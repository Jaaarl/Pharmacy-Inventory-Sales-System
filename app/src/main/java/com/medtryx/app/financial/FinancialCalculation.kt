package com.medtryx.app.financial

import com.medtryx.app.catalog.BenefitEligibility
import com.medtryx.app.catalog.TaxClass
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** Integer-centavo value used at the financial domain boundary. */
@JvmInline
value class Money(val centavos: Long) : Comparable<Money> {
    override fun compareTo(other: Money): Int = centavos.compareTo(other.centavos)
    operator fun plus(other: Money): Money = Money(Math.addExact(centavos, other.centavos))
    operator fun minus(other: Money): Money = Money(Math.subtractExact(centavos, other.centavos))
    fun asDecimal(): BigDecimal = BigDecimal.valueOf(centavos, 2)

    companion object {
        val ZERO = Money(0)
        fun fromDecimal(value: BigDecimal): Money {
            require(value.scale() <= 2) { "Money must be expressed to centavo precision." }
            return Money(value.movePointRight(2).longValueExact())
        }
    }
}

/** Store-approved, versioned line rounding rule. Callers must supply it explicitly. */
data class RoundingRule(
    val id: String,
    val version: String,
    val mode: RoundingMode,
    val approvedBy: String,
    val approvedAt: Instant,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank())
        require(approvedBy.isNotBlank())
    }
}

data class FinancialRuleSet(
    val id: String,
    val version: String,
    val vatRate: BigDecimal,
    val seniorDiscountRate: BigDecimal,
    val pwdDiscountRate: BigDecimal,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank())
        require(vatRate >= BigDecimal.ZERO && vatRate < BigDecimal.ONE)
        require(seniorDiscountRate >= BigDecimal.ZERO && seniorDiscountRate <= BigDecimal.ONE)
        require(pwdDiscountRate >= BigDecimal.ZERO && pwdDiscountRate <= BigDecimal.ONE)
    }

    companion object {
        /** Rates are explicit and versioned; changing this object cannot mutate existing snapshots. */
        val PH_VAT_REGISTERED_MVP = FinancialRuleSet(
            id = "PH_VAT_REGISTERED",
            version = "1",
            vatRate = BigDecimal("0.12"),
            seniorDiscountRate = BigDecimal("0.20"),
            pwdDiscountRate = BigDecimal("0.20"),
        )
    }
}

enum class CustomerBenefit { SENIOR_CITIZEN, PERSON_WITH_DISABILITY }
enum class TaxResult { VATABLE, VAT_EXEMPT_CATALOG, VAT_EXEMPT_SC, VAT_EXEMPT_PWD, ZERO_RATED }
enum class DiscountSelection { NONE, STATUTORY_ONLY, PROMOTION_ONLY, STACK_PROMOTION_AFTER_STATUTORY }
enum class DiscountStackingPolicy { EXCLUSIVE, PROMOTION_AFTER_STATUTORY }
enum class PromotionTaxInteraction { AFTER_APPLICABLE_TAX_AND_STATUTORY_BENEFIT }

data class DiscountAuthorization(
    val authorizedBy: String,
    val authorizedAt: Instant,
    val reason: String,
    val reference: String? = null,
) {
    init {
        require(authorizedBy.isNotBlank() && reason.isNotBlank())
    }
}

/** Immutable copy of an approved promotion/OTHER rule, captured for reproducibility. */
data class PromotionRuleSnapshot(
    val id: String,
    val version: String,
    val rate: BigDecimal,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val requiresAuthorization: Boolean,
    val stackingPolicy: DiscountStackingPolicy,
    val taxInteraction: PromotionTaxInteraction,
    val authority: String,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank() && authority.isNotBlank())
        require(rate >= BigDecimal.ZERO && rate <= BigDecimal.ONE)
        require(validTo == null || !validTo.isBefore(validFrom))
    }
}

/** BNPC is deliberately unusable until a complete, enabled policy snapshot is supplied. */
data class BnpcPolicySnapshot(
    val version: String,
    val enabled: Boolean,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val coveredSkus: Set<String>,
    /** Maximum covered quantity by SKU for this customer purchase, in each SKU's sales unit. */
    val maxDiscountableQuantityBySku: Map<String, BigDecimal>,
    val rate: BigDecimal?,
    val authority: String?,
    val approvedBy: String?,
    val approvedAt: Instant?,
) {
    val isUsable: Boolean
        get() = enabled && version.isNotBlank() && validFrom != null && validTo != null && !validTo.isBefore(validFrom) &&
            coveredSkus.isNotEmpty() && maxDiscountableQuantityBySku.keys.containsAll(coveredSkus) &&
            maxDiscountableQuantityBySku.values.all { it > BigDecimal.ZERO && it.scale() <= 4 } &&
            rate != null && rate >= BigDecimal.ZERO && rate <= BigDecimal.ONE &&
            !authority.isNullOrBlank() && !approvedBy.isNullOrBlank() && approvedAt != null

    companion object {
        val DISABLED = BnpcPolicySnapshot(
            version = "unconfigured", enabled = false, validFrom = null, validTo = null,
            coveredSkus = emptySet(), maxDiscountableQuantityBySku = emptyMap(), rate = null, authority = null,
            approvedBy = null, approvedAt = null,
        )
    }
}

