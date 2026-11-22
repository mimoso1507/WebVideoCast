package com.ondacast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ondacast.app.ui.WebHost
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

    private var webMode = false

    // ---- Navegador interno: permisos, archivos y pantalla completa ----
    private var permCb: ((Boolean) -> Unit)? = null
    private val webPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permCb?.invoke(r.values.all { it })
        permCb = null
    }
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var customBack: OnBackPressedCallback? = null

    private fun showCustom(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        val decor = window.decorView as FrameLayout
        v.setBackgroundColor(android.graphics.Color.BLACK)
        decor.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowInsetsControllerCompat(window, decor).hide(WindowInsetsCompat.Type.systemBars())
        customView = v
        customCb = cb
        customBack = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = hideCustom()
        }.also { onBackPressedDispatcher.addCallback(this, it) }
    }

    private fun hideCustom() {
        val v = customView ?: return
        val decor = window.decorView as FrameLayout
        decor.removeView(v)
        WindowCompat.getInsetsController(window, decor).show(WindowInsetsCompat.Type.systemBars())
        customView = null
        customBack?.remove()
        customBack = null
        customCb?.onCustomViewHidden()
        customCb = null
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, r.resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
                .putExtra(MirrorService.EXTRA_WEB, webMode)
            ContextCompat.startForegroundService(this, i)
            if (webMode) CastController.startViewer() else CastController.castMirror()
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
        WebHost.requestPermissions = { perms, cb ->
            permCb = cb
            webPerms.launch(perms)
        }
        WebHost.chooseFile = { params, cb ->
            fileCb?.onReceiveValue(null)
            fileCb = cb
            try {
                fileChooser.launch(params.createIntent())
            } catch (e: Exception) {
                fileCb = null
                cb.onReceiveValue(null)
            }
        }
        WebHost.showCustomView = { v, cb -> showCustom(v, cb) }
        WebHost.hideCustomView = { hideCustom() }
        CastController.scan()
        setContent {
            OndaTheme {
                MainScreen(onStartMirror = { web -> startMirror(web) })
            }
        }
    }

    private fun startMirror(web: Boolean) {
        webMode = web
        if (!web && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection.launch(mgr.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        WebHost.requestPermissions = null
        WebHost.chooseFile = null
        WebHost.showCustomView = null
        WebHost.hideCustomView = null
        super.onDestroy()
    }
}
