package com.example.di

import android.content.SharedPreferences
import com.example.network.PersistentCookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Простой объект-фабрика для зависимостей уровня приложения.
 * Заменяет прямое создание OkHttpClient в HermesApiClient и HermesAuthClient —
 * оба теперь получают один и тот же экземпляр и, соответственно, один пул соединений
 * и один PersistentCookieJar (гарантирует, что auth-cookie видны API-клиенту).
 *
 * При подключении Hilt/Koin достаточно заменить тело provide-функции на @Provides/@Single.
 */
object AppModule {

    /**
     * Создаёт разделяемый OkHttpClient.
     * Вызывать один раз из Application или передавать через ViewModel.Factory.
     */
    fun provideOkHttpClient(prefs: SharedPreferences): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar(prefs))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
}