data class LineCalculationInput(
    val sku: String,
    val unitPrice: Money,
    val priceEffectiveFrom: LocalDate,
    val priceEffectiveTo: LocalDate?,
    val quantity: BigDecimal,
    val taxClass: TaxClass,
    val taxSource: String,
    val taxEffectiveFrom: LocalDate,
    val taxEffectiveTo: LocalDate?,
    val benefitEligibility: BenefitEligibility,
    val benefitEffectiveFrom: LocalDate,
    val benefitEffectiveTo: LocalDate?,
    val selectedBenefit: CustomerBenefit? = null,
    val qualifiedForSelectedBenefit: Boolean = false,
    val promotion: PromotionRuleSnapshot? = null,
    val promotionAuthorization: DiscountAuthorization? = null,
    val bnpcPolicy: BnpcPolicySnapshot = BnpcPolicySnapshot.DISABLED,
    val bnpcQuantityAlreadyDiscounted: BigDecimal = BigDecimal.ZERO,
    val selection: DiscountSelection = DiscountSelection.NONE,
    val calculationDate: LocalDate,
) {
    init {
        require(sku.isNotBlank())
        require(taxSource.isNotBlank())
        require(taxEffectiveTo == null || !taxEffectiveTo.isBefore(taxEffectiveFrom))
        require(priceEffectiveTo == null || !priceEffectiveTo.isBefore(priceEffectiveFrom))
        require(benefitEffectiveTo == null || !benefitEffectiveTo.isBefore(benefitEffectiveFrom))
        require(unitPrice.centavos >= 0)
        require(quantity > BigDecimal.ZERO && quantity.scale() <= 4) {
            "Quantity must be positive and have no more than four decimal places."
        }
        require(bnpcQuantityAlreadyDiscounted >= BigDecimal.ZERO && bnpcQuantityAlreadyDiscounted.scale() <= 4)
        require(!qualifiedForSelectedBenefit || selectedBenefit != null) {
            "A line cannot be marked qualified without a selected customer benefit."
        }
    }
}

data class CalculationAmounts(
    val gross: Money,
    val vatExclusiveBase: Money,
    val regularVat: Money,
    val vatExemptionAdjustment: Money,
    val statutoryDiscount: Money,
    val promotionalDiscount: Money,
    val amountDue: Money,
)

/** Financial inputs and outputs captured as a historical, self-contained snapshot. */
data class CalculationSnapshot(
    val sku: String,
    val unitPrice: Money,
    val priceEffectiveFrom: LocalDate,
    val priceEffectiveTo: LocalDate?,
    val quantity: BigDecimal,
    val taxClass: TaxClass,
    val taxSource: String,
    val taxEffectiveFrom: LocalDate,
    val taxEffectiveTo: LocalDate?,
    val benefitEligibility: BenefitEligibility,
    val benefitEffectiveFrom: LocalDate,
    val benefitEffectiveTo: LocalDate?,
    val selectedBenefit: CustomerBenefit?,
    val qualifiedForSelectedBenefit: Boolean,
    val taxResult: TaxResult,
    val discountSelection: DiscountSelection,
    val promotionId: String?,
    val promotionVersion: String?,
    val promotionAuthority: String?,
    val promotionRate: BigDecimal?,
    val promotionValidFrom: LocalDate?,
    val promotionValidTo: LocalDate?,
    val promotionStackingPolicy: DiscountStackingPolicy?,
    val promotionTaxInteraction: PromotionTaxInteraction?,
    val promotionAuthorization: DiscountAuthorization?,
    val bnpcPolicyVersion: String?,
    val bnpcPolicyValidFrom: LocalDate?,
    val bnpcPolicyValidTo: LocalDate?,
    val bnpcPolicyAuthority: String?,
    val bnpcPolicyApprovedBy: String?,
    val bnpcPolicyApprovedAt: Instant?,
    val bnpcMaximumDiscountableQuantity: BigDecimal?,
    val bnpcQuantityAlreadyDiscounted: BigDecimal,
    val roundingRuleId: String,
    val roundingRuleVersion: String,
    val roundingMode: RoundingMode,
    val roundingApprovedBy: String,
    val roundingApprovedAt: Instant,
    val financialRuleSetId: String,
    val financialRuleSetVersion: String,
    val vatRate: BigDecimal,
    val statutoryDiscountRate: BigDecimal,
    /** Exact unit-price × quantity before centavo rounding, stored as a plain decimal. */
    val unroundedGrossBasis: BigDecimal,
    /** Exact VAT-exclusive/discount basis before centavo rounding, when VAT is removed. */
    val unroundedVatExclusiveBasis: BigDecimal,
    val amounts: CalculationAmounts,
    val discountReason: String?,
)

