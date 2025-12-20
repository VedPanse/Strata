// commonMain/kotlin/org/strata/auth/AuthViewModel.kt
package org.strata.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel responsible for handling Google sign-in and basic auth UI state.
 *
 * Exposes both granular flows [isLoading] and [error] for backward compatibility,
 * and a consolidated [state] which can be preferred by new UI.
 */
class AuthViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Combined UI state for authentication. Prefer this over individual flows in new code. */
    data class AuthUiState(val isLoading: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Initiates Google sign-in with all required scopes.
     * On success, persists session tokens for future app runs and invokes [onSuccess].
     */
    fun signIn(onSuccess: () -> Unit) {
        scope.launch {
            setLoading(true)
            val res = GoogleAuth.signIn(AuthScopes.ALL)
            setLoading(false)
            if (res.success) {
                // Persist session so user stays signed in across restarts
                SessionStorage.save(
                    AuthSession(
                        accessToken = res.accessToken,
                        refreshToken = res.refreshToken,
                        idToken = res.idToken,
                    ),
                )
                onSuccess()
            } else {
                setError(res.error ?: "Sign-in failed")
            }
        }
    }

    private fun setLoading(value: Boolean) {
        _isLoading.value = value
        _state.value = _state.value.copy(isLoading = value, error = if (value) null else _state.value.error)
        if (value) _error.value = null
    }

    private fun setError(message: String?) {
        _error.value = message
        _state.value = _state.value.copy(error = message)
    }
}
