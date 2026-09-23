package com.medtryx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

class MainActivity : ComponentActivity() {
    private var launchCount by mutableIntStateOf(0)
    private var authState by mutableStateOf<AuthState>(AuthState.Loading)
    private var authMessage by mutableStateOf<String?>(null)
    private lateinit var authenticationService: AuthenticationService

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
        ).addMigrations(MedtryxDatabase.MIGRATION_1_2, MedtryxDatabase.MIGRATION_2_3).build()
        val preferences = getSharedPreferences("medtryx_device", MODE_PRIVATE)
        val deviceId = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
            preferences.edit().putString("id", it).apply()
        }
        authenticationService = AuthenticationService(authDatabase, PasswordHasher(), deviceId)

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
                    )
                }
            }
        }
    }

    private fun createOwner(username: String, displayName: String, pin: String) = lifecycleScope.launch {
        authMessage = null
        runCatching { withContext(Dispatchers.IO) { authenticationService.bootstrapOwner(username, displayName, pin.toCharArray()) } }
            .onSuccess { authState = AuthState.SignedIn(it) }
            .onFailure { authMessage = it.message ?: "Unable to create the owner account." }
    }

    private fun login(username: String, pin: String) = lifecycleScope.launch {
        authMessage = null
        when (val result = withContext(Dispatchers.IO) { authenticationService.login(username, pin.toCharArray()) }) {
            is LoginResult.Success -> authState = AuthState.SignedIn(result.session)
            LoginResult.InvalidCredentials -> authMessage = "Invalid username or PIN."
            LoginResult.Throttled -> authMessage = "Too many attempts. Please wait and try again."
            LoginResult.DisabledAccount -> authMessage = "This account is disabled."
        }
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
}

@Composable
private fun MedtryxApp(authState: AuthState, message: String?, onCreateOwner: (String, String, String) -> Unit, onLogin: (String, String) -> Unit, onLogout: () -> Unit, onLock: () -> Unit) {
    when (authState) {
        AuthState.Loading -> LoadingScreen()
        AuthState.OwnerSetup -> OwnerSetupScreen(message, onCreateOwner)
        AuthState.SignIn -> LoginScreen(message, onLogin)
        is AuthState.SignedIn -> SignedInScreen(authState.session, onLogout, onLock)
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
        modifier = Modifier.fillMaxSize(),
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
private fun SignedInScreen(session: AuthenticatedSession, onLogout: () -> Unit, onLock: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Signed in")
        Text("Role: ${session.profile.role}")
        Text("F01 authentication foundation is active.")
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