data class CartCalculation(val lines: List<CalculationSnapshot>, val totalDue: Money) {
    init {
        require(totalDue == lines.fold(Money.ZERO) { total, line -> total + line.amounts.amountDue })
    }
}

object FinancialCalculationEngine {
    private const val INTERNAL_SCALE = 12

    fun calculateLine(
        input: LineCalculationInput,
        rules: FinancialRuleSet,
        rounding: RoundingRule,
    ): CalculationSnapshot {
        validateDiscountSelection(input)
        require(input.calculationDate >= input.priceEffectiveFrom &&
            (input.priceEffectiveTo == null || input.calculationDate <= input.priceEffectiveTo)) {
            "The captured selling price is not effective on the calculation date."
        }
        require(input.calculationDate >= input.taxEffectiveFrom &&
            (input.taxEffectiveTo == null || input.calculationDate <= input.taxEffectiveTo)) {
            "The captured tax classification is not effective on the calculation date."
        }
        require(input.calculationDate >= input.benefitEffectiveFrom &&
            (input.benefitEffectiveTo == null || input.calculationDate <= input.benefitEffectiveTo)) {
            "The captured benefit eligibility is not effective on the calculation date."
        }
        val rawGross = input.unitPrice.asDecimal().multiply(input.quantity)
        val gross = round(rawGross, rounding)
        val qualifies = input.qualifiedForSelectedBenefit && isEligible(input.benefitEligibility, input.selectedBenefit)
        val applyStatutoryBenefit = qualifies && input.selection in setOf(
            DiscountSelection.STATUTORY_ONLY,
            DiscountSelection.STACK_PROMOTION_AFTER_STATUTORY,
        )
        val isBnpc = input.benefitEligibility == BenefitEligibility.BNPC_5 &&
            input.selection == DiscountSelection.STATUTORY_ONLY
        if (isBnpc) validateBnpc(input)

        val unroundedVatExclusiveBasis = if (input.taxClass == TaxClass.VATABLE) {
            rawGross.divide(BigDecimal.ONE.add(rules.vatRate), INTERNAL_SCALE, rounding.mode)
        } else rawGross
        val vatExclusiveBase = round(unroundedVatExclusiveBasis, rounding)
        val removesVat = applyStatutoryBenefit && input.taxClass == TaxClass.VATABLE
        val taxResult = when {
            removesVat && input.selectedBenefit == CustomerBenefit.SENIOR_CITIZEN -> TaxResult.VAT_EXEMPT_SC
            removesVat -> TaxResult.VAT_EXEMPT_PWD
            input.taxClass == TaxClass.VATABLE -> TaxResult.VATABLE
            input.taxClass == TaxClass.VAT_EXEMPT -> TaxResult.VAT_EXEMPT_CATALOG
            else -> TaxResult.ZERO_RATED
        }
        val vatBaseForLine = if (input.taxClass == TaxClass.VATABLE) vatExclusiveBase else gross
        val regularVat = if (input.taxClass == TaxClass.VATABLE && !removesVat) gross - vatExclusiveBase else Money.ZERO
        val vatExemptionAdjustment = if (removesVat) gross - vatExclusiveBase else Money.ZERO
        val statutoryRate = when (input.selectedBenefit) {
            CustomerBenefit.SENIOR_CITIZEN -> rules.seniorDiscountRate
            CustomerBenefit.PERSON_WITH_DISABILITY -> rules.pwdDiscountRate
            null -> if (isBnpc) input.bnpcPolicy.rate!! else BigDecimal.ZERO
        }
        val bnpcAllowedQuantity = if (isBnpc) {
            (input.bnpcPolicy.maxDiscountableQuantityBySku.getValue(input.sku) - input.bnpcQuantityAlreadyDiscounted)
                .max(BigDecimal.ZERO).min(input.quantity)
        } else BigDecimal.ZERO
        val statutoryBasis = if (isBnpc) {
            round(input.unitPrice.asDecimal().multiply(bnpcAllowedQuantity), rounding)
        } else vatBaseForLine
        val statutoryDiscount = if (qualifies || isBnpc) round(
            statutoryBasis.asDecimal().multiply(statutoryRate), rounding,
        ) else Money.ZERO

        val promotion = input.promotion
        val promotionActive = promotion != null && input.calculationDate >= promotion.validFrom &&
            (promotion.validTo == null || input.calculationDate <= promotion.validTo)
        val promotionBase = if (input.selection == DiscountSelection.STACK_PROMOTION_AFTER_STATUTORY && qualifies) {
            vatBaseForLine - statutoryDiscount
        } else gross
        val promotionalDiscount = if (promotionActive && input.selection in setOf(
                DiscountSelection.PROMOTION_ONLY, DiscountSelection.STACK_PROMOTION_AFTER_STATUTORY,
            )
        ) round(promotionBase.asDecimal().multiply(promotion!!.rate), rounding) else Money.ZERO

        val appliedStatutory = when (input.selection) {
            DiscountSelection.NONE, DiscountSelection.PROMOTION_ONLY -> Money.ZERO
            else -> statutoryDiscount
        }
        val dueBasis = if (removesVat) vatExclusiveBase else gross
        val amountDue = dueBasis - appliedStatutory - promotionalDiscount
        require(amountDue.centavos >= 0) { "Discounts cannot make a line amount due negative." }
        val reason = when {
            appliedStatutory.centavos > 0 && promotionalDiscount.centavos > 0 -> "Configured statutory and promotional stacking"
            appliedStatutory.centavos > 0 && input.selectedBenefit == CustomerBenefit.SENIOR_CITIZEN -> "Qualified senior-citizen benefit"
            appliedStatutory.centavos > 0 && input.selectedBenefit == CustomerBenefit.PERSON_WITH_DISABILITY -> "Qualified PWD benefit"
            isBnpc && appliedStatutory.centavos > 0 -> "Configured BNPC policy ${input.bnpcPolicy.version}"
            promotionalDiscount.centavos > 0 -> promotion!!.authority
            else -> null
        }
        return CalculationSnapshot(
            sku = input.sku,
            unitPrice = input.unitPrice,
            priceEffectiveFrom = input.priceEffectiveFrom,
            priceEffectiveTo = input.priceEffectiveTo,
            quantity = input.quantity,
            taxClass = input.taxClass,
            taxSource = input.taxSource,
            taxEffectiveFrom = input.taxEffectiveFrom,
            taxEffectiveTo = input.taxEffectiveTo,
            benefitEligibility = input.benefitEligibility,
            benefitEffectiveFrom = input.benefitEffectiveFrom,
            benefitEffectiveTo = input.benefitEffectiveTo,
            selectedBenefit = input.selectedBenefit,
            qualifiedForSelectedBenefit = qualifies,
            taxResult = taxResult,
            discountSelection = input.selection,
            promotionId = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.id,
            promotionVersion = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.version,
            promotionAuthority = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.authority,
            promotionRate = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.rate,
            promotionValidFrom = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.validFrom,
            promotionValidTo = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.validTo,
            promotionStackingPolicy = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.stackingPolicy,
            promotionTaxInteraction = promotion?.takeIf { promotionalDiscount.centavos > 0 }?.taxInteraction,
            promotionAuthorization = input.promotionAuthorization.takeIf { promotionalDiscount.centavos > 0 },
            bnpcPolicyVersion = input.bnpcPolicy.version.takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcPolicyValidFrom = input.bnpcPolicy.validFrom.takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcPolicyValidTo = input.bnpcPolicy.validTo.takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcPolicyAuthority = input.bnpcPolicy.authority.takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcPolicyApprovedBy = input.bnpcPolicy.approvedBy.takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcPolicyApprovedAt = input.bnpcPolicy.approvedAt.takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcMaximumDiscountableQuantity = input.bnpcPolicy.maxDiscountableQuantityBySku[input.sku]
                .takeIf { appliedStatutory.centavos > 0 && isBnpc },
            bnpcQuantityAlreadyDiscounted = input.bnpcQuantityAlreadyDiscounted,
            roundingRuleId = rounding.id,
            roundingRuleVersion = rounding.version,
            roundingMode = rounding.mode,
            roundingApprovedBy = rounding.approvedBy,
            roundingApprovedAt = rounding.approvedAt,
            financialRuleSetId = rules.id,
            financialRuleSetVersion = rules.version,
            vatRate = rules.vatRate,
            statutoryDiscountRate = statutoryRate,
            unroundedGrossBasis = rawGross,
            unroundedVatExclusiveBasis = unroundedVatExclusiveBasis,
            amounts = CalculationAmounts(
                gross = gross,
                vatExclusiveBase = vatExclusiveBase,
                regularVat = regularVat,
                vatExemptionAdjustment = vatExemptionAdjustment,
                statutoryDiscount = appliedStatutory,
                promotionalDiscount = promotionalDiscount,
                amountDue = amountDue,
            ),
            discountReason = reason,
        )
    }

