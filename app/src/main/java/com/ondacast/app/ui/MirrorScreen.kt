package com.ondacast.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ondacast.app.cast.CastController
import com.ondacast.app.cast.DeviceType
import com.ondacast.app.data.Store
import com.ondacast.app.mirror.MirrorService

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = current == value, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun MirrorScreen(onStart: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val active by MirrorService.state.collectAsState()
    val selected by CastController.selected.collectAsState()
    val viewerUrl by CastController.viewerUrl.collectAsState()
    var res by remember { mutableStateOf(Store.mirrorRes) }
    var fps by remember { mutableStateOf(Store.mirrorFps) }
    var kbps by remember { mutableStateOf(Store.mirrorKbps) }
    var format by remember { mutableStateOf(Store.mirrorFormat) }
    var black by remember { mutableStateOf(Store.blackOnMirror) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Duplica la pantalla del celular en tu TV (DLNA/Roku). El sonido del teléfono también se envía en Android 10 o superior.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (selected?.type == DeviceType.CHROMECAST) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Chromecast no acepta este tipo de transmisión. Para duplicar en un Chromecast usa la función \"Transmitir pantalla\" de Android:",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Abrir transmitir pantalla de Android") }
        }

        Section("Resolución") {
            Choices(listOf("480p" to 480, "720p" to 720, "1080p" to 1080), res) { res = it; Store.mirrorRes = it }
        }
        Section("Fotogramas por segundo") {
            Choices(listOf("15" to 15, "24" to 24, "30" to 30), fps) { fps = it; Store.mirrorFps = it }
        }
        Section("Calidad (bitrate)") {
            Choices(
                listOf("Baja 2 Mbps" to 2000, "Media 4 Mbps" to 4000, "Alta 8 Mbps" to 8000), kbps
            ) { kbps = it; Store.mirrorKbps = it }
        }
        Section("Formato de pantalla") {
            Choices(
                listOf("Original" to "orig", "Estirar 16:9" to "169", "Estirar 4:3" to "43"), format
            ) { format = it; Store.mirrorFormat = it }
            Text(
                "Original conserva la proporción del celular (la TV pone barras negras). Los demás estiran la imagen para llenar la TV.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Section("Pantalla del celular") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Poner pantalla negra al empezar", Modifier.weight(1f))
                Switch(checked = black, onCheckedChange = { black = it; Store.blackOnMirror = it })
            }
            Text(
                "Apaga la luz del celular pero sigue transmitiendo. Toca dos veces para volver a encenderla. " +
                    "(Si el celular se bloquea del todo, Android detiene la captura.)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        if (!active) {
            Button(onClick = { onStart(false) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ScreenShare, null); Spacer(Modifier.width(8.dp)); Text("Empezar a duplicar en la TV")
            }
            Spacer(Modifier.height(16.dp))
            Section("Ver en el navegador del proyector") {
                Text(
                    "Para proyectores o TV sin Chromecast/DLNA: el celular muestra una dirección y la abres en el navegador " +
                        "del proyector (misma Wi‑Fi). No se instala nada allá. Esta opción no envía sonido.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Compartir por navegador")
                }
            }
        } else {
            val url = viewerUrl
            if (url != null) {
                Section("Abre esta dirección en el navegador del proyector") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(url, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("OndaCast", url))
                    }) { Text("Copiar dirección") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { CastController.blackMode.value = true }, modifier = Modifier.weight(1f)) {
                    Text("Pantalla negra")
                }
                Button(onClick = {
                    ctx.startService(
                        Intent(ctx, MirrorService::class.java).setAction(MirrorService.ACTION_STOP)
                    )
                    CastController.blackMode.value = false
                    CastController.viewerUrl.value = null
                    CastController.stop()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.StopScreenShare, null); Spacer(Modifier.width(8.dp)); Text("Detener")
                }
            }
        }
    }
}
