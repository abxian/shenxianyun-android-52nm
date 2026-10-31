package com.github.kr328.clash.service.traffic
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal
private const val ACTIVATION_STORE_NAME="service_traffic_crypto_v1"
private const val MANAGED_KEY_ALIAS="sxy-service-traffic-v1"
private const val MANAGED_WRAPPED_KEY="wrapped"
private const val MANAGED_RSA_ALIAS="sxy-service-traffic-rsa-v1"
private fun Context.managedSecretKey(): SecretKey {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return legacyManagedSecretKey()
    }
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(MANAGED_KEY_ALIAS, null) as? SecretKey)?.let { return it }

    val generator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore",
    )
    generator.init(
        KeyGenParameterSpec.Builder(
            MANAGED_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build(),
    )
    return generator.generateKey()
}

@Suppress("DEPRECATION")
private fun Context.legacyManagedSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!keyStore.containsAlias(MANAGED_RSA_ALIAS)) {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        val spec = KeyPairGeneratorSpec.Builder(this)
            .setAlias(MANAGED_RSA_ALIAS)
            .setSubject(X500Principal("CN=Shenxianyun Managed Subscription"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    val store = getSharedPreferences(ACTIVATION_STORE_NAME, Context.MODE_PRIVATE)
    val wrapped = store.getString(MANAGED_WRAPPED_KEY, null)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    val raw = if (wrapped == null) {
        ByteArray(32).also { SecureRandom().nextBytes(it) }.also { generated ->
            cipher.init(Cipher.ENCRYPT_MODE, keyStore.getCertificate(MANAGED_RSA_ALIAS).publicKey)
            store.edit()
                .putString(
                    MANAGED_WRAPPED_KEY,
                    Base64.encodeToString(cipher.doFinal(generated), Base64.NO_WRAP),
                )
                .commit()
        }
    } else {
        cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(MANAGED_RSA_ALIAS, null))
        cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
    }
    return SecretKeySpec(raw, "AES")
}


/** Explicit encrypted no-backup transport across UI and service processes. */
object TrafficSecureStore {
    fun write(context: Context, name: String, text: String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, context.managedSecretKey())
        val bytes=cipher.iv+cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val file=AtomicFile(File(context.noBackupFilesDir,name))
        val out=file.startWrite()
        try { out.write(bytes);file.finishWrite(out) } catch(error: Throwable) {file.failWrite(out);throw error}
    }
    fun read(context: Context,name: String): String? {
        val file=File(context.noBackupFilesDir,name)
        if (!file.exists()) return null
        val bytes=AtomicFile(file).readFully()
        require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,context.managedSecretKey(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
}
