package com.ondacast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ondacast.app.cast.CastController
import com.ondacast.app.mirror.MirrorService
import com.ondacast.app.ui.MainScreen
import com.ondacast.app.ui.OndaTheme

class MainActivity : ComponentActivity() {

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, r.resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
            CastController.castMirror()
        } else {
            Toast.makeText(this, "Permiso de captura cancelado", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchProjection()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        CastController.scan()
        setContent {
            OndaTheme {
                MainScreen(onStartMirror = { startMirror() })
            }
        }
    }

    private fun startMirror() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection.launch(mgr.createScreenCaptureIntent())
    }
}
