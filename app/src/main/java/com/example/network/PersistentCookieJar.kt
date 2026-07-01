package com.example.network

import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal const val PREF_COOKIES = "webui_cookies"

// Разделитель хоста и сырой Set-Cookie-строки в персистентном хранилище.
// Раньше все хосты сохранялись одной плоской строкой без привязки к домену,
// из-за чего при смене адреса сервера (webui_url) куки одного хоста могли
// быть по ошибке восстановлены в контексте другого.
private const val HOST_SEPARATOR = "\u0001"

class PersistentCookieJar(
    private val prefs: SharedPreferences,
) : CookieJar {
    // OkHttp вызывает save/load из разных потоков — нужна синхронизация
    private val lock = Any()
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()

    // Хосты, для которых восстановление из SharedPreferences уже выполнялось
    // в рамках текущего процесса. Раньше restore() запускался только при
    // полностью пустой in-memory карте (`cookies.isEmpty()`), поэтому после
    // первого запроса к любому хосту куки для ВСЕХ остальных хостов (в
    // частности — нового сервера при смене webui_url в настройках) переставали
    // подтягиваться из хранилища до перезапуска процесса.
    private val restoredHosts = mutableSetOf<String>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        val hostCookies = this.cookies.getOrPut(url.host) { mutableListOf() }
        hostCookies.removeAll { existing -> cookies.any { it.name == existing.name } }
        hostCookies.addAll(cookies)
        restoredHosts.add(url.host) // самой свежей записи для хоста уже не нужен restore()
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        if (url.host !in restoredHosts) restore(url)
        val now = System.currentTimeMillis()
        val all = cookies[url.host].orEmpty()
        val valid = all.filter { it.expiresAt > now }
        if (valid.size != all.size) {
            cookies[url.host] = valid.toMutableList()
            persist()
        }
        valid
    }

    // Сохраняем каждую cookie с явной привязкой к хосту, под которым она была
    // получена (а не одной общей строкой на всё хранилище), чтобы при
    // восстановлении не приписать чужие куки текущему хосту.
    private fun persist() {
        val encoded = cookies.entries
            .flatMap { (host, hostCookies) -> hostCookies.map { host to it } }
            .joinToString("\n") { (host, cookie) -> "$host$HOST_SEPARATOR${cookie}" }
        prefs.edit().putString(PREF_COOKIES, encoded).apply()
    }

    private fun restore(url: HttpUrl) {
        restoredHosts.add(url.host)
        val restored = prefs.getString(PREF_COOKIES, "").orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val sepIndex = line.indexOf(HOST_SEPARATOR)
                if (sepIndex == -1) return@mapNotNull null // старый формат без хоста — пропускаем
                val host = line.substring(0, sepIndex)
                if (host != url.host) return@mapNotNull null
                val raw = line.substring(sepIndex + 1)
                Cookie.parse(url, raw)
            }
            .toList()
        if (restored.isNotEmpty()) {
            cookies[url.host] = restored.toMutableList()
        }
    }
}