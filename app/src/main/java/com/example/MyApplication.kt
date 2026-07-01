package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.di.AppModule
import com.example.notifications.NotificationHelper
import com.example.work.WorkScheduler

class MyApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        WorkScheduler.schedule(this)
    }

    override fun newImageLoader(): ImageLoader {
        val prefs = getSharedPreferences("herch_hermes", MODE_PRIVATE)
        val httpClient = AppModule.provideOkHttpClient(prefs)
        return ImageLoader.Builder(this)
            .okHttpClient(httpClient)
            .crossfade(true)
            .build()
    }
}
