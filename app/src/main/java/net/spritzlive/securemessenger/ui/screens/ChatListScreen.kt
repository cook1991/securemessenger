package net.spritzlive.securemessenger.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.spritzlive.securemessenger.ui.ChatListViewModel
import net.spritzlive.securemessenger.ui.ChatRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home screen: one row per conversation (contacts, plus Hermes once it's set up).
 * Rows show the name, when the last message was, and "New" — never a preview of
 * what was said. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    vm: ChatListViewModel,
    onOpenContact: (peerId: String) -> Unit,
    onOpenHermes: () -> Unit,
    onAddContact: () -> Unit,
    onSetUpHermes: () -> Unit,
    onTextSize: () -> Unit,
) {
    val rows by vm.rows.collectAsState()
    val hermesPaired by vm.hermesPaired.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Bound only while this screen is composed, never for the ViewModel's lifetime:
    // the pairing screen stops the relay service, and a binding left behind on this
    // back-stack entry would keep it (and its socket) alive underneath.
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!hermesPaired) {
                                DropdownMenuItem(
                                    text = { Text("Set up AI agent") },
                                    onClick = { menuOpen = false; onSetUpHermes() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Text size") },
                                onClick = { menuOpen = false; onTextSize() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "No conversations yet. Add a contact to start one.",
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(rows, key = { it.peerId ?: "hermes" }) { row ->
                    ChatRowItem(row, onClick = {
                        val peerId = row.peerId
                        if (peerId == null) onOpenHermes() else onOpenContact(peerId)
                    })
                    HorizontalDivider()
                }
            }
            Button(
                onClick = onAddContact,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Add contact") }
        }
    }
}

@Composable
private fun ChatRowItem(row: ChatRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (row.hasUnread) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            row.lastMessageAt?.let { Text(formatWhen(it), style = MaterialTheme.typography.labelMedium) }
            if (row.hasUnread) {
                Text("New", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatWhen(millis: Long): String {
    val pattern = if (DateUtils.isToday(millis)) "h:mm a" else "MMM d"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
