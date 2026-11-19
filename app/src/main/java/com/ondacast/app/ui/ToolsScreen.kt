package com.ondacast.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ondacast.app.cast.CastController
import com.ondacast.app.cast.CastMedia
import com.ondacast.app.cast.MediaKind
import com.ondacast.app.data.Store
import com.ondacast.app.fmtTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Chips(options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = current == value, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun ToolsScreen() {
    var link by remember { mutableStateOf("") }
    var imageMode by remember { mutableStateOf(Store.imageMode) }
    var slide by remember { mutableIntStateOf(Store.slideshowSec) }
    var proxy by remember { mutableStateOf(Store.proxyWeb) }
    var history by remember { mutableStateOf(Store.history()) }
    var bookmarks by remember { mutableStateOf(Store.bookmarks()) }
    val queue by CastController.queue.collectAsState()
    val qIndex by CastController.queueIndex.collectAsState()
    val sleep by CastController.sleepRemaining.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section("Enviar desde un enlace") {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, singleLine = true,
                label = { Text("Pega la dirección del video, audio o imagen") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                val u = link.trim()
                if (u.startsWith("http")) {
                    val ext = u.substringBefore('?').substringAfterLast('.', "").lowercase()
                    val kind = when (ext) {
                        "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus" -> MediaKind.AUDIO
                        "jpg", "jpeg", "png", "gif", "webp" -> MediaKind.IMAGE
                        else -> MediaKind.VIDEO
                    }
                    CastController.requestCast(
                        CastMedia(kind, u.substringBefore('?').substringAfterLast('/'), u, guessMime(u, kind), false)
                    )
                }
            }, enabled = link.trim().startsWith("http")) { Text("Enviar a la TV") }
        }

        Section("Formato de las fotos en la TV") {
            Chips(
                listOf("Ajustar" to "fit", "Llenar" to "fill", "Estirar" to "stretch", "Original" to "orig"), imageMode
            ) { imageMode = it; CastController.setImageMode(it) }
            Text(
                "Ajustar muestra la foto completa con barras; Llenar recorta para cubrir toda la TV; Estirar deforma para llenar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Presentación de fotos: segundos por foto") {
            Chips(listOf("3" to 3, "5" to 5, "10" to 10, "20" to 20), slide) { slide = it; Store.slideshowSec = it }
        }

        Section("Temporizador de apagado") {
            Chips(
                listOf("15 min" to 15, "30 min" to 30, "60 min" to 60, "90 min" to 90, "2 h" to 120),
                if (sleep > 0) -1 else 0
            ) { CastController.setSleepTimer(it) }
            if (sleep > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Se detiene en ${(sleep * 1000L).fmtTime()}", Modifier.weight(1f))
                    TextButton(onClick = { CastController.setSleepTimer(0) }) { Text("Cancelar") }
                }
            }
        }

        Section("Contenido web") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pasar videos web por el celular (soluciona bloqueos de la página)", Modifier.weight(1f))
                Switch(checked = proxy, onCheckedChange = { proxy = it; Store.proxyWeb = it })
            }
        }

        Section("Cola de reproducción (${queue.size})") {
            if (queue.isEmpty()) {
                Text("Vacía. Usa el botón + en archivos o en la lista de medios detectados.", style = MaterialTheme.typography.bodySmall)
            }
            queue.forEachIndexed { i, m ->
                Row(
                    Modifier.fillMaxWidth().clickable { CastController.playQueueAt(i) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (if (i == qIndex) "▶ " else "${i + 1}. ") + m.title, Modifier.weight(1f).padding(vertical = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == qIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { CastController.removeFromQueue(i) }) { Icon(Icons.Default.Close, "Quitar") }
                }
            }
        }

        Section("Favoritos web") {
            if (bookmarks.isEmpty()) Text("Marca la estrella en el navegador para guardar páginas.", style = MaterialTheme.typography.bodySmall)
            bookmarks.forEach { (t, u) ->
                Row(Modifier.fillMaxWidth().clickable { CastController.browserUrl.value = u }, verticalAlignment = Alignment.CenterVertically) {
                    Text(t, Modifier.weight(1f).padding(vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { Store.toggleBookmark(t, u); bookmarks = Store.bookmarks() }) {
                        Icon(Icons.Default.Close, "Quitar")
                    }
                }
            }
        }

        Section("Historial") {
            if (history.isEmpty()) Text("Aún no has enviado nada.", style = MaterialTheme.typography.bodySmall)
            history.forEach { m ->
                Text(
                    m.title, Modifier.fillMaxWidth().clickable { CastController.requestCast(m) }.padding(vertical = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (history.isNotEmpty()) TextButton(onClick = { Store.clearHistory(); history = emptyList() }) { Text("Borrar historial") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