    fun compareStatutoryAndPromotion(
        input: LineCalculationInput,
        rules: FinancialRuleSet,
        rounding: RoundingRule,
    ): Pair<CalculationSnapshot, CalculationSnapshot> {
        require(input.selectedBenefit != null && input.qualifiedForSelectedBenefit)
        require(input.promotion != null)
        val statutory = calculateLine(input.copy(selection = DiscountSelection.STATUTORY_ONLY), rules, rounding)
        val promotionChoice = if (input.promotion!!.stackingPolicy == DiscountStackingPolicy.EXCLUSIVE) {
            DiscountSelection.PROMOTION_ONLY
        } else {
            DiscountSelection.STACK_PROMOTION_AFTER_STATUTORY
        }
        val promotion = calculateLine(input.copy(selection = promotionChoice), rules, rounding)
        return statutory to promotion
    }

    fun calculateCart(
        lines: List<LineCalculationInput>,
        rules: FinancialRuleSet,
        rounding: RoundingRule,
    ): CartCalculation {
        val snapshots = lines.map { calculateLine(it, rules, rounding) }
        val total = snapshots.fold(Money.ZERO) { sum, line -> sum + line.amounts.amountDue }
        return CartCalculation(snapshots, total)
    }

    private fun validateDiscountSelection(input: LineCalculationInput) {
        val qualified = input.qualifiedForSelectedBenefit && isEligible(input.benefitEligibility, input.selectedBenefit)
        when (input.selection) {
            DiscountSelection.NONE -> Unit
            DiscountSelection.STATUTORY_ONLY -> require(qualified || input.benefitEligibility == BenefitEligibility.BNPC_5) {
                "This line is not eligible for the selected statutory benefit."
            }
            DiscountSelection.PROMOTION_ONLY -> validatePromotion(input)
            DiscountSelection.STACK_PROMOTION_AFTER_STATUTORY -> {
                require(qualified) { "A statutory benefit must qualify before a promotion can stack." }
                validatePromotion(input)
                require(input.promotion!!.stackingPolicy == DiscountStackingPolicy.PROMOTION_AFTER_STATUTORY) {
                    "The configured promotion does not allow stacking after a statutory benefit."
                }
            }
        }
    }

