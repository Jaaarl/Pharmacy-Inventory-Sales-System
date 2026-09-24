package com.medtryx.app.financial

import com.medtryx.app.catalog.BenefitEligibility
import com.medtryx.app.catalog.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

class FinancialCalculationEngineTest {
    private val date = LocalDate.parse("2026-06-01")
    private val rounding = RoundingRule("LINE-CENT", "1", RoundingMode.HALF_UP, "owner-1", Instant.parse("2026-01-01T00:00:00Z"))
    private val rules = FinancialRuleSet.PH_VAT_REGISTERED_MVP

    private fun input(
        price: String = "100.00",
        quantity: String = "1",
        tax: TaxClass = TaxClass.VATABLE,
        eligibility: BenefitEligibility = BenefitEligibility.NONE,
        benefit: CustomerBenefit? = null,
        qualified: Boolean = false,
        selection: DiscountSelection = DiscountSelection.NONE,
        promotion: PromotionRuleSnapshot? = null,
        promotionAuthorization: DiscountAuthorization? = null,
    ) = LineCalculationInput(
        sku = "SKU-1",
        unitPrice = Money.fromDecimal(BigDecimal(price)),
        priceEffectiveFrom = LocalDate.parse("2026-01-01"),
        priceEffectiveTo = null,
        quantity = BigDecimal(quantity),
        taxClass = tax,
        taxSource = "approved catalog source",
        taxEffectiveFrom = LocalDate.parse("2026-01-01"),
        taxEffectiveTo = null,
        benefitEligibility = eligibility,
        benefitEffectiveFrom = LocalDate.parse("2026-01-01"),
        benefitEffectiveTo = null,
        selectedBenefit = benefit,
        qualifiedForSelectedBenefit = qualified,
        promotion = promotion,
        promotionAuthorization = promotionAuthorization,
        selection = selection,
        calculationDate = date,
    )

    private data class Expected(
        val gross: Long,
        val base: Long,
        val vat: Long,
        val vatAdjustment: Long,
        val statutoryDiscount: Long,
        val promotionalDiscount: Long = 0,
        val due: Long,
        val taxResult: TaxResult,
    )

    @Test fun table_driven_tax_and_benefit_cases_match_exact_centavos() {
        val cases = listOf(
            "regular VATable" to input() to Expected(10_000, 8_929, 1_071, 0, 0, due = 10_000, taxResult = TaxResult.VATABLE),
            "VAT-exempt catalog" to input(tax = TaxClass.VAT_EXEMPT) to Expected(10_000, 10_000, 0, 0, 0, due = 10_000, taxResult = TaxResult.VAT_EXEMPT_CATALOG),
            "zero-rated catalog" to input(tax = TaxClass.ZERO_RATED) to Expected(10_000, 10_000, 0, 0, 0, due = 10_000, taxResult = TaxResult.ZERO_RATED),
            "senior qualified VATable" to input(eligibility = BenefitEligibility.SC_PWD_20, benefit = CustomerBenefit.SENIOR_CITIZEN, qualified = true, selection = DiscountSelection.STATUTORY_ONLY) to Expected(10_000, 8_929, 0, 1_071, 1_786, due = 7_143, taxResult = TaxResult.VAT_EXEMPT_SC),
            "PWD qualified VATable" to input(eligibility = BenefitEligibility.SC_PWD_20, benefit = CustomerBenefit.PERSON_WITH_DISABILITY, qualified = true, selection = DiscountSelection.STATUTORY_ONLY) to Expected(10_000, 8_929, 0, 1_071, 1_786, due = 7_143, taxResult = TaxResult.VAT_EXEMPT_PWD),
            "SC on already-exempt item" to input(tax = TaxClass.VAT_EXEMPT, eligibility = BenefitEligibility.SC_PWD_20, benefit = CustomerBenefit.SENIOR_CITIZEN, qualified = true, selection = DiscountSelection.STATUTORY_ONLY) to Expected(10_000, 10_000, 0, 0, 2_000, due = 8_000, taxResult = TaxResult.VAT_EXEMPT_CATALOG),
            "quantity three" to input(price = "10.00", quantity = "3") to Expected(3_000, 2_679, 321, 0, 0, due = 3_000, taxResult = TaxResult.VATABLE),
        )

        cases.forEach { (labelAndInput, expected) ->
            val (label, line) = labelAndInput
            val actual = FinancialCalculationEngine.calculateLine(line, rules, rounding)
            assertEquals("$label gross", expected.gross, actual.amounts.gross.centavos)
            assertEquals("$label base", expected.base, actual.amounts.vatExclusiveBase.centavos)
            assertEquals("$label VAT", expected.vat, actual.amounts.regularVat.centavos)
            assertEquals("$label VAT adjustment", expected.vatAdjustment, actual.amounts.vatExemptionAdjustment.centavos)
            assertEquals("$label statutory discount", expected.statutoryDiscount, actual.amounts.statutoryDiscount.centavos)
            assertEquals("$label promotional discount", expected.promotionalDiscount, actual.amounts.promotionalDiscount.centavos)
            assertEquals("$label due", expected.due, actual.amounts.amountDue.centavos)
            assertEquals("$label tax result", expected.taxResult, actual.taxResult)
        }
    }

