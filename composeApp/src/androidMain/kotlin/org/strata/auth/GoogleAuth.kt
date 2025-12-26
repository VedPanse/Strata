package org.strata.auth

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.strata.platform.AppContext
import java.io.File

actual object GoogleAuth {
    private const val REQUEST_CODE_SIGN_IN = 9312
    private const val REQUEST_CODE_RECOVER = 9313
    private var pending: CompletableDeferred<AuthResult>? = null
    private var pendingScopes: List<String> = emptyList()
    private var pendingAccount: GoogleSignInAccount? = null
    private var awaitingRecovery = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    actual suspend fun signIn(scopes: List<String>): AuthResult {
        val activity =
            AppContext.activity?.get()
                ?: return AuthResult(false, error = "No active Android activity")
        val clientId = readEnv("GOOGLE_ANDROID_CLIENT_ID")?.trim().orEmpty()
        if (clientId.isBlank()) return AuthResult(false, error = "Missing GOOGLE_ANDROID_CLIENT_ID")
        if (pending != null) return AuthResult(false, error = "Sign-in already in progress")

        val gsoBuilder =
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(clientId)
        val scopeObjects = scopes.map { Scope(it) }
        if (scopeObjects.isNotEmpty()) {
            val first = scopeObjects.first()
            val rest = scopeObjects.drop(1).toTypedArray()
            gsoBuilder.requestScopes(first, *rest)
        }
        val client = GoogleSignIn.getClient(activity, gsoBuilder.build())

        pending = CompletableDeferred()
        pendingScopes = scopes
        pendingAccount = null
        awaitingRecovery = false
        activity.startActivityForResult(client.signInIntent, REQUEST_CODE_SIGN_IN)
        return pending!!.await()
    }

    fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        when (requestCode) {
            REQUEST_CODE_SIGN_IN -> handleSignInResult(data)
            REQUEST_CODE_RECOVER -> handleRecoveryResult(resultCode)
        }
    }

    private fun handleSignInResult(data: Intent?) {
        val deferred = pending ?: return
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account =
            try {
                task.getResult(ApiException::class.java)
            } catch (e: Exception) {
                clearPending()
                deferred.complete(AuthResult(false, error = e.message ?: "Google Sign-In failed"))
                return
            }
        pendingAccount = account
        awaitingRecovery = false

        scope.launch {
            val accessToken = fetchAccessToken(account, pendingScopes)

            if (accessToken == null && awaitingRecovery) {
                return@launch
            }

            if (accessToken.isNullOrBlank()) {
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    clearPending()
                    deferred.complete(AuthResult(false, error = "Missing token from Google Sign-In"))
                } else {
                    clearPending()
                    deferred.complete(
                        AuthResult(
                            success = true,
                            accessToken = idToken,
                            idToken = idToken,
                            refreshToken = null,
                        ),
                    )
                }
                return@launch
            }

            clearPending()
            deferred.complete(
                AuthResult(
                    success = true,
                    accessToken = accessToken,
                    refreshToken = null,
                    idToken = account.idToken,
                ),
            )
        }
    }

    private fun handleRecoveryResult(resultCode: Int) {
        val deferred = pending ?: return
        val account = pendingAccount
        if (account == null) {
            clearPending()
            deferred.complete(AuthResult(false, error = "Recovery failed: missing account"))
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            clearPending()
            deferred.complete(AuthResult(false, error = "Google Sign-In cancelled"))
            return
        }

        scope.launch {
            val accessToken = fetchAccessToken(account, pendingScopes)

            if (accessToken.isNullOrBlank()) {
                clearPending()
                deferred.complete(AuthResult(false, error = "Failed to obtain access token"))
                return@launch
            }

            clearPending()
            deferred.complete(
                AuthResult(
                    success = true,
                    accessToken = accessToken,
                    refreshToken = null,
                    idToken = account.idToken,
                ),
            )
        }
    }

    private fun clearPending() {
        pending = null
        pendingScopes = emptyList()
        pendingAccount = null
        awaitingRecovery = false
    }

    private suspend fun fetchAccessToken(
        account: GoogleSignInAccount,
        scopes: List<String>,
    ): String? {
        val ctx = AppContext.context
        val acct = account.account ?: return null
        val scopeString =
            if (scopes.isEmpty()) {
                "oauth2:https://www.googleapis.com/auth/userinfo.profile"
            } else {
                "oauth2:" + scopes.joinToString(" ")
            }
        return try {
            withContext(Dispatchers.IO) {
                GoogleAuthUtil.getToken(ctx, acct, scopeString)
            }
        } catch (e: UserRecoverableAuthException) {
            val activity = AppContext.activity?.get() ?: return null
            awaitingRecovery = true
            withContext(Dispatchers.Main) {
                activity.startActivityForResult(e.intent, REQUEST_CODE_RECOVER)
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

private val envFileCandidates =
    listOf(
        "src/.env",
        "composeApp/src/.env",
        ".env",
        "local.properties",
    )

private fun readEnv(key: String): String? {
    System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    envFileCandidates.forEach { path ->
        val file = File(path)
        if (!file.exists()) return@forEach
        val map =
            file.readLines()
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    line.substring(0, idx) to line.substring(idx + 1)
                }
                .toMap()
        map[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return null
}
