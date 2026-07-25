package com.github.kr328.clash

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
import java.util.UUID

internal const val ACTIVATION_STORE_NAME = "jc116_activation"
internal const val MANAGED_PROFILE_UUID_KEY = "profile_uuid"
internal const val SUBSCRIPTION_NETWORK_ATTEMPTS = 4
internal const val SUBSCRIPTION_NETWORK_RETRIES = SUBSCRIPTION_NETWORK_ATTEMPTS - 1
private const val MANAGED_CREDENTIAL_KEY = "managed_credentials_v2"
private const val MANAGED_KEY_ALIAS = "shenxianyun-managed-subscription-v2"
private const val MANAGED_WRAPPED_KEY = "managed_wrapped_key_v2"
private const val MANAGED_RSA_ALIAS = "shenxianyun-managed-subscription-rsa-v2"

internal data class ManagedCredentials(
    val accessCode: String,
    val apiBase: String,
    val subscriptionUrl: String,
    val deviceToken: String,
    val expiresAt: String,
    val limitMode: String,
)

internal suspend fun Context.issueManagedImportTicket(
    code: String,
    apiBase: String,
): String = withContext(Dispatchers.IO) {
    val body = JSONObject()
        .put("code", code)
        .put("target", "shenxianyun")
        .toString()
        .toByteArray(Charsets.UTF_8)
    val connection = (URL("${apiBase.trimEnd('/')}/api/import/ticket").openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("User-Agent", "Shenxianyun-Android/Managed")
    }
    try {
        connection.outputStream.use { it.write(body) }
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val json = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        if (connection.responseCode !in 200..299 || !json.optBoolean("ok", false)) {
            throw IllegalStateException(json.optString("message", "无法创建安全导入票据"))
        }
        Uri.parse(json.optString("launch_url", "")).lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("安全导入票据格式无效")
    } finally {
        connection.disconnect()
    }
}

internal suspend fun Context.exchangeManagedImportTicket(
    ticket: String,
    apiBase: String,
    clientId: String,
): ManagedCredentials = withContext(Dispatchers.IO) {
    val base = apiBase.trimEnd('/')
    val body = JSONObject()
        .put("ticket", ticket)
        .put("client_id", clientId)
        .put("platform", "android")
        .toString()
        .toByteArray(Charsets.UTF_8)
    val connection = (URL("$base/api/import/exchange").openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("User-Agent", "Shenxianyun-Android/Managed")
        setRequestProperty("X-Client-Id", clientId)
    }
    try {
        connection.outputStream.use { it.write(body) }
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val json = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        val deviceToken = json.optString("device_token", "")
        val subscriptionUrl = json.optString("subscription_url", "")
        if (
            connection.responseCode !in 200..299 ||
            !json.optBoolean("ok", false) ||
            deviceToken.isBlank() ||
            subscriptionUrl.isBlank()
        ) {
            throw IllegalStateException(json.optString("message", "安全导入票据无效或已过期"))
        }
        ManagedCredentials(
            accessCode = json.optString("name", ""),
            apiBase = base,
            subscriptionUrl = subscriptionUrl,
            deviceToken = deviceToken,
            expiresAt = json.optString("expires_at", ""),
            limitMode = json.optString("limit_mode", "hybrid"),
        )
    } finally {
        connection.disconnect()
    }
}

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
                .apply()
        }
    } else {
        cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(MANAGED_RSA_ALIAS, null))
        cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
    }
    return SecretKeySpec(raw, "AES")
}

internal fun Context.saveManagedCredentials(value: ManagedCredentials) {
    val json = JSONObject().apply {
        put("access_code", value.accessCode)
        put("api_base", value.apiBase)
        put("subscription_url", value.subscriptionUrl)
        put("device_token", value.deviceToken)
        put("expires_at", value.expiresAt)
        put("limit_mode", value.limitMode)
    }.toString().toByteArray(Charsets.UTF_8)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, managedSecretKey())
    val encrypted = cipher.doFinal(json)
    val packed = ByteArray(cipher.iv.size + encrypted.size)
    cipher.iv.copyInto(packed)
    encrypted.copyInto(packed, cipher.iv.size)
    getSharedPreferences(ACTIVATION_STORE_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(MANAGED_CREDENTIAL_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
        .apply()
}

internal fun Context.loadManagedCredentials(): ManagedCredentials? = runCatching {
    val encoded = getSharedPreferences(ACTIVATION_STORE_NAME, Context.MODE_PRIVATE)
        .getString(MANAGED_CREDENTIAL_KEY, null)
        ?: return null
    val packed = Base64.decode(encoded, Base64.NO_WRAP)
    require(packed.size > 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        managedSecretKey(),
        GCMParameterSpec(128, packed.copyOfRange(0, 12)),
    )
    val json = JSONObject(
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8),
    )
    ManagedCredentials(
        accessCode = json.getString("access_code"),
        apiBase = json.getString("api_base").trimEnd('/'),
        subscriptionUrl = json.getString("subscription_url"),
        deviceToken = json.getString("device_token"),
        expiresAt = json.optString("expires_at", ""),
        limitMode = json.optString("limit_mode", "hybrid"),
    )
}.getOrNull()

internal fun Context.clearManagedCredentials() {
    getSharedPreferences(ACTIVATION_STORE_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(MANAGED_CREDENTIAL_KEY)
        .apply()
}

internal suspend fun Context.fetchManagedSubscription(
    credentials: ManagedCredentials,
): String = withContext(Dispatchers.IO) {
    val connection = (URL(credentials.subscriptionUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 12_000
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer ${credentials.deviceToken}")
        setRequestProperty("User-Agent", "Shenxianyun-Android/Managed")
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("受管订阅获取失败，请重新导入提取码")
        }
        connection.inputStream.bufferedReader().use { it.readText() }
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("受管订阅内容为空")
    } finally {
        connection.disconnect()
    }
}

internal suspend fun Context.installManagedProfile(code: String, content: String): UUID {
    val store = getSharedPreferences(ACTIVATION_STORE_NAME, Context.MODE_PRIVATE)
    val oldUuid = managedProfileUuid()
    return withProfile {
        val old = oldUuid?.let { queryByUUID(it) }
        val uuid = if (old?.type == Profile.Type.File) {
            patch(old.uuid, code, "", 0)
            old.uuid
        } else {
            create(Profile.Type.File, code)
        }
        val config = filesDir.resolve("pending").resolve(uuid.toString()).resolve("config.yaml")
        config.parentFile?.mkdirs()
        config.writeText(content)
        commit(uuid, null)
        queryByUUID(uuid)?.let { setActive(it) }
        if (oldUuid != null && oldUuid != uuid) {
            runCatching { delete(oldUuid) }
        }
        store.edit()
            .putString(MANAGED_PROFILE_UUID_KEY, uuid.toString())
            .apply()
        uuid
    }
}

internal fun Context.managedProfileUuid(): UUID? =
    getSharedPreferences(ACTIVATION_STORE_NAME, Context.MODE_PRIVATE)
        .getString(MANAGED_PROFILE_UUID_KEY, null)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

internal fun Context.isManagedProfile(uuid: UUID): Boolean =
    managedProfileUuid() == uuid

internal fun subscriptionRetryDelayMillis(retryNumber: Int): Long =
    (1_200L shl (retryNumber - 1).coerceAtLeast(0)).coerceAtMost(4_800L)
