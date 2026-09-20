package com.morton.trucknav.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morton.trucknav.Arrival

// "You have arrived" in the search box's slot; same pill palette as search.
@Composable
fun ArrivalCard(arrived: Arrival, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF10141a))
            .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .semantics { contentDescription = "Arrived" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("You have arrived", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            arrived.name?.let { Text(it, color = Color(0xFF9aa5b1), fontSize = 17.sp, maxLines = 1) }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF1f5f8b)).clickable(onClick = onDone)
                .semantics { contentDescription = "Done" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp)) }
    }
}
