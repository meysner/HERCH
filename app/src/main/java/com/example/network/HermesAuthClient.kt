package com.example.network

import android.content.SharedPreferences
import com.example.data.AuthStatus
import com.example.data.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
/**
 * @param httpClient общий клиент из AppModule — тот же CookieJar, что и у HermesApiClient.
 */
class HermesAuthClient(httpClient: OkHttpClient) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = httpClient

    suspend fun checkConnection(rawBaseUrl: String): LoginResult = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawBaseUrl)
            get("$baseUrl/health")
            val status = authStatus(baseUrl)
            if (status.authEnabled && !status.loggedIn) {
                LoginResult.Success("Server is reachable. Password login is required.")
            } else {
                LoginResult.Success("Server is reachable. Auth is not required.")
            }
        }.getOrElse { LoginResult.Failure(it.userMessage()) }
    }

    suspend fun login(rawBaseUrl: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawBaseUrl)
            get("$baseUrl/health")
            val before = authStatus(baseUrl)
            if (!before.authEnabled || before.loggedIn) {
                LoginResult.Success("Connected.")
            } else if (password.isBlank()) {
                LoginResult.Failure("Password is required for this WebUI.")
            } else {
                val body = JSONObject()
                    .put("password", password)
                    .toString()
                    .toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url("$baseUrl/api/auth/login")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(readError(response.code, response.body?.string()))
                    }
                }
                val after = authStatus(baseUrl)
                if (!after.authEnabled || after.loggedIn) {
                    LoginResult.Success("Logged in.")
                } else {
                    LoginResult.Failure("Login did not complete. Check the password.")
                }
            }
        }.getOrElse { LoginResult.Failure(it.userMessage()) }
    }

    suspend fun logout(rawBaseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawBaseUrl)
            val request = Request.Builder()
                .url("$baseUrl/api/auth/logout")
                .post("".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(readError(response.code, response.body?.string()))
                }
            }
        }
    }

    fun normalizeBaseUrl(raw: String): String = com.example.network.normalizeBaseUrl(raw)

    private fun authStatus(baseUrl: String): AuthStatus {
        val body = get("$baseUrl/api/auth/status")
        val json = JSONObject(body)
        return AuthStatus(
            authEnabled = json.optBoolean("auth_enabled", false),
            loggedIn = json.optBoolean("logged_in", false),
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(readError(response.code, body))
            }
            return body
        }
    }
}