    @Test fun mixed_cart_is_line_scoped_and_order_independent() {
        val qualifiedMedicine = input(eligibility = BenefitEligibility.SC_PWD_20, benefit = CustomerBenefit.SENIOR_CITIZEN, qualified = true, selection = DiscountSelection.STATUTORY_ONLY)
        val regularRetail = input(price = "150.00", eligibility = BenefitEligibility.NONE)
        val first = FinancialCalculationEngine.calculateCart(listOf(qualifiedMedicine, regularRetail), rules, rounding)
        val reversed = FinancialCalculationEngine.calculateCart(listOf(regularRetail, qualifiedMedicine), rules, rounding)

        assertEquals(22_143, first.totalDue.centavos)
        assertEquals(first.totalDue, reversed.totalDue)
        assertEquals(0, first.lines[1].amounts.vatExemptionAdjustment.centavos)
        assertEquals(1_607, first.lines[1].amounts.regularVat.centavos)
    }

    @Test fun promotions_are_validated_compared_and_stacked_only_when_configured() {
        val exclusive = PromotionRuleSnapshot(
            "PROMO-10", "v2", BigDecimal("0.10"), date.minusDays(1), date.plusDays(1), true,
            DiscountStackingPolicy.EXCLUSIVE, PromotionTaxInteraction.AFTER_APPLICABLE_TAX_AND_STATUTORY_BENEFIT,
            "approved campaign",
        )
        rejects { FinancialCalculationEngine.calculateLine(input(selection = DiscountSelection.PROMOTION_ONLY, promotion = exclusive), rules, rounding) }
        val authorization = DiscountAuthorization("supervisor-1", Instant.parse("2026-06-01T01:00:00Z"), "campaign approval", "AUTH-1")
        val line = input(eligibility = BenefitEligibility.SC_PWD_20, benefit = CustomerBenefit.SENIOR_CITIZEN, qualified = true, promotion = exclusive, promotionAuthorization = authorization)
        val (statutory, promotion) = FinancialCalculationEngine.compareStatutoryAndPromotion(line, rules, rounding)
        assertEquals(7_143, statutory.amounts.amountDue.centavos)
        assertEquals(9_000, promotion.amounts.amountDue.centavos)
        assertEquals(1_000, promotion.amounts.promotionalDiscount.centavos)
        assertEquals(authorization, promotion.promotionAuthorization)
        assertEquals(TaxResult.VATABLE, promotion.taxResult)

        val stackable = exclusive.copy(stackingPolicy = DiscountStackingPolicy.PROMOTION_AFTER_STATUTORY)
        val stacked = FinancialCalculationEngine.calculateLine(
            line.copy(promotion = stackable, selection = DiscountSelection.STACK_PROMOTION_AFTER_STATUTORY), rules, rounding,
        )
        assertEquals(714, stacked.amounts.promotionalDiscount.centavos)
        assertEquals(6_429, stacked.amounts.amountDue.centavos)
    }

