package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.*
import com.github.kr328.clash.design.R

class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {
    private companion object {
        const val ACTIVATION_STORE = ACTIVATION_STORE_NAME
        const val KEY_CODE = "code"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_PROFILE_UUID = "profile_uuid"
        const val KEY_UPDATE_VERSION = "update_version"
        const val KEY_CLIENT_ID = "client_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        when(intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return finish()

                launch {
                    try {
                        importShenxianyunSubscription(uri)
                        Toast.makeText(this@ExternalControlActivity, R.string.import_code_success, Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@ExternalControlActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@ExternalControlActivity,
                            getString(R.string.import_code_failed_after_retries),
                            Toast.LENGTH_LONG,
                        ).show()
                    } finally {
                        finish()
                    }
                }
                return
            }

            Intents.ACTION_TOGGLE_CLASH -> if(Remote.broadcasts.clashRunning) {
                stopClash()
            }
            else {
                startClash()
            }

            Intents.ACTION_START_CLASH -> if(!Remote.broadcasts.clashRunning) {
                startClash()
            }
            else {
                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
            }

            Intents.ACTION_STOP_CLASH -> if(Remote.broadcasts.clashRunning) {
                stopClash()
            }
            else {
                Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
            }
        }
        return finish()
    }

    private suspend fun importShenxianyunSubscription(uri: Uri) {
        val ticket = uri.getQueryParameter("ticket")?.trim().orEmpty()
        val url = uri.getQueryParameter("url")?.trim().orEmpty()
        if (ticket.isBlank() && url.isBlank()) {
            throw IllegalArgumentException("Missing import ticket")
        }
        val code = extractCodeFromSubscriptionUrl(url)
        if (ticket.isBlank() && code.isBlank()) {
            importExternalSubscription(uri, url)
            return
        }

        val apiBase = uri.getQueryParameter("api")
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf {
                val scheme = Uri.parse(it).scheme
                scheme == "https" || scheme == "http"
            }
            ?: EndpointResolver.apiBase()
        val clientId = stableClientId()
        val credentials = if (ticket.isNotBlank()) {
            exchangeManagedImportTicket(ticket, apiBase, clientId)
        } else {
            val issued = issueManagedImportTicket(code, apiBase)
            exchangeManagedImportTicket(issued, apiBase, clientId)
        }
        val accessCode = credentials.accessCode.ifBlank {
            uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { code }
        }
        if (accessCode.isBlank()) throw IllegalStateException("安全导入未返回提取码")
        val normalized = credentials.copy(accessCode = accessCode)
        val content = fetchManagedSubscription(normalized)
        val uuid = installManagedProfile(accessCode, content)
        saveManagedCredentials(normalized)
        activationStore().edit()
            .putString(KEY_CODE, accessCode)
            .putString(KEY_PROFILE_UUID, uuid.toString())
            .putString(KEY_EXPIRES_AT, normalized.expiresAt)
            .putLong(KEY_UPDATE_VERSION, 0L)
            .apply()
    }

    private suspend fun importExternalSubscription(uri: Uri, url: String) {
        withProfile {
            val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                "file" -> Profile.Type.File
                else -> Profile.Type.Url
            }
            val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)
            val uuid = create(type, name)
            patch(uuid, name, url, 0)
            commit(uuid, null)
            queryByUUID(uuid)?.let {
                setActive(it)
            }
        }
    }

    private fun extractCodeFromSubscriptionUrl(value: String): String {
        val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return ""
        parsed.getQueryParameter("code")?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        val segments = parsed.pathSegments
        val subIndex = segments.indexOf("sub")
        if (subIndex >= 0 && subIndex + 1 < segments.size) {
            return segments[subIndex + 1].trim()
        }
        return ""
    }

    private fun stableClientId(): String {
        val store = activationStore()
        val saved = store.getString(KEY_CLIENT_ID, "")?.trim().orEmpty()
        if (saved.isNotBlank()) {
            return saved
        }
        val generated = UUID.randomUUID().toString()
        store.edit().putString(KEY_CLIENT_ID, generated).apply()
        return generated
    }

    private fun activationStore() =
        getSharedPreferences(ACTIVATION_STORE, Context.MODE_PRIVATE)

    private fun startClash() {
//        if (currentProfile == null) {
//            Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_LONG).show()
//            return
//        }
        val vpnRequest = startClashService()
        if (vpnRequest != null) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
    }

    private fun stopClash() {
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
