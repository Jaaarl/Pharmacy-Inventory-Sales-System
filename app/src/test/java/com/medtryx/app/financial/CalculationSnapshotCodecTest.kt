package com.medtryx.app.financial

import com.medtryx.app.catalog.BenefitEligibility
import com.medtryx.app.catalog.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

class CalculationSnapshotCodecTest {
    @Test fun snapshot_round_trips_all_inputs_rules_outputs_and_dates() {
        val date = LocalDate.parse("2026-06-01")
        val approved = Instant.parse("2026-05-31T10:00:00Z")
        val input = LineCalculationInput(
            sku = "SKU-1", unitPrice = Money(11200), priceEffectiveFrom = date.minusDays(1), priceEffectiveTo = null,
            quantity = BigDecimal("2"), taxClass = TaxClass.VATABLE, taxSource = "approved basis",
            taxEffectiveFrom = date.minusDays(10), taxEffectiveTo = null,
            benefitEligibility = BenefitEligibility.SC_PWD_20, benefitEffectiveFrom = date.minusDays(10),
            benefitEffectiveTo = null, calculationDate = date,
            selectedBenefit = CustomerBenefit.SENIOR_CITIZEN, qualifiedForSelectedBenefit = true,
            selection = DiscountSelection.STATUTORY_ONLY,
        )
        val rounding = RoundingRule("round-1", "3", RoundingMode.HALF_UP, "owner-1", approved)
        val expected = FinancialCalculationEngine.calculateLine(input, FinancialRuleSet.PH_VAT_REGISTERED_MVP, rounding)

        val actual = CalculationSnapshotCodec.decode(CalculationSnapshotCodec.encode(expected))

        assertEquals(expected, actual)
        assertEquals(date, actual.calculationDate)
        assertEquals(16000L, actual.amounts.amountDue.centavos)
    }
}
