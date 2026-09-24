package com.medtryx.app.sales

import androidx.room.withTransaction
import com.medtryx.app.auth.AuthenticatedSession
import com.medtryx.app.auth.AuthenticationService
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.PermissionPolicy
import com.medtryx.app.auth.ProtectedActionAuthorizer
import com.medtryx.app.auth.requirePermission
import com.medtryx.app.catalog.BenefitEligibility
import com.medtryx.app.catalog.InventoryMovementEntity
import com.medtryx.app.catalog.InventoryMovementType
import com.medtryx.app.catalog.InventoryService
import com.medtryx.app.catalog.TaxClass
import com.medtryx.app.financial.CalculationSnapshot
import com.medtryx.app.financial.CalculationSnapshotCodec
import com.medtryx.app.financial.CustomerBenefit
import com.medtryx.app.financial.DiscountSelection
import com.medtryx.app.financial.FinancialCalculationEngine
import com.medtryx.app.financial.FinancialRuleSet
import com.medtryx.app.financial.LineCalculationInput
import com.medtryx.app.financial.Money
import com.medtryx.app.financial.RoundingRule
import com.medtryx.app.financial.TaxResult
import com.medtryx.app.security.SensitiveDataProtector
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class FinalizationCheckpoint {
    AFTER_VALIDATION,
    AFTER_SEQUENCE,
    AFTER_SALE_HEADER,
    AFTER_SALE_LINES,
    AFTER_INVENTORY_MOVEMENT,
    AFTER_ALLOCATION,
    AFTER_AUDIT,
}