    @Test fun bnpc_is_disabled_by_default_and_respects_approved_sku_quantity_cap_without_vat_exemption() {
        val disabled = input(eligibility = BenefitEligibility.BNPC_5, selection = DiscountSelection.STATUTORY_ONLY)
        rejects { FinancialCalculationEngine.calculateLine(disabled, rules, rounding) }

        val policy = BnpcPolicySnapshot(
            version = "bnpc-v3", enabled = true, validFrom = date.minusDays(1), validTo = date.plusDays(1),
            coveredSkus = setOf("SKU-1"), maxDiscountableQuantityBySku = mapOf("SKU-1" to BigDecimal("1.0")),
            rate = BigDecimal("0.05"), authority = "approved BNPC policy", approvedBy = "admin-1",
            approvedAt = Instant.parse("2026-05-31T00:00:00Z"),
        )
        val capped = FinancialCalculationEngine.calculateLine(
            disabled.copy(quantity = BigDecimal("2"), bnpcPolicy = policy), rules, rounding,
        )
        assertEquals(500, capped.amounts.statutoryDiscount.centavos)
        assertEquals(19_500, capped.amounts.amountDue.centavos)
        assertEquals(2_143, capped.amounts.regularVat.centavos)
        assertEquals("bnpc-v3", capped.bnpcPolicyVersion)

        val exhausted = FinancialCalculationEngine.calculateLine(
            disabled.copy(bnpcPolicy = policy, bnpcQuantityAlreadyDiscounted = BigDecimal.ONE), rules, rounding,
        )
        assertEquals(0, exhausted.amounts.statutoryDiscount.centavos)
        assertEquals(10_000, exhausted.amounts.amountDue.centavos)
    }

    @Test fun centavo_rounding_boundaries_follow_the_approved_rounding_mode() {
        val halfCent = input(price = "0.01", quantity = "0.5")
        val halfUp = FinancialCalculationEngine.calculateLine(halfCent, rules, rounding)
        val halfEven = FinancialCalculationEngine.calculateLine(
            halfCent, rules, rounding.copy(version = "2", mode = RoundingMode.HALF_EVEN),
        )
        assertEquals(1, halfUp.amounts.gross.centavos)
        assertEquals(0, halfEven.amounts.gross.centavos)
        assertEquals("1", halfUp.roundingRuleVersion)
        assertEquals(RoundingMode.HALF_EVEN, halfEven.roundingMode)
    }

    @Test fun component_and_cart_invariants_hold_across_a_table_of_prices_and_quantities() {
        val lines = (1L..250L).map { cents ->
            input(price = BigDecimal.valueOf(cents, 2).toPlainString(), quantity = ((cents % 7) + 1).toString())
        }
        val cart = FinancialCalculationEngine.calculateCart(lines, rules, rounding)
        assertTrue(cart.lines.all { it.amounts.amountDue.centavos >= 0 })
        assertTrue(cart.lines.all {
            it.amounts.vatExclusiveBase + it.amounts.regularVat == it.amounts.gross
        })
        assertEquals(cart.lines.sumOf { it.amounts.amountDue.centavos }, cart.totalDue.centavos)
        assertEquals(cart.totalDue, FinancialCalculationEngine.calculateCart(lines.reversed(), rules, rounding).totalDue)
    }

    @Test fun changed_rule_version_does_not_mutate_an_existing_historical_snapshot() {
        val eligible = input(eligibility = BenefitEligibility.SC_PWD_20, benefit = CustomerBenefit.SENIOR_CITIZEN, qualified = true, selection = DiscountSelection.STATUTORY_ONLY)
        val oldSnapshot = FinancialCalculationEngine.calculateLine(eligible, rules, rounding)
        val newRules = rules.copy(version = "2", seniorDiscountRate = BigDecimal("0.10"))
        val newSnapshot = FinancialCalculationEngine.calculateLine(eligible, newRules, rounding)

        assertEquals("1", oldSnapshot.financialRuleSetVersion)
        assertEquals(1_786, oldSnapshot.amounts.statutoryDiscount.centavos)
        assertEquals("2", newSnapshot.financialRuleSetVersion)
        assertEquals(893, newSnapshot.amounts.statutoryDiscount.centavos)
        assertNotEquals(oldSnapshot, newSnapshot)
        assertEquals(1_786, oldSnapshot.amounts.statutoryDiscount.centavos)
    }

    @Test fun outdated_catalog_effective_dates_and_invalid_stacking_are_rejected() {
        val expiredPrice = input().copy(priceEffectiveTo = date.minusDays(1))
        rejects { FinancialCalculationEngine.calculateLine(expiredPrice, rules, rounding) }
        val promotion = PromotionRuleSnapshot(
            "PROMO", "1", BigDecimal("0.10"), date.minusDays(5), date.minusDays(1), false,
            DiscountStackingPolicy.EXCLUSIVE, PromotionTaxInteraction.AFTER_APPLICABLE_TAX_AND_STATUTORY_BENEFIT, "campaign",
        )
        rejects { FinancialCalculationEngine.calculateLine(input(selection = DiscountSelection.PROMOTION_ONLY, promotion = promotion), rules, rounding) }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected the invalid calculation to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
