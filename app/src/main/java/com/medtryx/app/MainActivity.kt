package com.medtryx.app

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var launchCount by mutableIntStateOf(0)
    private var authState by mutableStateOf<AuthState>(AuthState.Loading)
    private var authMessage by mutableStateOf<String?>(null)
    private lateinit var authenticationService: AuthenticationService
    private lateinit var catalogService: CatalogService
    private lateinit var catalogImporter: CsvCatalogImporter
    private lateinit var inventoryService: InventoryService
    private var catalogImportPreview by mutableStateOf<CatalogImportPreview?>(null)
    private var catalogImportCsv by mutableStateOf<String?>(null)
    private var catalogProducts by mutableStateOf<List<CatalogProductSnapshot>>(emptyList())
    private var inventoryProducts by mutableStateOf<List<InventoryProductStatus>>(emptyList())
    private var inventoryAlerts by mutableStateOf<List<InventoryAlert>>(emptyList())
    private var nearExpiryDays by mutableStateOf("")
    private var showInventory by mutableStateOf(false)

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
        ).addMigrations(MedtryxDatabase.MIGRATION_1_2, MedtryxDatabase.MIGRATION_2_3, MedtryxDatabase.MIGRATION_3_4, MedtryxDatabase.MIGRATION_4_5).build()
        val preferences = getSharedPreferences("medtryx_device", MODE_PRIVATE)
        val deviceId = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
            preferences.edit().putString("id", it).apply()
        }
        authenticationService = AuthenticationService(authDatabase, PasswordHasher(), deviceId)
        catalogService = CatalogService(authDatabase, ProtectedActionAuthorizer(authenticationService), authenticationService)
        catalogImporter = CsvCatalogImporter(authDatabase, catalogService, ProtectedActionAuthorizer(authenticationService))
        inventoryService = InventoryService(authDatabase, ProtectedActionAuthorizer(authenticationService))

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
            catalogProducts = withContext(Dispatchers.IO) { catalogService.productSnapshots() }
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
                        onSaveProduct = ::saveCatalogProduct,
                        products = catalogProducts,
                        onDeactivateProduct = ::deactivateProduct,
                        importPreview = catalogImportPreview,
                        importCsv = catalogImportCsv,
                        onChooseCsv = ::loadCatalogCsv,
                        onCommitImport = ::commitCatalogImport,
                        showInventory = showInventory,
                        inventoryProducts = inventoryProducts,
                        inventoryAlerts = inventoryAlerts,
                        nearExpiryDays = nearExpiryDays,
                        onNearExpiryDaysChange = { nearExpiryDays = it.filter(Char::isDigit).take(3) },
                        onOpenInventory = ::openInventory,
                        onRefreshInventory = ::refreshInventory,
                        onReceiveStock = ::receiveStock,
                        onAdjustStock = ::adjustStock,
                        onDisposeExpired = ::disposeExpiredStock,
                        onCloseInventory = { showInventory = false },
                    )
                }
            }
        }
    }

    private fun createOwner(username: String, displayName: String, pin: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { authenticationService.bootstrapOwner(username, displayName, pin.toCharArray()) } }
            .onSuccess { authState = AuthState.SignedIn(it); lifecycleScope.launch { catalogProducts = withContext(Dispatchers.IO) { catalogService.productSnapshots() } } }
            .onFailure { authMessage = it.message ?: "Unable to create the owner account." }
    }

    private fun login(username: String, pin: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { authenticationService.login(username, pin.toCharArray()) } }
            .onSuccess { result -> when (result) {
                is LoginResult.Success -> { authState = AuthState.SignedIn(result.session); lifecycleScope.launch { catalogProducts = withContext(Dispatchers.IO) { catalogService.productSnapshots() } } }
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
    private fun saveCatalogProduct(session: AuthenticatedSession, productId: String?, expectedSnapshot: CatalogProductSnapshot?, draft: ProductDraft, effectiveFrom: LocalDate, reason: String, onSuccess: () -> Unit) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) {
            if (productId == null) catalogService.createProduct(session.sessionId, draft, reason)
            else catalogService.updateProduct(session.sessionId, productId, requireNotNull(expectedSnapshot), draft, effectiveFrom, reason)
            catalogService.productSnapshots()
        } }.onSuccess { catalogProducts = it; authMessage = if (productId == null) "Product and opening stock saved." else "Catalog changes saved."; onSuccess() }
            .onFailure { authMessage = it.message ?: "Unable to save catalog changes." }
    }
    private fun deactivateProduct(session: AuthenticatedSession, productId: String, reason: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogService.deactivateProduct(session.sessionId, productId, reason); catalogService.productSnapshots() } }
            .onSuccess { catalogProducts = it; authMessage = "Product inactivated." }
            .onFailure { authMessage = it.message ?: "Unable to inactivate product." }
    }
    private fun loadCatalogCsv(session: AuthenticatedSession, uri: Uri) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) {
            val input = contentResolver.openInputStream(uri) ?: error("Unable to open the selected CSV file.")
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8 * 1024); var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= CatalogCsvEncoding.MAX_BYTES) { "CSV file exceeds the 10 MiB import limit." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            CatalogCsvEncoding.decodeUtf8(bytes)
        } }.onSuccess { previewCatalogImport(session, it) }.onFailure { authMessage = it.message ?: "Unable to read the selected CSV file." }
    }
    private fun previewCatalogImport(session: AuthenticatedSession, csv: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogImporter.validateAgainstCatalog(catalogImporter.preview(csv)) } }
            .onSuccess { catalogImportCsv = csv; catalogImportPreview = it; authMessage = "Import preview ready: ${it.validRows.size} valid, ${it.rejectedRows.size} invalid." }
            .onFailure { authMessage = it.message ?: "Unable to read the catalog CSV." }
    }
    private fun commitCatalogImport(session: AuthenticatedSession, csv: String, preview: CatalogImportPreview, reason: String, reviewedRows: Set<Int>?) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { catalogImporter.commit(session.sessionId, csv, preview, reason, reviewedRows); catalogService.productSnapshots() } }
            .onSuccess { catalogProducts = it; catalogImportPreview = null; catalogImportCsv = null; authMessage = "Catalog import committed." }
            .onFailure { authMessage = it.message ?: "Catalog import was not committed." }
    }

    private fun inventoryCutoff(): LocalDate = nearExpiryDays.toIntOrNull()?.takeIf { it > 0 }?.let { LocalDate.now(java.time.ZoneId.of("Asia/Manila")).plusDays(it.toLong()) } ?: LocalDate.now(java.time.ZoneId.of("Asia/Manila"))
    private fun openInventory(session: AuthenticatedSession) { showInventory = true; refreshInventory(session) }
    private fun refreshInventory(session: AuthenticatedSession) = lifecycleScope.launch {
        val cutoff = inventoryCutoff()
        runCatching { withContext(Dispatchers.IO) { inventoryService.status(cutoff) to inventoryService.alerts(cutoff) } }
            .onSuccess { (products, warnings) -> inventoryProducts = products; inventoryAlerts = warnings }
            .onFailure { authMessage = it.message ?: "Unable to load inventory." }
    }
    private fun receiveStock(sessionId: String, draft: StockReceiptDraft, reason: String) = lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { inventoryService.receive(sessionId, draft, reason); Unit } }
            .onSuccess { authMessage = "Stock receipt recorded."; (authState as? AuthState.SignedIn)?.let { refreshInventory(it.session) } }
            .onFailure { authMessage = it.message ?: "Stock receipt was not recorded." }
    }
    private fun adjustStock(sessionId: String, productId: String, lotId: String?, quantity: BigDecimal, reason: String) = lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { inventoryService.adjust(sessionId, productId, lotId, quantity, reason); Unit } }
            .onSuccess { authMessage = "Inventory adjustment recorded."; (authState as? AuthState.SignedIn)?.let { refreshInventory(it.session) } }
            .onFailure { authMessage = it.message ?: "Inventory adjustment was not recorded." }
    }
    private fun disposeExpiredStock(sessionId: String, lotId: String, quantity: BigDecimal, reason: String) = lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { inventoryService.disposeExpired(sessionId, lotId, quantity, reason); Unit } }
            .onSuccess { authMessage = "Expired stock disposal recorded."; (authState as? AuthState.SignedIn)?.let { refreshInventory(it.session) } }
            .onFailure { authMessage = it.message ?: "Expired stock disposal was not recorded." }
    }
}