/** One application/use-case boundary for checkout preview, sensitive-data handling, and atomic sale finalization. */
class SaleFinalizationService(
    private val database: MedtryxDatabase,
    private val authentication: AuthenticationService,
    private val authorizer: ProtectedActionAuthorizer,
    private val inventory: InventoryService,
    private val sensitiveDataProtector: SensitiveDataProtector,
    private val clock: () -> Instant = Instant::now,
    private val today: () -> LocalDate = { LocalDate.now(STORE_ZONE) },
    private val failureInjector: (FinalizationCheckpoint) -> Unit = {},
) {
    suspend fun currentRoundingRule(): RoundingRule? = database.salesDao().latestRoundingRule()?.toDomain()

    suspend fun approveRoundingRule(sessionId: String, mode: RoundingMode, reason: String): RoundingRule {
        require(reason.isNotBlank()) { "A reason is required." }
        val actor = authorizer.require(
            sessionId, Permission.ROUNDING_CONFIGURATION_CHANGE, "ROUNDING_RULE_APPROVAL", "STORE", reason,
        )
        return database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            require(active.userId == actor.userId)
            active.profile.requirePermission(Permission.ROUNDING_CONFIGURATION_CHANGE)
            val previous = database.salesDao().latestRoundingRule()
            val nextVersion = (previous?.version?.toIntOrNull() ?: 0) + 1
            val entity = RoundingRuleApprovalEntity(
                id = UUID.randomUUID().toString(), version = nextVersion.toString(), mode = mode.name,
                approvedByUserId = active.userId, approvedAtUtcMillis = clock().toEpochMilli(), reason = reason.trim(),
            )
            database.salesDao().insertRoundingRule(entity)
            authorizer.recordApplicationAudit(
                sessionId, "ROUNDING_RULE_APPROVED", entity.id, reason,
                previous?.let { "version=${it.version};mode=${it.mode}" }, "version=${entity.version};mode=${entity.mode}",
            )
            entity.toDomain()
        }
    }

    suspend fun preview(sessionId: String, draft: CheckoutDraft): CheckoutPreview {
        val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
        active.profile.requirePermission(Permission.CHECKOUT_CREATE)
        val date = today()
        val rounding = currentRoundingRule() ?: error("A protected user must approve the store rounding rule before checkout.")
        val lines = calculateLines(draft, date, rounding)
        lines.groupBy { it.productId }.forEach { (productId, productLines) ->
            inventory.allocateEarliestExpiry(productId, productLines.fold(BigDecimal.ZERO) { total, line -> total + line.quantity })
        }
        return CheckoutPreview(draft, lines, lines.fold(Money.ZERO) { total, line -> total + line.calculation.amounts.amountDue })
    }

    suspend fun finalize(sessionId: String, draft: CheckoutDraft): SaleSummary {
        validateDraft(draft)
        val actor = authorizer.require(
            sessionId, Permission.CHECKOUT_CREATE, "SALE_FINALIZATION", draft.idempotencyKey, "Cashier confirmed checkout",
        )
        val saleId = UUID.randomUUID().toString()
        val createdAt = clock()
        val businessDate = createdAt.atZone(STORE_ZONE).toLocalDate()
        return database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            require(active.userId == actor.userId) { "Active cashier changed during checkout." }
            active.profile.requirePermission(Permission.CHECKOUT_CREATE)
            database.salesDao().saleByIdempotencyKey(draft.idempotencyKey)?.let { existing ->
                require(existing.cashierUserId == active.userId) { "Checkout idempotency key belongs to another cashier." }
                return@withTransaction summary(existing)
            }
            val rounding = database.salesDao().latestRoundingRule()?.toDomain()
                ?: error("A protected user must approve the store rounding rule before checkout.")
            val session = requireNotNull(database.authDao().session(sessionId)) { "Session is no longer active." }
            val activeShift = requireNotNull(database.shiftDao().openShift(
                com.medtryx.app.shifts.ShiftService.STORE_ID, session.deviceId, active.userId,
            )) { "Open your cashier shift before finalizing a sale." }
            val lines = calculateLines(draft, businessDate, rounding)
            failureInjector(FinalizationCheckpoint.AFTER_VALIDATION)
            val transactionId = nextTransactionId(businessDate)
            failureInjector(FinalizationCheckpoint.AFTER_SEQUENCE)
            val lineCalculations = lines.map { it.calculation }
            val totals = totals(lineCalculations)
            val user = database.authDao().findUserById(active.userId) ?: error("Cashier account does not exist.")
            val customerName = draft.customerName?.trim()
            val customerId = draft.benefitIdNumber?.trim()
            val sale = SaleEntity(
                id = saleId,
                humanTransactionId = transactionId,
                idempotencyKey = draft.idempotencyKey,
                status = SaleStatus.FINALIZED,
                cashierUserId = active.userId,
                cashierDisplayName = user.displayName,
                shiftId = activeShift.id,
                createdAtUtcMillis = createdAt.toEpochMilli(),
                businessDateManila = businessDate.toString(),
                customerBenefit = draft.customerBenefit,
                customerNameEncrypted = customerName?.let(sensitiveDataProtector::protect),
                benefitIdType = draft.benefitIdType,
                benefitIdNumberEncrypted = customerId?.let(sensitiveDataProtector::protect),
                benefitIdLastFour = customerId?.takeLast(4),
                physicalIdChecked = draft.physicalIdChecked,
                settlementMethod = draft.settlementMethod,
                qrReference = draft.qrReference?.trim()?.takeIf(String::isNotBlank),
                customerShowedQrSuccess = draft.customerShowedQrSuccess,
                grossCentavos = totals.gross.centavos,
                vatableSalesCentavos = totals.vatableSales.centavos,
                vatCentavos = totals.vat.centavos,
                vatExemptSalesCentavos = totals.vatExemptSales.centavos,
                zeroRatedSalesCentavos = totals.zeroRatedSales.centavos,
                vatExemptionAdjustmentCentavos = totals.vatAdjustment.centavos,
                statutoryDiscountCentavos = totals.statutoryDiscount.centavos,
                promotionalDiscountCentavos = totals.promotionalDiscount.centavos,
                amountDueCentavos = totals.amountDue.centavos,
                lineCount = lines.size,
            )
            database.salesDao().insertSale(sale)
            failureInjector(FinalizationCheckpoint.AFTER_SALE_HEADER)

            val saleLines = lines.mapIndexed { index, line -> line.toSaleLine(saleId, index + 1) }
            database.salesDao().insertSaleLines(saleLines)
            failureInjector(FinalizationCheckpoint.AFTER_SALE_LINES)

            val lineAllocations = mutableListOf<SaleLineAllocationEntity>()
            lines.forEachIndexed { index, line ->
                val freshAllocations = inventory.allocateEarliestExpiry(line.productId, line.quantity)
                val product = database.catalogDao().productById(line.productId) ?: error("Product does not exist.")
                val currentCost = database.catalogDao().priceOnDate(line.productId, businessDate.toString())?.costCentavos
                val lotCosts = inventory.availableLots(line.productId).associate { it.lotId to it.unitCostCentavos }
                freshAllocations.forEach { allocation ->
                    val movementId = UUID.randomUUID().toString()
                    database.inventoryDao().insertMovement(
                        InventoryMovementEntity(
                            id = movementId, productId = line.productId, lotId = allocation.lotId,
                            type = InventoryMovementType.SALE, quantity = allocation.quantity.negate().stripTrailingZeros().toPlainString(),
                            unit = product.unit,
                            expiryDate = allocation.lotId?.let { lotId -> database.inventoryDao().lotById(lotId)?.expiryDate },
                            costCentavos = allocation.lotId?.let(lotCosts::get) ?: currentCost,
                            sourceReference = saleId, actorUserId = active.userId,
                            occurredAtUtcMillis = createdAt.toEpochMilli(), reason = "Atomic sale finalization",
                        ),
                    )
                    failureInjector(FinalizationCheckpoint.AFTER_INVENTORY_MOVEMENT)
                    lineAllocations += SaleLineAllocationEntity(
                        id = UUID.randomUUID().toString(), saleLineId = saleLines[index].id,
                        lotId = allocation.lotId, quantity = allocation.quantity.stripTrailingZeros().toPlainString(),
                        inventoryMovementId = movementId,
                    )
                    failureInjector(FinalizationCheckpoint.AFTER_ALLOCATION)
                }
            }
            database.salesDao().insertAllocations(lineAllocations)
            authorizer.recordApplicationAudit(
                sessionId, "SALE_FINALIZED", saleId, "Cashier confirmed checkout", null,
                "transactionId=$transactionId;amountDueCentavos=${totals.amountDue.centavos};lines=${lines.size};settlement=${sale.settlementMethod}",
            )
            failureInjector(FinalizationCheckpoint.AFTER_AUDIT)
            summary(sale, saleLines)
        }
    }

    suspend fun recentSales(sessionId: String, limit: Int = 20): List<SaleSummary> {
        require(limit in 1..100)
        val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
        active.profile.requirePermission(Permission.SALES_VIEW)
        return database.salesDao().recentSales(limit).map { summary(it) }
    }

    private suspend fun calculateLines(draft: CheckoutDraft, date: LocalDate, rounding: RoundingRule): List<CheckoutLinePreview> {
        validateDraft(draft)
        return draft.lines.map { line ->
            val product = database.catalogDao().productById(line.productId) ?: error("Product no longer exists.")
            require(product.active) { "${product.name} is inactive and cannot be sold." }
            val dateText = date.toString()
            val price = database.catalogDao().priceOnDate(product.id, dateText) ?: error("${product.name} has no effective selling price for today.")
            val tax = database.catalogDao().taxOnDate(product.id, dateText) ?: error("${product.name} has no effective tax classification for today.")
            val benefit = database.catalogDao().benefitOnDate(product.id, dateText) ?: error("${product.name} has no effective benefit classification for today.")
            if (line.qualifiedForBenefit) {
                require(draft.customerBenefit != null && benefit.eligibility == BenefitEligibility.SC_PWD_20) {
                    "Only a product explicitly marked SC/PWD-eligible may receive the selected benefit."
                }
            }
            val selected = draft.customerBenefit
            val calculation = FinancialCalculationEngine.calculateLine(
                LineCalculationInput(
                    sku = product.sku,
                    unitPrice = Money(price.sellingCentavos),
                    priceEffectiveFrom = LocalDate.parse(price.effectiveFrom),
                    priceEffectiveTo = price.effectiveTo?.let(LocalDate::parse),
                    quantity = line.quantity,
                    taxClass = tax.taxClass,
                    taxSource = tax.source,
                    taxEffectiveFrom = LocalDate.parse(tax.effectiveFrom),
                    taxEffectiveTo = tax.effectiveTo?.let(LocalDate::parse),
                    benefitEligibility = benefit.eligibility,
                    benefitEffectiveFrom = LocalDate.parse(benefit.effectiveFrom),
                    benefitEffectiveTo = benefit.effectiveTo?.let(LocalDate::parse),
                    selectedBenefit = selected,
                    qualifiedForSelectedBenefit = line.qualifiedForBenefit,
                    selection = if (line.qualifiedForBenefit) DiscountSelection.STATUTORY_ONLY else DiscountSelection.NONE,
                    calculationDate = date,
                ),
                rules = FinancialRuleSet.PH_VAT_REGISTERED_MVP,
                rounding = rounding,
            )
            CheckoutLinePreview(product.id, product.sku, product.name, product.unit, line.quantity, price.costCentavos, tax.taxClass, benefit.eligibility, calculation)
        }
    }

    private suspend fun nextTransactionId(date: LocalDate): String {
        val dateText = date.toString()
        val dao = database.salesDao()
        val next = Math.addExact(dao.sequenceFor(dateText) ?: 0L, 1L)
        dao.upsertSequence(TransactionSequenceEntity(dateText, next))
        return "MTX-${date.format(ID_DATE_FORMAT)}-${next.toString().padStart(6, '0')}"
    }

    private suspend fun summary(sale: SaleEntity, knownLines: List<SaleLineEntity>? = null): SaleSummary {
        val storedLines = knownLines ?: database.salesDao().linesForSale(sale.id)
        val previews = storedLines.map { stored ->
            val snapshot = CalculationSnapshotCodec.decode(stored.calculationSnapshot)
            CheckoutLinePreview(
                productId = stored.productId, sku = stored.sku, productName = stored.productName,
                unit = stored.unit, quantity = BigDecimal(stored.quantity), unitCostCentavos = stored.unitCostCentavos,
                taxClass = snapshot.taxClass,
                benefitEligibility = snapshot.benefitEligibility, calculation = snapshot,
            )
        }
        return SaleSummary(
            saleId = sale.id, humanTransactionId = sale.humanTransactionId,
            createdAtUtcMillis = sale.createdAtUtcMillis, businessDateManila = sale.businessDateManila,
            cashierDisplayName = sale.cashierDisplayName, customerBenefit = sale.customerBenefit,
            maskedBenefitIdNumber = sale.benefitIdLastFour?.let { "••••${it}" },
            settlementMethod = sale.settlementMethod, qrReference = sale.qrReference,
            customerShowedQrSuccess = sale.customerShowedQrSuccess, amountDue = Money(sale.amountDueCentavos),
            lines = previews,
        )
    }

    private fun CheckoutLinePreview.toSaleLine(saleId: String, lineNumber: Int): SaleLineEntity {
        val amount = calculation.amounts
        val exemptSales = when (calculation.taxResult) {
            TaxResult.VAT_EXEMPT_CATALOG -> amount.gross
            TaxResult.VAT_EXEMPT_SC, TaxResult.VAT_EXEMPT_PWD -> amount.vatExclusiveBase
            else -> Money.ZERO
        }
        val vatableSales = if (calculation.taxResult == TaxResult.VATABLE) amount.vatExclusiveBase else Money.ZERO
        val zeroRated = if (calculation.taxResult == TaxResult.ZERO_RATED) amount.gross else Money.ZERO
        return SaleLineEntity(
            id = UUID.randomUUID().toString(), saleId = saleId, lineNumber = lineNumber, productId = productId,
            sku = sku, productName = productName, unit = unit, quantity = quantity.stripTrailingZeros().toPlainString(),
            unitPriceCentavos = calculation.unitPrice.centavos, unitCostCentavos = unitCostCentavos, taxResult = calculation.taxResult,
            grossCentavos = amount.gross.centavos, vatableSalesCentavos = vatableSales.centavos,
            vatCentavos = amount.regularVat.centavos, vatExemptSalesCentavos = exemptSales.centavos,
            zeroRatedSalesCentavos = zeroRated.centavos, vatExemptionAdjustmentCentavos = amount.vatExemptionAdjustment.centavos,
            statutoryDiscountCentavos = amount.statutoryDiscount.centavos,
            promotionalDiscountCentavos = amount.promotionalDiscount.centavos, amountDueCentavos = amount.amountDue.centavos,
            calculationSnapshot = CalculationSnapshotCodec.encode(calculation),
        )
    }

    private fun validateDraft(draft: CheckoutDraft) {
        require(runCatching { UUID.fromString(draft.idempotencyKey) }.isSuccess) { "Checkout attempt ID is invalid." }
        require(draft.lines.isNotEmpty() && draft.lines.size <= MAX_LINES) { "Checkout must contain 1 to $MAX_LINES sale lines." }
        draft.lines.forEach { line ->
            require(line.productId.isNotBlank())
            require(line.quantity > BigDecimal.ZERO && line.quantity.scale() <= 4) { "Quantity must be positive with no more than four decimal places." }
        }
        when (draft.customerBenefit) {
            null -> {
                require(draft.lines.none { it.qualifiedForBenefit }) { "A benefit must be selected before qualifying sale lines." }
                require(draft.customerName == null && draft.benefitIdType == null && draft.benefitIdNumber == null && !draft.physicalIdChecked) {
                    "Customer benefit details are only stored for an SC/PWD checkout."
                }
            }
            CustomerBenefit.SENIOR_CITIZEN, CustomerBenefit.PERSON_WITH_DISABILITY -> {
                require(draft.lines.any { it.qualifiedForBenefit }) { "Select at least one eligible line for the SC/PWD benefit." }
                val name = draft.customerName?.trim().orEmpty()
                val idNumber = draft.benefitIdNumber?.trim().orEmpty()
                require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl)) { "Customer name is required." }
                require(idNumber.isNotBlank() && idNumber.length <= 100 && idNumber.none(Char::isISOControl)) { "Benefit ID number is required." }
                require(draft.physicalIdChecked) { "Confirm that the physical ID was checked." }
                val expectedType = if (draft.customerBenefit == CustomerBenefit.SENIOR_CITIZEN) BenefitIdType.SENIOR_CITIZEN_ID else BenefitIdType.PWD_ID
                require(draft.benefitIdType == expectedType) { "The benefit ID type must match the selected customer benefit." }
            }
        }
        if (draft.settlementMethod == SettlementMethod.CASH) {
            require(draft.qrReference.isNullOrBlank() && !draft.customerShowedQrSuccess) { "QR details apply only to QR settlement declarations." }
        } else {
            val reference = draft.qrReference?.trim()
            require(reference == null || reference.length <= 128 && reference.none(Char::isISOControl)) { "QR reference must be at most 128 printable characters." }
        }
    }

    private data class SaleTotals(
        val gross: Money, val vatableSales: Money, val vat: Money, val vatExemptSales: Money,
        val zeroRatedSales: Money, val vatAdjustment: Money, val statutoryDiscount: Money,
        val promotionalDiscount: Money, val amountDue: Money,
    )

    private fun totals(lines: List<CalculationSnapshot>): SaleTotals {
        fun sum(selector: (CalculationSnapshot) -> Money) = lines.fold(Money.ZERO) { total, line -> total + selector(line) }
        return SaleTotals(
            gross = sum { it.amounts.gross },
            vatableSales = sum { if (it.taxResult == TaxResult.VATABLE) it.amounts.vatExclusiveBase else Money.ZERO },
            vat = sum { it.amounts.regularVat },
            vatExemptSales = sum {
                when (it.taxResult) {
                    TaxResult.VAT_EXEMPT_CATALOG -> it.amounts.gross
                    TaxResult.VAT_EXEMPT_SC, TaxResult.VAT_EXEMPT_PWD -> it.amounts.vatExclusiveBase
                    else -> Money.ZERO
                }
            },
            zeroRatedSales = sum { if (it.taxResult == TaxResult.ZERO_RATED) it.amounts.gross else Money.ZERO },
            vatAdjustment = sum { it.amounts.vatExemptionAdjustment },
            statutoryDiscount = sum { it.amounts.statutoryDiscount },
            promotionalDiscount = sum { it.amounts.promotionalDiscount },
            amountDue = sum { it.amounts.amountDue },
        )
    }

    private fun RoundingRuleApprovalEntity.toDomain() = RoundingRule(
        id = id, version = version, mode = RoundingMode.valueOf(mode), approvedBy = approvedByUserId,
        approvedAt = Instant.ofEpochMilli(approvedAtUtcMillis),
    )

    companion object {
        private const val MAX_LINES = 100
        private val STORE_ZONE = ZoneId.of("Asia/Manila")
        private val ID_DATE_FORMAT = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    }
}
