package com.enigma.messenger.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

class SecurityManager private constructor(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "enigma_secure_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _isLocked = MutableStateFlow(hasPin() || isBiometricEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    companion object {
        private const val KEY_PIN_HASH = "sec_pin_hash"
        private const val KEY_PIN_SALT = "sec_pin_salt"
        private const val KEY_BIOMETRIC_ENABLED = "sec_biometric_enabled"
        private const val KEY_NOTIFICATION_PRIVACY = "sec_notification_privacy"

        const val NOTIF_FULL = 0
        const val NOTIF_GENERIC = 1
        const val NOTIF_NONE = 2

        @Volatile
        private var INSTANCE: SecurityManager? = null

        fun getInstance(context: Context): SecurityManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun hasPin(): Boolean = encryptedPrefs.getString(KEY_PIN_HASH, null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hash = hashPin(pin, saltHex)

        encryptedPrefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, saltHex)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = encryptedPrefs.getString(KEY_PIN_HASH, null) ?: return true
        val saltHex = encryptedPrefs.getString(KEY_PIN_SALT, "") ?: ""
        val hash = hashPin(pin, saltHex)
        val matched = (storedHash == hash)
        if (matched) {
            _isLocked.value = false
        }
        return matched
    }

    fun isBiometricEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    _isLocked.value = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Biometric authentication not recognized.")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Enigma Vault")
            .setSubtitle("Confirm your biometric credentials to open private conversations")
            .setNegativeButtonText("Use PIN")
            .build()

        prompt.authenticate(promptInfo)
    }

    fun lock() {
        if (hasPin() || isBiometricEnabled()) {
            _isLocked.value = true
        }
    }

    fun unlockDirectly() {
        _isLocked.value = false
    }

    fun getNotificationPrivacy(): Int = encryptedPrefs.getInt(KEY_NOTIFICATION_PRIVACY, NOTIF_GENERIC)

    fun setNotificationPrivacy(mode: Int) {
        encryptedPrefs.edit().putInt(KEY_NOTIFICATION_PRIVACY, mode).apply()
    }

    private fun hashPin(pin: String, saltHex: String): String {
        val input = "$saltHex:$pin:enigma_sec_v1"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}