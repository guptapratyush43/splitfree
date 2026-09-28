package com.splitfree.data

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.splitfree.BuildConfig
import com.splitfree.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Auth {
    private val fa get() = FirebaseAuth.getInstance()
    val user = MutableStateFlow<FirebaseUser?>(null)

    fun init() {
        user.value = fa.currentUser
        fa.addAuthStateListener { user.value = it.currentUser }
    }

    val uid: String? get() = fa.currentUser?.uid
    val email: String get() = fa.currentUser?.email.orEmpty().lowercase()
    val name: String get() = Repo.me.value?.name?.takeIf { it.isNotBlank() }
        ?: fa.currentUser?.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')

    /** Google account picker, then Firebase sign-in with the returned ID token. */
    suspend fun signIn(activity: Activity) {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val result = CredentialManager.create(activity)
            .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        fa.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
    }

    suspend fun signOut(context: Context) {
        Repo.stop()
        fa.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}

class ApiException(val code: Int, message: String) : IOException(message)

/** Calls to the Split Free Cloudflare Worker, signed with the Firebase ID token. */
object Api {
    suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw ApiException(401, "Not signed in")
        val c = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw ApiException(code, json.optString("error", "Server error $code"))
            json
        } finally {
            c.disconnect()
        }
    }

    fun friendly(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Something went wrong"
        is IOException -> "No internet connection. Try again when you're online."
        else -> e.message ?: "Something went wrong"
    }
}
