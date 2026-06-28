package com.example.network

import org.json.JSONObject
import java.io.IOException
import java.net.URI

internal fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) {
        throw IllegalArgumentException("Enter WebUI URL.")
    }
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    val uri = URI(withScheme)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw IllegalArgumentException("URL must start with http:// or https://")
    }
    if (uri.host.isNullOrBlank()) {
        throw IllegalArgumentException("Enter a valid WebUI host.")
    }
    return withScheme
}

internal fun readError(code: Int, body: String?): String {
    val text = body.orEmpty()
    val apiMessage = runCatching {
        val json = JSONObject(text)
        json.optString("error").ifBlank { json.optString("message") }
    }.getOrDefault("")
    return if (apiMessage.isNotBlank()) {
        "HTTP $code: $apiMessage"
    } else {
        "HTTP $code"
    }
}

internal fun Throwable.userMessage(): String {
    return when (this) {
        is IllegalArgumentException -> message ?: "Invalid input."
        is IOException -> message ?: "Connection failed."
        else -> message ?: "Unexpected error."
    }
}
