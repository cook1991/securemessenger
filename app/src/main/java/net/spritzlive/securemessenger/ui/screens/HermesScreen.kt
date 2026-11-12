package net.spritzlive.securemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.spritzlive.securemessenger.storage.HermesMessage
import net.spritzlive.securemessenger.ui.HermesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hermes's own conversation screen — a separate thread from the primary contact's,
 * not merged into ThreadScreen, since NetworkRepository/ThreadScreen are built
 * around exactly one verified contact (see HermesRepository's doc for why this
 * stays fully parallel instead). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScreen(
    onNavigateToPairing: () -> Unit,
    onBack: () -> Unit,
    vm: HermesViewModel,
) {
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val needsPairing by vm.needsPairing.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(needsPairing) {
        if (needsPairing) onNavigateToPairing()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hermes")
                        Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> HermesBubble(message) }
            }

            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Hermes") },
                )
                IconButton(onClick = {
                    vm.send(input)
                    input = ""
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun HermesBubble(message: HermesMessage) {
    val alignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val timeText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.ts))

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(color = bubbleColor, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 280.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(message.text, color = textColor)
                Text(timeText, color = textColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
