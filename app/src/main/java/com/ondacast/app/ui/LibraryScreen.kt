package com.ondacast.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.ondacast.app.cast.CastController
import com.ondacast.app.cast.MediaKind
import com.ondacast.app.data.Library
import com.ondacast.app.data.LocalItem
import com.ondacast.app.data.Store
import com.ondacast.app.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun permissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES
    ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(permissions().any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = permissions().any { p -> ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Library.fromUri(ctx, uri)?.let { CastController.requestCast(it) }
        }
    }
    val kinds = listOf(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE)
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    val slideshow by CastController.slideshowActive.collectAsState()
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }

    LaunchedEffect(granted, tab) {
        items = if (granted) withContext(Dispatchers.IO) { Library.query(ctx, kinds[tab]) } else emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = { picker.launch(arrayOf("video/*", "audio/*", "image/*")) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Elegir un archivo…")
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Videos", "Música", "Fotos").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Para mostrar tus videos, música y fotos necesito permiso de acceso.")
                Spacer(Modifier.size(12.dp))
                Button(onClick = { permLauncher.launch(permissions()) }) { Text("Dar permiso") }
            }
            return@Column
        }
        if (kinds[tab] == MediaKind.IMAGE && items.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (slideshow) {
                    Button(onClick = { CastController.stopSlideshow() }) {
                        Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Detener presentación")
                    }
                } else {
                    Button(onClick = {
                        CastController.startSlideshow(items.take(200).map { it.toMedia() }, Store.slideshowSec)
                    }) {
                        Icon(Icons.Default.Slideshow, null); Spacer(Modifier.width(6.dp))
                        Text("Presentación (${Store.slideshowSec} s)")
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay archivos en esta categoría", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.uri.toString() }) { it ->
                    ListItem(
                        headlineContent = { Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (it.durationMs > 0) it.durationMs.fmtTime() else "%.1f MB".format(it.size / 1048576.0)
                            )
                        },
                        leadingContent = {
                            val mod = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            if (it.kind == MediaKind.AUDIO) {
                                Box(mod, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                AsyncImage(
                                    model = it.uri, imageLoader = loader, contentDescription = null,
                                    modifier = mod, contentScale = ContentScale.Crop
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { CastController.enqueue(it.toMedia()) }) {
                                Icon(Icons.Default.PlaylistAdd, "Añadir a la cola")
                            }
                        },
                        modifier = Modifier.clickable { CastController.requestCast(it.toMedia()) }
                    )
                }
            }
        }
    }
}
