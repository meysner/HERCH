package com.example.network

import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal const val PREF_COOKIES = "webui_cookies"

class PersistentCookieJar(
    private val prefs: SharedPreferences,
) : CookieJar {
    // OkHttp вызывает save/load из разных потоков — нужна синхронизация
    private val lock = Any()
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        val hostCookies = this.cookies.getOrPut(url.host) { mutableListOf() }
        hostCookies.removeAll { existing -> cookies.any { it.name == existing.name } }
        hostCookies.addAll(cookies)
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        if (cookies.isEmpty()) restore(url)
        val now = System.currentTimeMillis()
        val all = cookies[url.host].orEmpty()
        val valid = all.filter { it.expiresAt > now }
        if (valid.size != all.size) {
            cookies[url.host] = valid.toMutableList()
            persist()
        }
        valid
    }

    private fun persist() {
        val encoded = cookies.values
            .flatten()
            .joinToString("\n") { it.toString() }
        prefs.edit().putString(PREF_COOKIES, encoded).apply()
    }

    private fun restore(url: HttpUrl) {
        val restored = prefs.getString(PREF_COOKIES, "").orEmpty()
            .lineSequence()
            .mapNotNull { raw -> Cookie.parse(url, raw) }
            .toList()
        if (restored.isNotEmpty()) {
            cookies[url.host] = restored.toMutableList()
        }
    }
}
