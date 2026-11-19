package com.ondacast.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ondacast.app.cast.CastController
import com.ondacast.app.cast.PlayStatus
import com.ondacast.app.fmtTime

@Composable
fun PlayerBar() {
    val selected by CastController.selected.collectAsState()
    val st by CastController.player.collectAsState()
    val dev = selected ?: return

    var dragging by remember { mutableStateOf<Float?>(null) }
    var dragVol by remember { mutableStateOf<Float?>(null) }
    val active = st.status != PlayStatus.IDLE || st.title.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                if (active && st.title.isNotEmpty()) st.title else "Conectado a ${dev.name}",
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (st.status == PlayStatus.ERROR) {
                Text(st.error ?: "Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (st.durationMs > 0) {
                val pos = dragging ?: st.positionMs.toFloat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pos.toLong().fmtTime(), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = pos.coerceIn(0f, st.durationMs.toFloat()),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { CastController.seek(it.toLong()) }
                            dragging = null
                        },
                        valueRange = 0f..st.durationMs.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(st.durationMs.fmtTime(), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { CastController.prev() }) { Icon(Icons.Default.SkipPrevious, "Anterior") }
                IconButton(onClick = { CastController.togglePlay() }) {
                    Icon(
                        if (st.status == PlayStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Reproducir o pausar", Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { CastController.next() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
                IconButton(onClick = { CastController.stop() }) { Icon(Icons.Default.Stop, "Detener") }
                if (st.volume >= 0) {
                    Icon(Icons.Default.VolumeUp, null, Modifier.padding(start = 8.dp).size(20.dp))
                    Slider(
                        value = dragVol ?: st.volume.toFloat(),
                        onValueChange = { dragVol = it },
                        onValueChangeFinished = {
                            dragVol?.let { CastController.setVolume(it.toInt()) }
                            dragVol = null
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
