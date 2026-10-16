package com.pcremote.agent

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import com.pcremote.settings.prefsKv
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * The PC's Windows PIN/password, per PC, sealed with an Android Keystore key that only a
 * fingerprint (strong biometric) unlocks, once per use. Nothing readable is ever stored.
 */
object PinVault {
    private const val ALIAS = "pcr_unlock"
    private fun pref(pcId: String) = "pcremote_unlock_$pcId"

    /** Fingerprint unlock needs Android 11+ and an enrolled strong biometric. */
    fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 30 &&
        ctx.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun has(ctx: Context, pcId: String) = prefsKv(ctx).get(pref(pcId)) != null

    fun forget(ctx: Context, pcId: String) = prefsKv(ctx).put(pref(pcId), null)

    /** Asks for a fingerprint, then stores [secret] for [pcId]. */
    suspend fun save(activity: Activity, pcId: String, secret: String, title: String): Boolean {
        if (Build.VERSION.SDK_INT < 30 || secret.isEmpty()) return false
        val cipher = runCatching { Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) } }.getOrElse {
            if (it is KeyPermanentlyInvalidatedException) reset()
            return false
        }
        val ok = authenticate(activity, cipher, title) ?: return false
        val sealed = Sealed(ok.iv, ok.doFinal(secret.toByteArray(Charsets.UTF_8)))
        prefsKv(activity).put(pref(pcId), sealed.encode())
        return true
    }

    /** Asks for a fingerprint and returns the stored secret; null if cancelled or none stored. */
    suspend fun reveal(activity: Activity, pcId: String, title: String): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val sealed = prefsKv(activity).get(pref(pcId))?.let(Sealed::decode) ?: return null
        val cipher = runCatching { Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.iv)) } }.getOrElse {
            // a new fingerprint was enrolled: the key is gone, so is every stored secret
            if (it is KeyPermanentlyInvalidatedException) { reset(); forget(activity, pcId) }
            return null
        }
        val ok = authenticate(activity, cipher, title) ?: return null
        return runCatching { String(ok.doFinal(sealed.data), Charsets.UTF_8) }.getOrNull()
    }

    private fun reset() = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }

    @RequiresApi(30)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
        }.generateKey()
    }

    @RequiresApi(30)
    private suspend fun authenticate(activity: Activity, cipher: Cipher, title: String): Cipher? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        val exec = activity.mainExecutor
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(activity.getString(android.R.string.cancel), exec) { _, _ -> if (cont.isActive) cont.resume(null) }
            .build()
            .authenticate(BiometricPrompt.CryptoObject(cipher), cancel, exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(null)
                }
            })
    }

    private const val TRANSFORM = "AES/GCM/NoPadding"
}

/** Stored form: base64(iv) + ":" + base64(ciphertext). */
class Sealed(val iv: ByteArray, val data: ByteArray) {
    fun encode() = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)

    companion object {
        fun decode(s: String): Sealed? {
            val (a, b) = s.split(':').takeIf { it.size == 2 } ?: return null
            return runCatching { Sealed(Base64.decode(a, Base64.NO_WRAP), Base64.decode(b, Base64.NO_WRAP)) }.getOrNull()
        }
    }
}
