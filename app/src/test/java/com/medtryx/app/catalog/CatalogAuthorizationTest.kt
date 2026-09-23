package com.medtryx.app.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CatalogAuthorizationTest {
 private lateinit var db:MedtryxDatabase; private lateinit var auth:AuthenticationService; private lateinit var catalog:CatalogService
 @Before fun setUp(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MedtryxDatabase::class.java).allowMainThreadQueries().build();auth=AuthenticationService(db,PasswordHasher(),"device");catalog=CatalogService(db,ProtectedActionAuthorizer(auth),auth)}
 @After fun close()=db.close()
 private fun draft()=ProductDraft("ITEM-1","Item",unit="piece",sellingPrice=BigDecimal("10.00"),taxClass=TaxClass.VATABLE,taxSource="authority",taxValidFrom=LocalDate.parse("2026-01-01"),benefitEligibility=BenefitEligibility.NONE,prescriptionClass=PrescriptionClass.OTC,reorderLevel=BigDecimal.ZERO,requiresLotExpiry=false)
 @Test fun cashier_cannot_change_price_below_ui_and_owner_change_is_versioned_and_audited()=runTest { val owner=auth.bootstrapOwner("owner","Owner","1234".toCharArray());val id=catalog.createProduct(owner.sessionId,draft(),"initial");val cashierId=auth.createUser(owner.sessionId,"cashier","Cashier",Role.CASHIER,"5678".toCharArray(),reason="hire");val cashier=(auth.login("cashier","5678".toCharArray()) as LoginResult.Success).session;try{catalog.changePrice(cashier.sessionId,id,BigDecimal("12.00"),null,"2026-02-01","bypass");fail()}catch(_:AccessDeniedException){};assertEquals(1,db.catalogDao().priceVersionCount(id));catalog.changePrice(owner.sessionId,id,BigDecimal("12.00"),null,"2026-02-01","approved change");assertEquals(2,db.catalogDao().priceVersionCount(id));assertTrue(db.authDao().auditEvents().any{it.action=="PRODUCT_PRICE_CHANGED"&&it.entityReference==id&&it.oldValue!=null&&it.newValue!=null});assertNotNull(cashierId) }
}