    private fun validatePromotion(input: LineCalculationInput) {
        val rule = requireNotNull(input.promotion) { "A configured promotion rule is required." }
        require(input.calculationDate >= rule.validFrom && (rule.validTo == null || input.calculationDate <= rule.validTo)) {
            "The promotion is not valid on the calculation date."
        }
        if (rule.requiresAuthorization) requireNotNull(input.promotionAuthorization) {
            "This promotion requires protected-role authorization."
        }
        if (input.selection == DiscountSelection.PROMOTION_ONLY) require(rule.stackingPolicy == DiscountStackingPolicy.EXCLUSIVE) {
            "This promotion is configured to apply only after the statutory benefit."
        }
    }

    private fun validateBnpc(input: LineCalculationInput) {
        val policy = input.bnpcPolicy
        require(input.selectedBenefit == null && !input.qualifiedForSelectedBenefit) {
            "BNPC is a separate benefit and cannot be combined with SC/PWD qualification."
        }
        require(policy.isUsable) { "BNPC is disabled or its policy is not completely configured." }
        require(input.benefitEligibility == BenefitEligibility.BNPC_5 && input.sku in policy.coveredSkus) {
            "This SKU is not covered by the configured BNPC policy."
        }
        require(input.calculationDate >= policy.validFrom!! && input.calculationDate <= policy.validTo!!) {
            "The BNPC policy is not valid on the calculation date."
        }
    }

    private fun isEligible(eligibility: BenefitEligibility, benefit: CustomerBenefit?): Boolean = when (benefit) {
        CustomerBenefit.SENIOR_CITIZEN, CustomerBenefit.PERSON_WITH_DISABILITY -> eligibility == BenefitEligibility.SC_PWD_20
        null -> false
    }

    private fun round(value: BigDecimal, rule: RoundingRule): Money = Money(
        value.setScale(2, rule.mode).movePointRight(2).longValueExact(),
    )
}
