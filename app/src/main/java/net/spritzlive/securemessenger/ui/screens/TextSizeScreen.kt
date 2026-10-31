package net.spritzlive.securemessenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.ui.BackToMessagesBar
import net.spritzlive.securemessenger.ui.TextSizePreference
import net.spritzlive.securemessenger.ui.theme.SENIOR_FONT_SCALE

private const val MIN_SCALE = 1.0f
private const val MAX_SCALE = 2.0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persisted by TextSizePreference.flow(context).collectAsState(initial = SENIOR_FONT_SCALE)
    var scale by remember { mutableFloatStateOf(persisted) }

    LaunchedEffect(persisted) { scale = persisted }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Text size") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(24.dp)) {
                Surface {
                    Text(
                        "Sample message text",
                        fontSize = TextUnit(18 * scale, TextUnitType.Sp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Slider(
                    value = scale,
                    valueRange = MIN_SCALE..MAX_SCALE,
                    onValueChange = {
                        scale = it
                        scope.launch { TextSizePreference.set(context, it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${"%.0f".format(scale * 100)}%")
            }
            Spacer(Modifier.weight(1f))
            BackToMessagesBar(onBack = onBack, label = "BACK TO CONVERSATION")
        }
    }
}
