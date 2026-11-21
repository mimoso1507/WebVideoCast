package com.ondacast.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import com.ondacast.app.cast.CastController
import com.ondacast.app.cast.Device
import com.ondacast.app.cast.DeviceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onStartMirror: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val selected by CastController.selected.collectAsState()
    val showPicker by CastController.showPicker.collectAsState()
    val black by CastController.blackMode.collectAsState()
    val browserUrl by CastController.browserUrl.collectAsState()
    val snack = remember { SnackbarHostState() }
    val browser = remember { BrowserState(ctx) }

    LaunchedEffect(Unit) { CastController.events.collect { snack.showSnackbar(it) } }
    LaunchedEffect(browserUrl) {
        browserUrl?.let {
            tab = 1
            browser.load(it)
            CastController.browserUrl.value = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("OndaCast") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        TextButton(onClick = {
                            CastController.showPicker.value = true
                            CastController.scan()
                        }) {
                            Icon(
                                if (selected != null) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(selected?.name ?: "Conectar TV")
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    PlayerBar()
                    NavigationBar {
                        val items = listOf(
                            "Archivos" to Icons.Default.PermMedia,
                            "Web" to Icons.Default.Language,
                            "Espejo" to Icons.Default.ScreenShare,
                            "Más" to Icons.Default.Tune,
                        )
                        items.forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = { Icon(icon, contentDescription = label) }, label = { Text(label) }
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> LibraryScreen()
                    1 -> BrowserScreen(browser)
                    2 -> MirrorScreen(onStartMirror)
                    else -> ToolsScreen()
                }
            }
        }
        if (showPicker) DevicePickerDialog()
        if (black) BlackOverlay()
    }
}

@Composable
private fun BlackOverlay() {
    val activity = LocalContext.current as? Activity
    val view = LocalView.current
    BackHandler { CastController.blackMode.value = false }
    DisposableEffect(Unit) {
        val w = activity?.window
        if (w != null) {
            val lp = w.attributes
            lp.screenBrightness = 0f
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowInsetsControllerCompat(w, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (w != null) {
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { CastController.blackMode.value = false }) },
        contentAlignment = Alignment.Center
    ) {
        Text("Toca dos veces para encender", color = Color(0xFF1B1B1B), fontSize = 14.sp)
    }
}

@Composable
private fun DevicePickerDialog() {
    val devices by CastController.devices.collectAsState()
    val scanning by CastController.scanning.collectAsState()
    val selected by CastController.selected.collectAsState()
    AlertDialog(
        onDismissRequest = {
            CastController.showPicker.value = false
            CastController.pending.value = null
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Elige una TV", Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.width(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { CastController.scan() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Buscar de nuevo")
                }
            }
        },
        text = {
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Buscando dispositivos en tu Wi‑Fi…"
                    else "No se encontró ninguna TV. Verifica que el celular y la TV estén en la misma red Wi‑Fi y toca actualizar."
                )
            } else {
                LazyColumn {
                    items(devices, key = { it.id }) { d ->
                        ListItem(
                            headlineContent = { Text(d.name) },
                            supportingContent = { Text(label(d)) },
                            leadingContent = { Icon(Icons.Default.Tv, contentDescription = null) },
                            modifier = Modifier.clickable { CastController.select(d) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (selected != null) {
                TextButton(onClick = {
                    CastController.disconnect()
                    CastController.showPicker.value = false
                }) { Text("Desconectar") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                CastController.showPicker.value = false
                CastController.pending.value = null
            }) { Text("Cerrar") }
        },
    )
}

private fun label(d: Device) = when (d.type) {
    DeviceType.CHROMECAST -> "Chromecast / Google Cast"
    DeviceType.DLNA -> "DLNA / UPnP · ${d.host}"
    DeviceType.ROKU -> "Roku (experimental) · ${d.host}"
}