@Composable
private fun MedtryxApp(authState: AuthState, message: String?, onCreateOwner: (String, String, String) -> Unit, onLogin: (String, String) -> Unit, onLogout: () -> Unit, onLock: () -> Unit, onSaveProduct:(AuthenticatedSession,String?,CatalogProductSnapshot?,ProductDraft,LocalDate,String,()->Unit)->Unit, products: List<CatalogProductSnapshot>, onDeactivateProduct:(AuthenticatedSession,String,String)->Unit, importPreview: CatalogImportPreview?, importCsv: String?, onChooseCsv:(AuthenticatedSession,Uri)->Unit, onCommitImport: (AuthenticatedSession, String, CatalogImportPreview, String, Set<Int>?) -> Unit, showInventory: Boolean, inventoryProducts: List<InventoryProductStatus>, inventoryAlerts: List<InventoryAlert>, nearExpiryDays: String, onNearExpiryDaysChange: (String) -> Unit, onOpenInventory: (AuthenticatedSession) -> Unit, onRefreshInventory: (AuthenticatedSession) -> Unit, onReceiveStock: (String,StockReceiptDraft,String)->Unit, onAdjustStock: (String,String,String?,BigDecimal,String)->Unit, onDisposeExpired: (String,String,BigDecimal,String)->Unit, onCloseInventory: ()->Unit) {
    when (authState) {
        AuthState.Loading -> LoadingScreen()
        AuthState.OwnerSetup -> OwnerSetupScreen(message, onCreateOwner)
        AuthState.SignIn -> LoginScreen(message, onLogin)
        is AuthState.SignedIn -> if (showInventory) InventoryScreen(authState.session, inventoryProducts, inventoryAlerts, message, nearExpiryDays, onNearExpiryDaysChange, { onRefreshInventory(authState.session) }, onReceiveStock, onAdjustStock, onDisposeExpired, onCloseInventory) else SignedInScreen(authState.session, message, onLogout, onLock, onSaveProduct, products, onDeactivateProduct, importPreview, importCsv, onChooseCsv, onCommitImport, onOpenInventory)
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
private fun SignedInScreen(session: AuthenticatedSession, message:String?, onLogout: () -> Unit, onLock: () -> Unit, onSaveProduct:(AuthenticatedSession,String?,CatalogProductSnapshot?,ProductDraft,LocalDate,String,()->Unit)->Unit, products: List<CatalogProductSnapshot>, onDeactivateProduct:(AuthenticatedSession,String,String)->Unit, importPreview: CatalogImportPreview?, importCsv: String?, onChooseCsv:(AuthenticatedSession,Uri)->Unit, onCommitImport: (AuthenticatedSession, String, CatalogImportPreview, String, Set<Int>?) -> Unit, onOpenInventory: (AuthenticatedSession)->Unit) {
    var showCatalog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if(showCatalog) { CatalogEditorScreen(session, products, message, importPreview, importCsv, { id, expected, draft, effectiveFrom, reason, onSuccess -> onSaveProduct(session, id, expected, draft, effectiveFrom, reason, onSuccess) }, { id, reason -> onDeactivateProduct(session, id, reason) }, { uri -> onChooseCsv(session, uri) }, { csv, preview, reason, rows -> onCommitImport(session, csv, preview, reason, rows) }, { showCatalog = false }); return }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Signed in")
        Text("Role: ${session.profile.role}")
        Text("F01 authentication foundation is active.")
        val catalogPermissions = listOf(Permission.PRODUCT_MANAGE, Permission.PRICE_CHANGE, Permission.TAX_CONFIGURATION_CHANGE, Permission.BENEFIT_ELIGIBILITY_CHANGE)
        if (catalogPermissions.any { PermissionPolicy.allows(session.profile, it) }) Button(onClick={showCatalog=true}) { Text("Product catalog") }
        if (PermissionPolicy.allows(session.profile, Permission.INVENTORY_ADJUST) || PermissionPolicy.allows(session.profile, Permission.REPORTS_VIEW)) Button(onClick={onOpenInventory(session)}) { Text("Inventory") }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLock) { Text("Lock") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onLogout) { Text("Sign out") }
    }
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
