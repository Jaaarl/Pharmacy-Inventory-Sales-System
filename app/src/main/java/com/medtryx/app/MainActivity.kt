package com.medtryx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var launchCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = Room.databaseBuilder(
            applicationContext,
            DeviceCompatibilityDatabase::class.java,
            "medtryx-compatibility.db",
        ).build()

        lifecycleScope.launch {
            launchCount = withContext(Dispatchers.IO) {
                val dao = database.compatibilityProbeDao()
                val nextCount = (dao.read()?.launchCount ?: 0) + 1
                dao.save(CompatibilityProbe(launchCount = nextCount))
                nextCount
            }
        }

        setContent {
            MedtryxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompatibilityScreen(launchCount = launchCount)
                }
            }
        }
    }
}

@Composable
private fun CompatibilityScreen(launchCount: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Medtryx")
        Text(text = "Device compatibility check")
        Text(text = "Saved launch count: $launchCount")
    }
}

@Composable
private fun MedtryxTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Preview(showBackground = true, widthDp = 840, heightDp = 540)
@Composable
private fun CompatibilityScreenPreview() {
    MedtryxTheme { CompatibilityScreen(launchCount = 1) }
}
