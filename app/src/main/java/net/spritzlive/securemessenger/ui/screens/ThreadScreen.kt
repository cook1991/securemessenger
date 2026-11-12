package net.spritzlive.securemessenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.data.MessageKind
import net.spritzlive.securemessenger.data.MessageStatus
import net.spritzlive.securemessenger.net.RelayConnectionService
import net.spritzlive.securemessenger.ui.SecureMessengerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToTextSize: () -> Unit,
    onNavigateToHermes: () -> Unit,
    vm: SecureMessengerViewModel = viewModel(),
) {
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val peerDisplayName by vm.peerDisplayName.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var shredTarget by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.sendImage(context, uri)
    }

    // The TakePicture contract needs a destination Uri up front (via FileProvider,
    // since minSdk 26 has no direct file:// access) and reports back only success/
    // failure, not the Uri — so the Uri this launch used has to be remembered here
    // to know what to read afterward.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            // deleteSourceAfter: true — this is this app's own throwaway cache file,
            // and vm.sendImage deletes it only once its async read has finished, not
            // before.
            vm.sendImage(context, uri, deleteSourceAfter = true)
        } else {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    fun startCameraCapture() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startCameraCapture() }

    DisposableEffect(Unit) {
        vm.bind()
        RelayConnectionService.isThreadVisible = true
        vm.markRead()
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
                Lifecycle.Event.ON_START -> { RelayConnectionService.isThreadVisible = true; vm.markRead() }
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
                    IconButton(onClick = onNavigateToHermes) {
                        Icon(Icons.Filled.SmartToy, contentDescription = "Hermes agent")
                    }
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
                IconButton(onClick = {
                    pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.Image, contentDescription = "Send photo")
                }
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startCameraCapture()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
                }
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
    // Decoded once per message id, not on every recomposition — imageBytes never
    // changes for a given row once stored.
    val bitmap = remember(message.id) {
        message.imageBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    var showFullscreen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .height(IntrinsicSize.Min)
                .combinedClickable(
                    onClick = { if (bitmap != null) showFullscreen = true },
                    onLongClick = onLongPress,
                ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                when {
                    message.shredded -> Text("Message deleted", color = textColor, textAlign = TextAlign.Start)
                    message.status == MessageStatus.FAILED && message.kind == MessageKind.IMAGE ->
                        Text("Photo too large to send", color = textColor, textAlign = TextAlign.Start)
                    message.kind == MessageKind.IMAGE -> if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Photo",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Couldn't load photo", color = textColor, textAlign = TextAlign.Start)
                    }
                    else -> Text(message.body, color = textColor, textAlign = TextAlign.Start)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        timeText,
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (message.outgoing && !message.shredded) {
                        val label = when (message.status) {
                            MessageStatus.SENT -> "Sent"
                            MessageStatus.DELIVERED -> "Delivered"
                            MessageStatus.READ -> "Seen"
                            else -> null
                        }
                        if (label != null) {
                            // Words instead of tick glyphs — confirmed live that a
                            // plain double-check was easy to miss/misread; "Seen"
                            // brighter-tinted still marks the one state that means a
                            // human actually opened the thread.
                            val tint = if (message.status == MessageStatus.READ) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                textColor
                            }
                            Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    if (showFullscreen && bitmap != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showFullscreen = false }) {
            Image(
                bitmap = bitmap,
                contentDescription = "Photo",
                modifier = Modifier.fillMaxSize().clickable { showFullscreen = false },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}
