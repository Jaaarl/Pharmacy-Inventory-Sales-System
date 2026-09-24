package com.medtryx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.medtryx.app.auth.AuthenticatedSession
import com.medtryx.app.auth.AuthenticationService
import com.medtryx.app.auth.LoginResult
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.PasswordHasher
import com.medtryx.app.auth.ProtectedActionAuthorizer
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.PermissionPolicy
import com.medtryx.app.catalog.*
import java.math.BigDecimal
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var launchCount by mutableIntStateOf(0)
    private var authState by mutableStateOf<AuthState>(AuthState.Loading)
    private var authMessage by mutableStateOf<String?>(null)
    private lateinit var authenticationService: AuthenticationService
    private lateinit var catalogService: CatalogService
    private lateinit var catalogImporter: CsvCatalogImporter
    private var catalogImportPreview by mutableStateOf<CatalogImportPreview?>(null)
    private var catalogProducts by mutableStateOf<List<ProductEntity>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = Room.databaseBuilder(
            applicationContext,
            DeviceCompatibilityDatabase::class.java,
            "medtryx-compatibility.db",
        ).build()
        val authDatabase = Room.databaseBuilder(
            applicationContext,
            MedtryxDatabase::class.java,
            "medtryx.db",
        ).addMigrations(MedtryxDatabase.MIGRATION_1_2, MedtryxDatabase.MIGRATION_2_3, MedtryxDatabase.MIGRATION_3_4).build()
        val preferences = getSharedPreferences("medtryx_device", MODE_PRIVATE)
        val deviceId = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
            preferences.edit().putString("id", it).apply()
        }
        authenticationService = AuthenticationService(authDatabase, PasswordHasher(), deviceId)
        catalogService = CatalogService(authDatabase, ProtectedActionAuthorizer(authenticationService), authenticationService)
        catalogImporter = CsvCatalogImporter(authDatabase, catalogService, ProtectedActionAuthorizer(authenticationService))

        lifecycleScope.launch {
            launchCount = withContext(Dispatchers.IO) {
                val dao = database.compatibilityProbeDao()
                val nextCount = (dao.read()?.launchCount ?: 0) + 1
                dao.save(CompatibilityProbe(launchCount = nextCount))
                nextCount
            }
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { authenticationService.revokeDeviceSessions("APP_RESTART_SESSION_INVALIDATION") }
            authState = withContext(Dispatchers.IO) {
                if (authDatabase.authDao().userCount() == 0) AuthState.OwnerSetup else AuthState.SignIn
            }
            catalogProducts = withContext(Dispatchers.IO) { catalogService.products() }
        }

        setContent {
            MedtryxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MedtryxApp(
                        authState = authState,
                        message = authMessage,
                        onCreateOwner = ::createOwner,
                        onLogin = ::login,
                        onLogout = ::logout,
                        onLock = ::lock,
                        onCreateProduct = ::createProductDraft,
                        products = catalogProducts,
                        onDeactivateProduct = ::deactivateProduct,
                        importPreview = catalogImportPreview,
                        onPreviewImport = ::previewCatalogImport,
                        onCommitImport = ::commitCatalogImport,
                    )
                }
            }
        }
    }

    private fun createOwner(username: String, displayName: String, pin: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { authenticationService.bootstrapOwner(username, displayName, pin.toCharArray()) } }
            .onSuccess { authState = AuthState.SignedIn(it); lifecycleScope.launch { catalogProducts = withContext(Dispatchers.IO) { catalogService.products() } } }
            .onFailure { authMessage = it.message ?: "Unable to create the owner account." }
    }

    private fun login(username: String, pin: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { authenticationService.login(username, pin.toCharArray()) } }
            .onSuccess { result -> when (result) {
                is LoginResult.Success -> { authState = AuthState.SignedIn(result.session); lifecycleScope.launch { catalogProducts = withContext(Dispatchers.IO) { catalogService.products() } } }
                LoginResult.InvalidCredentials -> authMessage = "Invalid username or PIN."
                LoginResult.Throttled -> authMessage = "Too many attempts. Please wait and try again."
                LoginResult.DisabledAccount -> authMessage = "This account is disabled."
            } }
            .onFailure { authMessage = it.message ?: "Unable to sign in." }
    }

    private fun logout() = lifecycleScope.launch {
        val current = authState as? AuthState.SignedIn ?: return@launch
        withContext(Dispatchers.IO) { authenticationService.logout(current.session.sessionId) }
        authState = AuthState.SignIn
    }

    private fun lock() = lifecycleScope.launch {
        val current = authState as? AuthState.SignedIn ?: return@launch
        withContext(Dispatchers.IO) { authenticationService.lockForInactivity(current.session.sessionId) }
        authState = AuthState.SignIn
    }
    private fun createProduct(session: AuthenticatedSession, sku:String, name:String, price:String, reason:String) = lifecycleScope.launch {
        authMessage=null
        runCatching { withContext(Dispatchers.IO) { catalogService.createProduct(session.sessionId, ProductDraft(sku,name,unit="piece",sellingPrice=BigDecimal(price),taxClass=TaxClass.VATABLE,taxSource="Pending approved catalog source",taxValidFrom=LocalDate.now(),benefitEligibility=BenefitEligibility.NONE,prescriptionClass=PrescriptionClass.OTHER,reorderLevel=BigDecimal.ZERO,requiresLotExpiry=false),reason) } }.onSuccess { authMessage="Product saved." }.onFailure { authMessage=it.message?:"Unable to save product." }
    }
    private fun createProductDraft(session: AuthenticatedSession, draft: ProductDraft, reason: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogService.createProduct(session.sessionId, draft, reason); catalogService.products() } }
            .onSuccess { catalogProducts = it; authMessage = "Product and opening stock saved." }
            .onFailure { authMessage = it.message ?: "Unable to save product." }
    }
    private fun deactivateProduct(session: AuthenticatedSession, productId: String, reason: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogService.deactivateProduct(session.sessionId, productId, reason); catalogService.products() } }
            .onSuccess { catalogProducts = it; authMessage = "Product inactivated." }
            .onFailure { authMessage = it.message ?: "Unable to inactivate product." }
    }
    private fun previewCatalogImport(session: AuthenticatedSession, csv: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogImporter.validateAgainstCatalog(catalogImporter.preview(csv)) } }
            .onSuccess { catalogImportPreview = it; authMessage = "Import preview ready: ${it.validRows.size} valid, ${it.rejectedRows.size} invalid." }
            .onFailure { authMessage = it.message ?: "Unable to read the catalog CSV." }
    }
    private fun commitCatalogImport(session: AuthenticatedSession, preview: CatalogImportPreview, reason: String, validRowsOnly: Boolean) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogImporter.commit(session.sessionId, preview, reason, if (validRowsOnly) preview.validRows.map { it.rowNumber }.toSet() else null) } }
            .onSuccess { catalogImportPreview = null; authMessage = "Imported ${it.size} product(s)." }
            .onFailure { authMessage = it.message ?: "Catalog import was not committed." }
    }
}

