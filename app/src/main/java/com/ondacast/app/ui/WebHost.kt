package com.ondacast.app.ui

import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** Puente entre el navegador interno y la actividad (permisos, selector de archivos, pantalla completa). */
object WebHost {
    var requestPermissions: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null
    var chooseFile: ((WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Unit)? = null
    var showCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null
    var hideCustomView: (() -> Unit)? = null
}
