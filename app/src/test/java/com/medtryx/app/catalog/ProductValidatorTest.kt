package com.medtryx.app.catalog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ProductValidatorTest {
 private fun draft()=ProductDraft("PARA-500","Paracetamol",unit="tablet",sellingPrice=BigDecimal("12.50"),taxClass=TaxClass.VATABLE,taxSource="Approved source",taxValidFrom=LocalDate.parse("2026-01-01"),benefitEligibility=BenefitEligibility.SC_PWD_20,prescriptionClass=PrescriptionClass.OTC,reorderLevel=BigDecimal.ONE,requiresLotExpiry=true,openingLots=listOf(OpeningLotDraft("L1",LocalDate.parse("2027-01-01"),BigDecimal.ONE)))
 @Test fun valid_product_is_accepted() { assertTrue(ProductValidator.validate(draft()).isEmpty()) }
 @Test fun rejects_invalid_price_dates_pack_and_missing_medicine_lot_expiry() { val bad=draft().copy(sellingPrice=BigDecimal("1.999"),packSize=BigDecimal.ZERO,taxValidTo=LocalDate.parse("2025-01-01"),openingLots=listOf(OpeningLotDraft("",null,BigDecimal.ZERO))); val fields=ProductValidator.validate(bad).map{it.field}; assertTrue("sellingPrice" in fields && "packSize" in fields && "taxValidTo" in fields && "openingLots[0]" in fields) }
}
