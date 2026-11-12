package net.spritzlive.securemessenger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.spritzlive.securemessenger.ui.HermesViewModel

/** Set up your own Hermes agent: scan the setup code your gateway shows (or paste
 * it). The code carries the gateway's address, certificate pins and a single-use
 * pairing code; see HermesSetupCode and HermesViewModel.submitSetupCode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesPairingScreen(
    onPaired: () -> Unit,
    onBack: (() -> Unit)?,
    vm: HermesViewModel,
) {
    // System back would land on HermesScreen, which immediately re-opens this
    // screen while unpaired — route it through onBack (which skips past it) instead.
    if (onBack != null) BackHandler(onBack = onBack)

    // Not rememberSaveable: the pasted code contains a live pairing code, so it
    // shouldn't be written into saved instance state.
    var pasted by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val error by vm.pairingError.collectAsState()
    val needsPairing by vm.needsPairing.collectAsState()

    LaunchedEffect(needsPairing) {
        if (!needsPairing) onPaired()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        scanError = null
        vm.submitSetupCode(contents)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        } else {
            scanError = "Camera permission is needed to scan the setup code. You can paste it instead."
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up AI agent") }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect your own Hermes agent. Your Hermes gateway gives you a one-time setup code.")
            Button(
                onClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan setup code") }

            Text("Or paste it:")
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("hermes-setup:1:…") },
                minLines = 3,
            )
            Button(
                enabled = pasted.isNotBlank(),
                onClick = { vm.submitSetupCode(pasted) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }

            (scanError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (onBack != null) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
