package dev.vivian.mobilebc.feature.auth

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.vivian.mobilebc.core.model.DefaultBcServerUrl
import dev.vivian.mobilebc.core.ui.LocalScreenInteractive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onAccountMenuExpandedChange: (Boolean) -> Unit,
    onSelectNewAccount: () -> Unit,
    onSelectSavedAccount: (LoginAccountOption) -> Unit,
    onAccountNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onBcVersionChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onSaveCredentialsChange: (Boolean) -> Unit,
    onDeleteSavedAccount: () -> Unit,
    onLogin: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by rememberSaveable(uiState.accountName) { mutableStateOf(false) }
    var confirmSavedAccountDeletion by rememberSaveable(uiState.currentSavedAccount?.accountName) {
        mutableStateOf(false)
    }
    var showConnectionOptions by rememberSaveable { mutableStateOf(uiState.serverUrl != DefaultBcServerUrl) }
    val accountFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val versionFocus = remember { FocusRequester() }
    val serverFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val interactive = LocalScreenInteractive.current
    LaunchedEffect(interactive) {
        if (!interactive) {
            focusManager.clearFocus(force = true)
            confirmSavedAccountDeletion = false
            onAccountMenuExpandedChange(false)
        }
    }
    LaunchedEffect(uiState.isBusy) {
        if (uiState.isBusy) focusManager.clearFocus()
    }
    LaunchedEffect(uiState.validationError, interactive) {
        if (!interactive) return@LaunchedEffect
        when (uiState.validationError) {
            LoginValidationError.AccountRequired -> accountFocus.requestFocus()
            LoginValidationError.PasswordRequired -> passwordFocus.requestFocus()
            LoginValidationError.VersionRequired -> versionFocus.requestFocus()
            LoginValidationError.ServerUrlInvalid -> serverFocus.requestFocus()
            null -> Unit
        }
    }
    LaunchedEffect(uiState.currentSavedAccount?.accountName) {
        if (uiState.serverUrl != DefaultBcServerUrl) showConnectionOptions = true
    }
    val connectionOptionsExpanded = showConnectionOptions || uiState.validationError == LoginValidationError.ServerUrlInvalid

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        onBack?.let { back ->
            TextButton(onClick = back, enabled = !uiState.isBusy) {
                Text(stringResource(R.string.back_to_settings))
            }
        }
        Text("BC", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.login_tagline), style = MaterialTheme.typography.bodyMedium)
        if (uiState.savedAccounts.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = interactive && uiState.accountMenuExpanded,
                onExpandedChange = { if (!uiState.isBusy) onAccountMenuExpandedChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
            ) {
                OutlinedTextField(
                    value = uiState.currentSavedAccount?.accountName
                        ?: uiState.accountName.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.new_account_named, it) }
                        ?: stringResource(R.string.new_account),
                    onValueChange = {},
                    readOnly = true,
                    enabled = !uiState.isBusy,
                    label = { Text(stringResource(R.string.saved_accounts)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = uiState.accountMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = interactive && uiState.accountMenuExpanded,
                    onDismissRequest = { onAccountMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_account)) },
                        onClick = onSelectNewAccount,
                        enabled = !uiState.isBusy,
                    )
                    uiState.savedAccounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.accountName) },
                            onClick = { onSelectSavedAccount(account) },
                            enabled = !uiState.isBusy,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = uiState.accountName,
            onValueChange = onAccountNameChange,
            label = { Text(stringResource(R.string.account)) },
            enabled = !uiState.isBusy,
            isError = uiState.validationError == LoginValidationError.AccountRequired,
            supportingText = if (uiState.validationError == LoginValidationError.AccountRequired) {
                { Text(stringResource(R.string.account_required)) }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(accountFocus)
                .padding(top = 24.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        )
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.password)) },
            enabled = !uiState.isBusy,
            isError = uiState.validationError == LoginValidationError.PasswordRequired,
            supportingText = if (uiState.validationError == LoginValidationError.PasswordRequired) {
                { Text(stringResource(R.string.password_required)) }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus)
                .padding(top = 12.dp),
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { versionFocus.requestFocus() }),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = !uiState.isBusy) {
                    Text(stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
                }
            },
        )
        OutlinedTextField(
            value = uiState.bcVersion,
            onValueChange = onBcVersionChange,
            label = { Text(stringResource(R.string.bc_version)) },
            enabled = !uiState.isBusy,
            isError = uiState.validationError == LoginValidationError.VersionRequired,
            supportingText = { Text(stringResource(R.string.bc_version_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(versionFocus)
                .padding(top = 12.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = if (connectionOptionsExpanded) ImeAction.Next else ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { serverFocus.requestFocus() },
                onDone = {
                    if (!uiState.isBusy) onLogin()
                },
            ),
        )
        TextButton(
            onClick = { showConnectionOptions = !showConnectionOptions },
            enabled = !uiState.isBusy,
        ) {
            Text(stringResource(if (connectionOptionsExpanded) R.string.hide_connection_options else R.string.connection_options))
        }
        if (connectionOptionsExpanded) {
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.server_url)) },
                supportingText = {
                    Text(stringResource(
                        if (uiState.validationError == LoginValidationError.ServerUrlInvalid) {
                            R.string.server_url_invalid
                        } else R.string.server_url_hint,
                    ))
                },
                isError = uiState.validationError == LoginValidationError.ServerUrlInvalid,
                enabled = !uiState.isBusy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(serverFocus),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { if (!uiState.isBusy) onLogin() }),
            )
            if (uiState.serverUrl != DefaultBcServerUrl) {
                TextButton(onClick = { onServerUrlChange(DefaultBcServerUrl) }, enabled = !uiState.isBusy) {
                    Text(stringResource(R.string.use_default_server))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = uiState.saveCredentials,
                    role = Role.Checkbox,
                    enabled = !uiState.isBusy,
                    onValueChange = onSaveCredentialsChange,
                )
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = uiState.saveCredentials,
                onCheckedChange = null,
            )
            Text(stringResource(R.string.save_account_password), style = MaterialTheme.typography.bodyMedium)
        }
        if (uiState.currentSavedAccount != null) {
            TextButton(
                onClick = { confirmSavedAccountDeletion = true },
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.delete_saved_account))
            }
        }
        Button(
            onClick = onLogin,
            enabled = !uiState.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
        ) {
            Text(stringResource(when (uiState.operation) {
                LoginOperation.LoggingIn -> R.string.login_in_progress
                LoginOperation.DeletingSavedAccount -> R.string.delete_in_progress
                null -> R.string.login
            }))
        }
        Text(
            text = stringResource(R.string.status_format, uiState.statusText),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .padding(top = 8.dp),
            )
        }
    }

    if (interactive && confirmSavedAccountDeletion && uiState.currentSavedAccount != null) {
        AlertDialog(
            onDismissRequest = { confirmSavedAccountDeletion = false },
            title = { Text(stringResource(R.string.delete_saved_account_title)) },
            text = { Text(stringResource(R.string.delete_saved_account_message, uiState.currentSavedAccount.accountName)) },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isBusy,
                    onClick = {
                        confirmSavedAccountDeletion = false
                        onDeleteSavedAccount()
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSavedAccountDeletion = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
