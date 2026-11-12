package net.spritzlive.securemessenger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.spritzlive.securemessenger.ui.HermesViewModel

/** Enter the one-time pairing code the gateway operator showed you; exchanged over
 * HTTPS (HermesPairingClient) for a permanent device token, stored Keystore-backed
 * (HermesAuthStore) — see HermesViewModel.submitPairingCode. */
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

    var code by rememberSaveable { mutableStateOf("") }
    val error by vm.pairingError.collectAsState()
    val needsPairing by vm.needsPairing.collectAsState()

    LaunchedEffect(needsPairing) {
        if (!needsPairing) onPaired()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pair with Hermes") }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Enter the pairing code shown by the Hermes gateway operator.")
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { androidx.compose.material3.Text("Pairing code") },
            )
            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { vm.submitPairingCode(code.trim()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pair")
            }
            if (onBack != null) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
