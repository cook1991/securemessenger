package net.spritzlive.securemessenger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.net.RelayConnectionService
import net.spritzlive.securemessenger.ui.SecureMessengerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToTextSize: () -> Unit,
    vm: SecureMessengerViewModel = viewModel(),
) {
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val peerDisplayName by vm.peerDisplayName.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var shredTarget by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        vm.bind()
        RelayConnectionService.isThreadVisible = true
        onDispose {
            RelayConnectionService.isThreadVisible = false
            vm.unbind()
        }
    }

    // isThreadVisible also has to track real Activity visibility (Home press, screen
    // off, app-switch) — leaving ThreadScreen composed is NOT the same as the user
    // actually looking at it. ON_STOP fires when the Activity is no longer visible at
    // all; ON_START fires when it's visible again, independent of Compose navigation.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> RelayConnectionService.isThreadVisible = true
                Lifecycle.Event.ON_STOP -> RelayConnectionService.isThreadVisible = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peerDisplayName)
                        Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToPairing) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = "Contact / safety number")
                    }
                    IconButton(onClick = onNavigateToTextSize) {
                        Icon(Icons.Filled.FormatSize, contentDescription = "Text size")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, onLongPress = { if (!message.shredded) shredTarget = message.id })
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
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

    val targetId = shredTarget
    if (targetId != null) {
        AlertDialog(
            onDismissRequest = { shredTarget = null },
            title = { Text("Delete this message?") },
            text = { Text("This destroys the message's encryption key. It cannot be recovered, even by you.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.shred(targetId)
                    shredTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { shredTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: Message, onLongPress: () -> Unit) {
    val alignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = when {
        message.shredded -> MaterialTheme.colorScheme.surfaceVariant
        message.outgoing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.outgoing && !message.shredded) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val timeText = remember(message.date) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.date))
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .height(IntrinsicSize.Min)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    if (message.shredded) "Message deleted" else message.body,
                    color = textColor,
                    textAlign = TextAlign.Start,
                )
                Text(
                    timeText,
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
