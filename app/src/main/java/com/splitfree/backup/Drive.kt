package com.splitfree.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google authorization for the one scope we use: the app's hidden
 * appDataFolder in Drive. It cannot see any of the user's own Drive files.
 */
object DriveAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request()).awaitResult()

    /** A fresh token if access was already granted, or null when the user must approve it on screen. */
    suspend fun silentToken(context: Context): String? {
        val result = authorize(context)
        return if (result.hasResolution()) null else result.accessToken
    }
}

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Tiny Drive v3 REST client, limited to the appDataFolder space. Files there
 * never show in My Drive; users only see a "hidden app data" size under
 * Drive > Settings > Manage apps.
 */
class Drive(private val token: String) {
    data class FileRef(val id: String, val modified: Long)

    fun find(name: String): FileRef? {
        val q = URLEncoder.encode("name = '$name'", "UTF-8")
        val body = request("GET", "$API/files?spaces=appDataFolder&q=$q&fields=files(id,modifiedTime)&pageSize=10")
        val files = JSONObject(String(body)).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        return FileRef(f.getString("id"), runCatching { Instant.parse(f.getString("modifiedTime")).toEpochMilli() }.getOrDefault(0L))
    }

    /** Creates the file in appDataFolder, or overwrites its contents when [id] is known. */
    fun upsert(name: String, mime: String, bytes: ByteArray, id: String?) {
        if (id != null) {
            // HttpURLConnection has no PATCH; Google APIs honour the override header.
            request("POST", "$UPLOAD/files/$id?uploadType=media", bytes, mime, mapOf("X-HTTP-Method-Override" to "PATCH"))
            return
        }
        val boundary = "sf-${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
        out.write("--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        request("POST", "$UPLOAD/files?uploadType=multipart", out.toByteArray(), "multipart/related; boundary=$boundary")
    }

    fun download(id: String): ByteArray = request("GET", "$API/files/$id?alt=media")

    fun delete(id: String) { request("DELETE", "$API/files/$id") }

    fun email(): String? = runCatching {
        JSONObject(String(request("GET", "$API/about?fields=user(emailAddress)"))).getJSONObject("user").getString("emailAddress")
    }.getOrNull()

    private fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap()
    ): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.readBytes()?.let(::String) }.getOrNull().orEmpty()
                throw HttpException(code, "Drive error $code ${err.take(200)}")
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }
}
