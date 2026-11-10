package dev.vivian.mobilebc.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ChatImageUploadButton(enabled: Boolean, onInsert: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember { ChatImageUploader() }
    val credentials = remember { ChatImageCredentials(context.applicationContext) }
    val uriHandler = LocalUriHandler.current
    val currentInsert by rememberUpdatedState(onInsert)
    var open by rememberSaveable { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("chat_images", 0) }
    var hostName by rememberSaveable { mutableStateOf(preferences.getString("host", ChatImageHost.Litterbox.name)!!) }
    val host = ChatImageHost.entries.firstOrNull { it.name == hostName } ?: ChatImageHost.Litterbox
    var credential by remember(host) { mutableStateOf(credentials.read(host)) }
    var uploading by remember { mutableStateOf(false) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var url by rememberSaveable { mutableStateOf<String?>(null) }
    val currentlyEnabled by rememberUpdatedState(enabled)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && currentlyEnabled && open) {
            uploading = true
            error = null
            uploadJob = scope.launch {
                try {
                    if (host.needsCredential) credentials.write(host, credential)
                    url = uploader.upload(context.applicationContext, uri, host, credential)
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    error = when (e) {
                        is ImageTooLargeException -> R.string.chat_image_too_large
                        is InvalidImageException -> R.string.chat_image_invalid
                        else -> R.string.chat_image_upload_failed
                    }
                } finally { uploading = false }
            }
        }
    }
    TextButton(enabled = enabled, onClick = { open = true }) { Text(stringResource(R.string.chat_image_attach)) }
    if (open) {
        fun close() { uploadJob?.cancel(); open = false; error = null; url = null }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text(stringResource(R.string.chat_image_upload_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.chat_image_upload_help, host.maxMegabytes))
                    ChatImageHost.entries.forEach { option ->
                        TextButton(enabled = !uploading && url == null, onClick = {
                            hostName = option.name
                            preferences.edit().putString("host", option.name).apply()
                        }) {
                            Text((if (host == option) "✓ " else "") + stringResource(option.label))
                        }
                    }
                    if (host.needsCredential && url == null) {
                        OutlinedTextField(
                            value = credential, onValueChange = { credential = it }, enabled = !uploading,
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            label = { Text(if (host == ChatImageHost.ImgBB) "API Key" else "Client ID") },
                        )
                        Text(stringResource(R.string.chat_image_credentials_help))
                        TextButton(enabled = !uploading, onClick = {
                            uriHandler.openUri(if (host == ChatImageHost.ImgBB) "https://api.imgbb.com/" else "https://api.imgur.com/oauth2/addclient")
                        }) { Text(stringResource(R.string.chat_image_credentials_get)) }
                        TextButton(enabled = !uploading, onClick = {
                            credentials.write(host, "")
                            credential = ""
                        }) { Text(stringResource(R.string.chat_image_credentials_clear)) }
                    }
                    if (uploading) { LinearProgressIndicator(); Text(stringResource(R.string.chat_image_uploading)) }
                    error?.let { Text(stringResource(it, host.maxMegabytes), color = MaterialTheme.colorScheme.error) }
                    url?.let { Text(stringResource(R.string.chat_image_uploaded)); Text(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = enabled && !uploading && (!host.needsCredential || credential.isNotBlank()), onClick = {
                    val uploaded = url
                    if (uploaded == null) picker.launch("image/*")
                    else { currentInsert(uploaded); close() }
                }) { Text(stringResource(if (url == null) R.string.chat_image_choose else R.string.chat_image_insert)) }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.chat_image_cancel)) } },
        )
    }
}