@Composable
private fun MedtryxApp(authState: AuthState, message: String?, onCreateOwner: (String, String, String) -> Unit, onLogin: (String, String) -> Unit, onLogout: () -> Unit, onLock: () -> Unit, onCreateProduct:(AuthenticatedSession,ProductDraft,String)->Unit, products: List<ProductEntity>, onDeactivateProduct:(AuthenticatedSession,String,String)->Unit, importPreview: CatalogImportPreview?, onPreviewImport: (AuthenticatedSession, String) -> Unit, onCommitImport: (AuthenticatedSession, CatalogImportPreview, String, Boolean) -> Unit) {
    when (authState) {
        AuthState.Loading -> LoadingScreen()
        AuthState.OwnerSetup -> OwnerSetupScreen(message, onCreateOwner)
        AuthState.SignIn -> LoginScreen(message, onLogin)
        is AuthState.SignedIn -> SignedInScreen(authState.session, message, onLogout, onLock, onCreateProduct, products, onDeactivateProduct, importPreview, onPreviewImport, onCommitImport)
    }
}

@Composable
private fun OwnerSetupScreen(message: String?, onCreate: (String, String, String) -> Unit) {
    var username by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var displayName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    AuthForm("Create the first owner account", message, listOf("Username" to username, "Display name" to displayName, "PIN (4+ characters)" to pin), { values -> onCreate(values[0], values[1], values[2]) }) { index, value ->
        when (index) { 0 -> username = value; 1 -> displayName = value; else -> pin = value }
    }
}

@Composable
private fun LoginScreen(message: String?, onLogin: (String, String) -> Unit) {
    var username by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    AuthForm("Sign in to Medtryx", message, listOf("Username" to username, "PIN" to pin), { values -> onLogin(values[0], values[1]) }) { index, value ->
        if (index == 0) username = value else pin = value
    }
}

