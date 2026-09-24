package com.medtryx.app.financial

import com.medtryx.app.catalog.BenefitEligibility
import com.medtryx.app.catalog.TaxClass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** Deterministic, versioned encoding used by F05 to persist a complete immutable F03 snapshot. */
object CalculationSnapshotCodec {
    private const val FORMAT_VERSION = 1

    fun encode(snapshot: CalculationSnapshot): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(snapshot.sku)
            out.writeLong(snapshot.unitPrice.centavos)
            out.writeDate(snapshot.priceEffectiveFrom); out.writeNullableDate(snapshot.priceEffectiveTo)
            out.writeUTF(snapshot.quantity.toString())
            out.writeUTF(snapshot.taxClass.name); out.writeUTF(snapshot.taxSource)
            out.writeDate(snapshot.taxEffectiveFrom); out.writeNullableDate(snapshot.taxEffectiveTo)
            out.writeUTF(snapshot.benefitEligibility.name)
            out.writeDate(snapshot.benefitEffectiveFrom); out.writeNullableDate(snapshot.benefitEffectiveTo)
            out.writeNullableEnum(snapshot.selectedBenefit); out.writeBoolean(snapshot.qualifiedForSelectedBenefit)
            out.writeDate(snapshot.calculationDate); out.writeUTF(snapshot.taxResult.name); out.writeUTF(snapshot.discountSelection.name)
            out.writeNullableString(snapshot.promotionId); out.writeNullableString(snapshot.promotionVersion)
            out.writeNullableString(snapshot.promotionAuthority); out.writeNullableDecimal(snapshot.promotionRate)
            out.writeNullableDate(snapshot.promotionValidFrom); out.writeNullableDate(snapshot.promotionValidTo)
            out.writeNullableEnum(snapshot.promotionStackingPolicy); out.writeNullableEnum(snapshot.promotionTaxInteraction)
            out.writeBoolean(snapshot.promotionAuthorization != null)
            snapshot.promotionAuthorization?.let { authorization ->
                out.writeUTF(authorization.authorizedBy); out.writeInstant(authorization.authorizedAt)
                out.writeUTF(authorization.reason); out.writeNullableString(authorization.reference)
            }
            out.writeNullableString(snapshot.bnpcPolicyVersion); out.writeNullableDate(snapshot.bnpcPolicyValidFrom)
            out.writeNullableDate(snapshot.bnpcPolicyValidTo); out.writeNullableString(snapshot.bnpcPolicyAuthority)
            out.writeNullableString(snapshot.bnpcPolicyApprovedBy); out.writeNullableInstant(snapshot.bnpcPolicyApprovedAt)
            out.writeNullableDecimal(snapshot.bnpcMaximumDiscountableQuantity); out.writeUTF(snapshot.bnpcQuantityAlreadyDiscounted.toString())
            out.writeUTF(snapshot.roundingRuleId); out.writeUTF(snapshot.roundingRuleVersion)
            out.writeUTF(snapshot.roundingMode.name); out.writeUTF(snapshot.roundingApprovedBy); out.writeInstant(snapshot.roundingApprovedAt)
            out.writeUTF(snapshot.financialRuleSetId); out.writeUTF(snapshot.financialRuleSetVersion)
            out.writeUTF(snapshot.vatRate.toString()); out.writeUTF(snapshot.statutoryDiscountRate.toString())
            out.writeUTF(snapshot.unroundedGrossBasis.toString()); out.writeUTF(snapshot.unroundedVatExclusiveBasis.toString())
            out.writeLong(snapshot.amounts.gross.centavos); out.writeLong(snapshot.amounts.vatExclusiveBase.centavos)
            out.writeLong(snapshot.amounts.regularVat.centavos); out.writeLong(snapshot.amounts.vatExemptionAdjustment.centavos)
            out.writeLong(snapshot.amounts.statutoryDiscount.centavos); out.writeLong(snapshot.amounts.promotionalDiscount.centavos)
            out.writeLong(snapshot.amounts.amountDue.centavos); out.writeNullableString(snapshot.discountReason)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): CalculationSnapshot = DataInputStream(
        ByteArrayInputStream(Base64.getDecoder().decode(encoded)),
    ).use { input ->
        require(input.readInt() == FORMAT_VERSION) { "Unsupported calculation snapshot format." }
        val sku = input.readUTF(); val unitPrice = Money(input.readLong())
        val priceFrom = input.readDate(); val priceTo = input.readNullableDate()
        val quantity = BigDecimal(input.readUTF()); val taxClass = TaxClass.valueOf(input.readUTF())
        val taxSource = input.readUTF(); val taxFrom = input.readDate(); val taxTo = input.readNullableDate()
        val eligibility = BenefitEligibility.valueOf(input.readUTF()); val benefitFrom = input.readDate(); val benefitTo = input.readNullableDate()
        val selectedBenefit = input.readNullableEnum<CustomerBenefit>(); val qualified = input.readBoolean()
        val calculationDate = input.readDate(); val taxResult = TaxResult.valueOf(input.readUTF())
        val selection = DiscountSelection.valueOf(input.readUTF())
        val promotionId = input.readNullableString(); val promotionVersion = input.readNullableString()
        val promotionAuthority = input.readNullableString(); val promotionRate = input.readNullableDecimal()
        val promotionFrom = input.readNullableDate(); val promotionTo = input.readNullableDate()
        val stackingPolicy = input.readNullableEnum<DiscountStackingPolicy>()
        val taxInteraction = input.readNullableEnum<PromotionTaxInteraction>()
        val authorization = if (input.readBoolean()) DiscountAuthorization(
            input.readUTF(), input.readInstant(), input.readUTF(), input.readNullableString(),
        ) else null
        val bnpcVersion = input.readNullableString(); val bnpcFrom = input.readNullableDate(); val bnpcTo = input.readNullableDate()
        val bnpcAuthority = input.readNullableString(); val bnpcApprovedBy = input.readNullableString()
        val bnpcApprovedAt = input.readNullableInstant(); val bnpcMaxQuantity = input.readNullableDecimal()
        val bnpcAlreadyUsed = BigDecimal(input.readUTF())
        val roundingId = input.readUTF(); val roundingVersion = input.readUTF()
        val roundingMode = RoundingMode.valueOf(input.readUTF()); val roundingApprovedBy = input.readUTF(); val roundingApprovedAt = input.readInstant()
        val rulesId = input.readUTF(); val rulesVersion = input.readUTF(); val vatRate = BigDecimal(input.readUTF())
        val statutoryRate = BigDecimal(input.readUTF()); val grossBasis = BigDecimal(input.readUTF())
        val vatBasis = BigDecimal(input.readUTF())
        val amounts = CalculationAmounts(
            gross = Money(input.readLong()), vatExclusiveBase = Money(input.readLong()),
            regularVat = Money(input.readLong()), vatExemptionAdjustment = Money(input.readLong()),
            statutoryDiscount = Money(input.readLong()), promotionalDiscount = Money(input.readLong()),
            amountDue = Money(input.readLong()),
        )
        val reason = input.readNullableString()
        require(input.available() == 0) { "Calculation snapshot has trailing data." }
        CalculationSnapshot(
            sku = sku, unitPrice = unitPrice, priceEffectiveFrom = priceFrom, priceEffectiveTo = priceTo,
            quantity = quantity, taxClass = taxClass, taxSource = taxSource, taxEffectiveFrom = taxFrom,
            taxEffectiveTo = taxTo, benefitEligibility = eligibility, benefitEffectiveFrom = benefitFrom,
            benefitEffectiveTo = benefitTo, selectedBenefit = selectedBenefit,
            qualifiedForSelectedBenefit = qualified, calculationDate = calculationDate, taxResult = taxResult,
            discountSelection = selection, promotionId = promotionId, promotionVersion = promotionVersion,
            promotionAuthority = promotionAuthority, promotionRate = promotionRate, promotionValidFrom = promotionFrom,
            promotionValidTo = promotionTo, promotionStackingPolicy = stackingPolicy,
            promotionTaxInteraction = taxInteraction, promotionAuthorization = authorization,
            bnpcPolicyVersion = bnpcVersion, bnpcPolicyValidFrom = bnpcFrom, bnpcPolicyValidTo = bnpcTo,
            bnpcPolicyAuthority = bnpcAuthority, bnpcPolicyApprovedBy = bnpcApprovedBy,
            bnpcPolicyApprovedAt = bnpcApprovedAt, bnpcMaximumDiscountableQuantity = bnpcMaxQuantity,
            bnpcQuantityAlreadyDiscounted = bnpcAlreadyUsed, roundingRuleId = roundingId,
            roundingRuleVersion = roundingVersion, roundingMode = roundingMode,
            roundingApprovedBy = roundingApprovedBy, roundingApprovedAt = roundingApprovedAt,
            financialRuleSetId = rulesId, financialRuleSetVersion = rulesVersion, vatRate = vatRate,
            statutoryDiscountRate = statutoryRate, unroundedGrossBasis = grossBasis,
            unroundedVatExclusiveBasis = vatBasis, amounts = amounts, discountReason = reason,
        )
    }

    private fun DataOutputStream.writeDate(value: LocalDate) = writeUTF(value.toString())
    private fun DataOutputStream.writeNullableDate(value: LocalDate?) = writeNullableString(value?.toString())
    private fun DataOutputStream.writeInstant(value: Instant) = writeUTF(value.toString())
    private fun DataOutputStream.writeNullableInstant(value: Instant?) = writeNullableString(value?.toString())
    private fun DataOutputStream.writeNullableString(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataOutputStream.writeNullableDecimal(value: BigDecimal?) = writeNullableString(value?.toString())
    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) = writeNullableString(value?.name)
    private fun DataInputStream.readDate() = LocalDate.parse(readUTF())
    private fun DataInputStream.readNullableDate() = readNullableString()?.let(LocalDate::parse)
    private fun DataInputStream.readInstant() = Instant.parse(readUTF())
    private fun DataInputStream.readNullableInstant() = readNullableString()?.let(Instant::parse)
    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null
    private fun DataInputStream.readNullableDecimal() = readNullableString()?.let(::BigDecimal)
    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        readNullableString()?.let { enumValueOf<T>(it) }
}
