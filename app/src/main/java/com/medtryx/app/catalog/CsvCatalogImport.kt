package com.medtryx.app.catalog

import androidx.room.withTransaction
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.ProtectedActionAuthorizer
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ImportRowPreview(val rowNumber: Int, val draft: ProductDraft?, val errors: List<ValidationError>)
data class CatalogImportPreview(val checksum: String, val rows: List<ImportRowPreview>) { val validRows get() = rows.filter { it.errors.isEmpty() && it.draft != null } }

/** UTF-8 CSV catalog importer. It never changes input values during validation. */
class CsvCatalogImporter(private val db: MedtryxDatabase, private val catalog: CatalogService, private val authorizer: ProtectedActionAuthorizer, private val clock: () -> Long = System::currentTimeMillis, private val afterProductWrite: suspend () -> Unit = {}) {
 fun preview(csv: String): CatalogImportPreview {
  val records = try { parse(csv) } catch (error: IllegalArgumentException) { return CatalogImportPreview(catalog.checksum(csv), listOf(ImportRowPreview(1,null,listOf(ValidationError("csv",error.message ?: "Malformed CSV"))))) }; if (records.isEmpty()) return CatalogImportPreview(catalog.checksum(csv), emptyList())
  val headers = records.first().map { it.trim().lowercase() }; val required=setOf("sku","name","unit","selling_price","tax_class","tax_source","tax_valid_from","benefit_eligibility","prescription_class","reorder_level","requires_lot_expiry")
  val missing=required-headers.toSet(); return CatalogImportPreview(catalog.checksum(csv), records.drop(1).mapIndexed { i,row ->
   val errors=mutableListOf<ValidationError>(); if(missing.isNotEmpty()) errors+=ValidationError("header","Missing: ${missing.sorted().joinToString()}")
   fun v(key:String)=headers.indexOf(key).takeIf { it>=0 }?.let { row.getOrElse(it){""} } ?: ""
   val draft=runCatching { ProductDraft(v("sku"),v("name"),v("generic_name").ifBlank{null},v("brand").ifBlank{null},v("strength").ifBlank{null},v("dosage_form").ifBlank{null},v("unit"),v("pack_size").ifBlank{null}?.let(::BigDecimal),BigDecimal(v("selling_price")),v("unit_cost").ifBlank{null}?.let(::BigDecimal),TaxClass.valueOf(v("tax_class")),v("tax_source"),LocalDate.parse(v("tax_valid_from")),v("tax_valid_to").ifBlank{null}?.let(LocalDate::parse),BenefitEligibility.valueOf(v("benefit_eligibility")),PrescriptionClass.valueOf(v("prescription_class")),BigDecimal(v("reorder_level")),v("requires_lot_expiry").equals("true",true),v("barcodes").split("|").filter(String::isNotBlank).toSet(), v("opening_quantity").ifBlank{null}?.let { listOf(OpeningLotDraft(v("lot_number"),v("expiry_date").ifBlank{null}?.let(LocalDate::parse),BigDecimal(it),v("supplier_reference").ifBlank{null})) } ?: emptyList()) }.getOrElse { errors+=ValidationError("row",it.message?:"Malformed value"); null }
   if(draft!=null) errors+=ProductValidator.validate(draft); ImportRowPreview(i+2,draft,errors)
  }.let { rows ->
   val skus=rows.filter{it.draft!=null}.groupBy{it.draft!!.sku.trim().uppercase()}.filterValues{it.size>1}
   val barcodes=rows.flatMap{r->r.draft?.barcodes?.map{it to r}?:emptyList()}.groupBy{it.first}.filterValues{it.size>1}
   rows.map { row ->
    val duplicateErrors=mutableListOf<ValidationError>()
    if(skus.containsKey(row.draft?.sku?.trim()?.uppercase())) duplicateErrors+=ValidationError("sku","Duplicate SKU in import.")
    row.draft?.barcodes.orEmpty().filter{barcodes.containsKey(it)}.forEach { duplicateErrors+=ValidationError("barcodes","Duplicate barcode in import: $it") }
    row.copy(errors=row.errors + duplicateErrors)
    }
   })
 }
 suspend fun commit(sessionId:String, preview:CatalogImportPreview, reason:String, reviewedSubset:Set<Int>?=null): List<String> {
  val actor=authorizer.require(sessionId,Permission.PRODUCT_MANAGE,"CATALOG_IMPORT",preview.checksum,reason)
  val selected= if(reviewedSubset==null) { require(preview.rows.all{it.errors.isEmpty()}){"Import has validation errors."}; preview.validRows } else { require(reviewedSubset.isNotEmpty()){ "Reviewed subset is required."}; preview.validRows.filter{it.rowNumber in reviewedSubset} }
  require(selected.isNotEmpty()){ "No valid rows selected." }
  return db.withTransaction { val ids=selected.map { catalog.persist(it.draft!!,actor.userId,reason).also { afterProductWrite() } }; val manifest=ImportManifestEntity(UUID.randomUUID().toString(),preview.checksum,actor.userId,clock(),ids.size,preview.rows.size-ids.size,reviewedSubset!=null); db.catalogDao().insertManifest(manifest); db.catalogDao().insertResults(preview.rows.map { ImportRowResultEntity(UUID.randomUUID().toString(),manifest.id,it.rowNumber,it in selected,it.errors.joinToString("; "){e->"${e.field}: ${e.message}"}.ifBlank{null},if(it in selected) ids[selected.indexOf(it)] else null) }); ids }
 }
 private fun parse(text:String):List<List<String>> { val out=mutableListOf<MutableList<String>>(); var row=mutableListOf<String>(); val cell=StringBuilder(); var q=false; var i=0; while(i<text.length){ val c=text[i]; if(c=='"'){ if(q&&i+1<text.length&&text[i+1]=='"'){cell.append('"');i++}else q=!q } else if(c==','&&!q){row+=cell.toString();cell.clear()} else if((c=='\n'||c=='\r')&&!q){if(c=='\r'&&i+1<text.length&&text[i+1]=='\n')i++;row+=cell.toString();cell.clear();out+=row;row=mutableListOf()} else cell.append(c); i++ }; if(q) throw IllegalArgumentException("Unclosed quoted CSV field."); if(cell.isNotEmpty()||row.isNotEmpty()){row+=cell.toString();out+=row}; return out }
}
