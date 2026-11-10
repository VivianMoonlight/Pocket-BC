package dev.vivian.mobilebc.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal interface CredentialStore {
    suspend fun savePassword(accountName: String, password: CharArray)
    suspend fun readPassword(accountName: String): CharArray?
    suspend fun clearPassword(accountName: String)
}

internal class DataStoreCredentialStore(
    context: Context,
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    private val encryptedPreferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        EncryptedPreferencesName,
        Context.MODE_PRIVATE,
    )

    override suspend fun savePassword(accountName: String, password: CharArray) = withContext(ioDispatcher) {
        val encrypted = encrypt(accountName, password)
        encryptedPreferences.edit()
            .putString(encryptedPasswordKey(accountName), encrypted)
            .commitOrThrow("save credential for $accountName")
        clearLegacyPassword(accountName)
    }

    override suspend fun readPassword(accountName: String): CharArray? = withContext(ioDispatcher) {
        encryptedPreferences.getString(encryptedPasswordKey(accountName), null)?.let { payload ->
            return@withContext decrypt(accountName, payload)
        }

        val legacyPassword = dataStore.data.first()[passwordKey(accountName)] ?: return@withContext null
        val legacyPasswordChars = legacyPassword.toCharArray()
        val encrypted = try {
            encrypt(accountName, legacyPasswordChars)
        } finally {
            legacyPasswordChars.fill('\u0000')
        }
        encryptedPreferences.edit()
            .putString(encryptedPasswordKey(accountName), encrypted)
            .commitOrThrow("migrate credential for $accountName")
        clearLegacyPassword(accountName)
        legacyPassword.toCharArray()
    }

    override suspend fun clearPassword(accountName: String) = withContext(ioDispatcher) {
        encryptedPreferences.edit()
            .remove(encryptedPasswordKey(accountName))
            .commitOrThrow("clear credential for $accountName")
        clearLegacyPassword(accountName)
    }

    private fun encrypt(accountName: String, password: CharArray): String {
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, credentialKey())
        cipher.updateAAD(accountName.toByteArray(StandardCharsets.UTF_8))
        val plainBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
        val encryptedBytes = try {
            cipher.doFinal(plainBytes)
        } finally {
            plainBytes.fill(0)
        }
        val iv = cipher.iv
        require(iv.size in 1..255)
        val payload = ByteBuffer.allocate(1 + iv.size + encryptedBytes.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encryptedBytes)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(accountName: String, payload: String): CharArray {
        val buffer = ByteBuffer.wrap(Base64.decode(payload, Base64.NO_WRAP))
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength > 0 && buffer.remaining() > ivLength)
        val iv = ByteArray(ivLength).also(buffer::get)
        val encryptedBytes = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.DECRYPT_MODE, credentialKey(), GCMParameterSpec(GcmTagLengthBits, iv))
        cipher.updateAAD(accountName.toByteArray(StandardCharsets.UTF_8))
        val plainBytes = cipher.doFinal(encryptedBytes)
        return try {
            String(plainBytes, StandardCharsets.UTF_8).toCharArray()
        } finally {
            plainBytes.fill(0)
        }
    }

    private fun credentialKey(): SecretKey = synchronized(CredentialKeyLock) {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(CredentialKeyAlias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    CredentialKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private suspend fun clearLegacyPassword(accountName: String) {
        dataStore.edit { prefs -> prefs.remove(passwordKey(accountName)) }
    }

    private fun SharedPreferences.Editor.commitOrThrow(operation: String) {
        check(commit()) { "Failed to $operation" }
    }

    private fun passwordKey(accountName: String): Preferences.Key<String> =
        stringPreferencesKey("password:$accountName")

    private fun encryptedPasswordKey(accountName: String): String = "password:$accountName"

    private companion object {
        private const val AndroidKeyStore = "AndroidKeyStore"
        private const val CipherTransformation = "AES/GCM/NoPadding"
        private const val CredentialKeyAlias = "mobile-bc-credential-key-v1"
        private const val EncryptedPreferencesName = "mobile-bc-credentials"
        private const val GcmTagLengthBits = 128
        private val CredentialKeyLock = Any()
    }
}
