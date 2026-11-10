package dev.vivian.mobilebc.feature.auth

import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.DefaultBcServerUrl
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuth
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.connection.SessionConnector
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

data class LoginAccountOption(
    val accountName: String,
    val password: String,
    val serverUrl: String,
)

enum class LoginValidationError {
    AccountRequired,
    PasswordRequired,
    VersionRequired,
    ServerUrlInvalid,
}

enum class LoginOperation {
    LoggingIn,
    DeletingSavedAccount,
}

data class LoginUiState(
    val savedAccounts: List<LoginAccountOption> = emptyList(),
    val accountName: String = "",
    val password: String = "",
    val bcVersion: String = "",
    val serverUrl: String = DefaultBcServerUrl,
    val saveCredentials: Boolean = true,
    val accountMenuExpanded: Boolean = false,
    val statusText: String = BcSessionState.Idle.label(),
    val operation: LoginOperation? = null,
    val errorMessage: String? = null,
    val validationError: LoginValidationError? = null,
) {
    val currentSavedAccount: LoginAccountOption? = savedAccounts.firstOrNull { it.accountName == accountName }
    val isBusy: Boolean get() = operation != null
}

class LoginViewModel(
    private val auth: BcSessionAuth,
    private val connectionStatus: ConnectionStatusReader,
    private val connector: SessionConnector,
    private val scope: CoroutineScope,
    private val savedLoginState: StateFlow<BcSavedLoginState>,
    private val prepareResources: suspend (version: String) -> Unit = {},
    private val onLoginSucceeded: suspend (accountName: String, password: String, serverConfig: BcServerConfig, saveCredentials: Boolean) -> Unit,
    private val onDeleteSavedAccount: suspend (accountName: String) -> Unit,
) {
    private data class InputState(
        val accountName: String? = null,
        val password: String? = null,
        val bcVersion: String? = null,
        val serverUrl: String? = null,
        val saveCredentials: Boolean? = null,
        val accountMenuExpanded: Boolean = false,
        val operation: Operation? = null,
        val errorMessage: String? = null,
        val validationError: LoginValidationError? = null,
        val credentialErrorDismissed: Boolean = false,
    )

    private class Operation(val kind: LoginOperation)

    private var input = InputState()
    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        scope.launch { savedLoginState.collect { publish() } }
        scope.launch { connectionStatus.snapshot.collect { publish() } }
    }

    fun onAccountMenuExpandedChange(expanded: Boolean) {
        if (input.operation != null) return
        update { it.copy(accountMenuExpanded = expanded) }
    }

    fun selectNewAccount() {
        if (input.operation != null) return
        resetAccountSelection()
    }

    private fun resetAccountSelection() {
        update {
            it.copy(
                accountName = "",
                password = "",
                serverUrl = DefaultBcServerUrl,
                saveCredentials = true,
                accountMenuExpanded = false,
                errorMessage = null,
                validationError = null,
            )
        }
    }

    fun selectSavedAccount(account: LoginAccountOption) {
        if (input.operation != null) return
        if (savedLoginState.value.accounts.none { it.accountName == account.accountName }) return
        update {
            it.copy(
                accountName = account.accountName,
                password = null,
                serverUrl = null,
                saveCredentials = true,
                accountMenuExpanded = false,
                errorMessage = null,
                validationError = null,
            )
        }
    }

    fun onAccountNameChange(value: String) {
        if (input.operation != null) return
        val current = buildUiState()
        val saved = current.savedAccounts.firstOrNull { it.accountName == value }
        if (saved != null) {
            selectSavedAccount(saved)
        } else {
            update {
                it.copy(
                    accountName = value,
                    password = if (current.currentSavedAccount != null) "" else it.password,
                    serverUrl = if (current.currentSavedAccount != null) DefaultBcServerUrl else it.serverUrl,
                    validationError = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun onPasswordChange(value: String) {
        if (input.operation != null) return
        val accountName = buildUiState().accountName
        update { it.copy(accountName = accountName, password = value, validationError = null, errorMessage = null) }
    }

    fun onBcVersionChange(value: String) {
        if (input.operation != null) return
        update {
            it.copy(
                bcVersion = value.trim().replaceFirstChar { character -> character.uppercase() },
                validationError = null,
                errorMessage = null,
            )
        }
    }

    fun onServerUrlChange(value: String) {
        if (input.operation != null) return
        val accountName = buildUiState().accountName
        update { it.copy(accountName = accountName, serverUrl = value, validationError = null, errorMessage = null) }
    }

    fun onSaveCredentialsChange(value: Boolean) {
        if (input.operation != null) return
        update { it.copy(saveCredentials = value) }
    }

    fun deleteCurrentSavedAccount() {
        if (input.operation != null) return
        val account = buildUiState().currentSavedAccount ?: return
        val operation = Operation(LoginOperation.DeletingSavedAccount)
        update { it.copy(operation = operation, accountMenuExpanded = false, errorMessage = null, validationError = null) }
        scope.launch {
            try {
                onDeleteSavedAccount(account.accountName)
                currentCoroutineContext().ensureActive()
                resetAccountSelection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                currentCoroutineContext().ensureActive()
                update { it.copy(errorMessage = throwable.toUserMessage("delete the saved account")) }
            }
        }.invokeOnCompletion { releaseOperation(operation) }
    }

    fun login() {
        if (input.operation != null) return
        val current = buildUiState()
        val normalizedAccountName = current.accountName.trim()
        when {
            normalizedAccountName.isBlank() -> {
                update { it.copy(errorMessage = null, validationError = LoginValidationError.AccountRequired) }
                return
            }

            current.password.isBlank() -> {
                update { it.copy(errorMessage = null, validationError = LoginValidationError.PasswordRequired) }
                return
            }
        }
        val normalizedVersion = current.bcVersion
            .takeIf(String::isNotBlank)
            ?.normalizedBcVersionOrNull()
        if (normalizedVersion == null) {
            update {
                it.copy(errorMessage = null, validationError = LoginValidationError.VersionRequired)
            }
            return
        }

        val serverUrl = current.serverUrl.trim().ifBlank { DefaultBcServerUrl }
        val serverUri = runCatching { URI(serverUrl) }.getOrNull()
        if (serverUri?.scheme?.lowercase() !in setOf("https", "http") || serverUri?.host.isNullOrBlank()) {
            update { it.copy(errorMessage = null, validationError = LoginValidationError.ServerUrlInvalid) }
            return
        }

        val operation = Operation(LoginOperation.LoggingIn)
        update { it.copy(errorMessage = null, validationError = null, operation = operation, accountMenuExpanded = false) }
        scope.launch {
            try {
                val serverConfig = BcServerConfig(
                    serverUrl = serverUrl,
                    version = normalizedVersion,
                )
                prepareResources(normalizedVersion)
                currentCoroutineContext().ensureActive()
                connector.connect(serverConfig)
                currentCoroutineContext().ensureActive()
                auth.login(normalizedAccountName, current.password)
                currentCoroutineContext().ensureActive()
                val session = connectionStatus.snapshot.value
                if (session.isVerifiedOnline && session.account?.accountName == normalizedAccountName) {
                    onLoginSucceeded(
                        normalizedAccountName,
                        current.password,
                        serverConfig,
                        current.saveCredentials,
                    )
                } else {
                    update {
                        it.copy(
                            errorMessage = "Login did not complete. Current state: ${session.state.label()}",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                currentCoroutineContext().ensureActive()
                update { it.copy(errorMessage = throwable.toUserMessage("finish login")) }
            }
        }.invokeOnCompletion { releaseOperation(operation) }
    }

    private fun releaseOperation(operation: Operation) {
        update { if (it.operation === operation) it.copy(operation = null) else it }
    }

    private fun update(transform: (InputState) -> InputState) {
        input = transform(input).copy(credentialErrorDismissed = true)
        publish()
    }

    private fun publish() {
        _uiState.update { buildUiState() }
    }

    private fun buildUiState(): LoginUiState {
        val saved = savedLoginState.value
        val accounts = saved.accounts.map { LoginAccountOption(it.accountName, it.password, it.serverUrl) }
        val accountName = input.accountName ?: saved.selectedAccountName.takeIf(String::isNotBlank)
            ?: accounts.firstOrNull()?.accountName.orEmpty()
        val account = accounts.firstOrNull { it.accountName == accountName }
        return LoginUiState(
            savedAccounts = accounts,
            accountName = accountName,
            password = input.password ?: account?.password.orEmpty(),
            bcVersion = input.bcVersion ?: saved.bcVersion.normalizedVersionInput(),
            serverUrl = input.serverUrl ?: account?.serverUrl?.ifBlank { DefaultBcServerUrl } ?: DefaultBcServerUrl,
            saveCredentials = input.saveCredentials ?: (account != null || saved.saveCredentialsByDefault),
            accountMenuExpanded = input.accountMenuExpanded,
            statusText = connectionStatus.snapshot.value.state.label(),
            operation = input.operation?.kind,
            errorMessage = input.errorMessage ?: saved.credentialErrorMessage.takeUnless { input.credentialErrorDismissed },
            validationError = input.validationError,
        )
    }
}

private fun String.normalizedVersionInput(): String {
    val input = trim()
    if (input.isBlank()) return ""
    return normalizedBcVersionOrNull() ?: input
}

private fun Throwable.toUserMessage(action: String): String =
    message?.takeIf { it.isNotBlank() } ?: "Could not $action (${this::class.java.simpleName})."