@Composable
private fun LoadingScreen() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Loading Medtryx…")
    }
}

@Composable
private fun AuthForm(title: String, message: String?, fields: List<Pair<String, String>>, submit: (List<String>) -> Unit, update: (Int, String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Medtryx")
        Text(text = title)
        fields.forEachIndexed { index, field ->
            OutlinedTextField(value = field.second, onValueChange = { update(index, it) }, label = { Text(field.first) }, visualTransformation = if (field.first.startsWith("PIN")) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        }
        if (message != null) Text(message)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit(fields.map { it.second }) }) { Text("Continue") }
    }
}

@Composable
private fun SignedInScreen(session: AuthenticatedSession, message:String?, onLogout: () -> Unit, onLock: () -> Unit, onCreateProduct:(AuthenticatedSession,ProductDraft,String)->Unit, products: List<ProductEntity>, onDeactivateProduct:(AuthenticatedSession,String,String)->Unit, importPreview: CatalogImportPreview?, onPreviewImport: (AuthenticatedSession, String) -> Unit, onCommitImport: (AuthenticatedSession, CatalogImportPreview, String, Boolean) -> Unit) {
    var showCatalog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if(showCatalog) { CatalogEditorScreen(session, products, message, { draft, reason -> onCreateProduct(session, draft, reason) }, { id, reason -> onDeactivateProduct(session, id, reason) }, { showCatalog = false }); return }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Signed in")
        Text("Role: ${session.profile.role}")
        Text("F01 authentication foundation is active.")
        if (PermissionPolicy.allows(session.profile, Permission.PRODUCT_MANAGE)) Button(onClick={showCatalog=true}) { Text("Add product") }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLock) { Text("Lock") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onLogout) { Text("Sign out") }
    }
}

@Composable private fun CatalogEntryScreen(session:AuthenticatedSession,message:String?,back:()->Unit,save:(AuthenticatedSession,String,String,String,String)->Unit,importPreview:CatalogImportPreview?,previewImport:(AuthenticatedSession,String)->Unit,commitImport:(AuthenticatedSession,CatalogImportPreview,String,Boolean)->Unit) {
 var sku by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }; var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }; var price by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }; var reason by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
 val context = LocalContext.current
 val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { selected -> runCatching { context.contentResolver.openInputStream(selected)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Unable to open CSV") }.onSuccess { previewImport(session, it) } } }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Text("Catalog — manual entry"); Text("Tax and eligibility are explicit per SKU; this starter form creates a VATable, no-benefit retail SKU."); OutlinedTextField(sku,{sku=it},label={Text("SKU")}); OutlinedTextField(name,{name=it},label={Text("Product name")}); OutlinedTextField(price,{price=it},label={Text("VAT-inclusive price")}); OutlinedTextField(reason,{reason=it},label={Text("Reason (required for audit)")}); if(message!=null)Text(message); Button(enabled=sku.isNotBlank() && name.isNotBlank() && price.isNotBlank() && reason.isNotBlank(),onClick={save(session,sku,name,price,reason)}){Text("Save product")}; Spacer(Modifier.height(12.dp)); Button(onClick={picker.launch(arrayOf("text/csv","text/comma-separated-values"))}){Text("Choose catalog CSV")}; importPreview?.let { preview -> Text("CSV preview: ${preview.validRows.size} valid, ${preview.rejectedRows.size} invalid"); preview.rejectedRows.take(5).forEach { row -> Text("Row ${row.rowNumber}: ${row.errors.joinToString { error -> "${error.field}: ${error.message}" }}") }; if(preview.rejectedRows.isEmpty()) Button(enabled=reason.isNotBlank(),onClick={commitImport(session,preview,reason,false)}){Text("Confirm full import")}; if(preview.validRows.isNotEmpty() && preview.rejectedRows.isNotEmpty()) Button(enabled=reason.isNotBlank(),onClick={commitImport(session,preview,reason,true)}){Text("Confirm reviewed valid rows only")} }; Button(onClick=back){Text("Back")} }
}

private sealed interface AuthState {
    data object Loading : AuthState
    data object OwnerSetup : AuthState
    data object SignIn : AuthState
    data class SignedIn(val session: AuthenticatedSession) : AuthState
}

@Composable
private fun MedtryxTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Preview(showBackground = true, widthDp = 840, heightDp = 540)
@Composable
private fun CompatibilityScreenPreview() {
    MedtryxTheme { LoginScreen(message = null) { _, _ -> } }
}
