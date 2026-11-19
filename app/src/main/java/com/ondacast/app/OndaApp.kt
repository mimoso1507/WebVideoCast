package com.ondacast.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.ondacast.app.cast.CastController
import com.ondacast.app.data.Store

class OndaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transmisión", NotificationManager.IMPORTANCE_LOW)
        )
        CastController.init(this)
    }

    companion object {
        const val CHANNEL = "onda_cast"
    }
}
