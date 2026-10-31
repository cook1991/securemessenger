package net.spritzlive.securemessenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Large, high-contrast full-width back bar, ported verbatim from smsmmsapp — zero data
 * dependency, same oversized tap target for one obvious way out of a conversation.
 */
@Composable
fun BackToMessagesBar(onBack: () -> Unit, label: String = "BACK TO CONVERSATIONS") {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onBack)
            .semantics { contentDescription = label },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Text(
                "  $label",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
        }
    }
}
