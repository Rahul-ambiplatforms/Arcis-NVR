package com.arcisai.nvr.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-NVR credentials. In LAN mode `host` is the NVR's LAN IP; in Remote (P2P)
 * mode `deviceId` is the rendezvous service_id used against the signaling
 * server and `host` becomes a localhost loopback to the libjuice tunnel.
 */
data class NvrCredentials(
    val host: String,
    val port: Int = 80,
    val username: String,
    val password: String,
    val remote: Boolean = false,
    val deviceId: String = "",
    val accountEmail: String = "",
    val accountName: String  = "",
    val accountAbdName: String = "",
)

class CredentialStore(private val ctx: Context) {

    // ── Encrypted store (preferred) ────────────────────────────────────────────
    // EncryptedSharedPreferences can throw GeneralSecurityException or IOException
    // when the Keystore entry is invalidated (e.g. a fresh APK install over the
    // previous one, or a device backup-restore cycle).  We delete the stale file
    // and retry once; if that also fails, we fall back to the plain store below.

    private fun buildEncrypted(): SharedPreferences? = runCatching {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx, "nvr_creds", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Stale/corrupt key — wipe the encrypted file and try once more with a
        // freshly-generated Keystore entry.
        ctx.deleteSharedPreferences("nvr_creds")
        runCatching {
            val key = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, "nvr_creds", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()  // null → callers fall back to plain prefs
    }

    // ── Plain fallback ─────────────────────────────────────────────────────────
    // Stored in the app's private data directory (MODE_PRIVATE) so no other app
    // can read it without root.  Used when EncryptedSharedPreferences is
    // unavailable AND as a persistent shadow that survives Keystore invalidation.

    private fun plain(): SharedPreferences =
        ctx.getSharedPreferences("nvr_session", Context.MODE_PRIVATE)

    // ── Public API ─────────────────────────────────────────────────────────────

    fun save(c: NvrCredentials) {
        // Always write to plain prefs first so we have a fallback.
        plain().edit()
            .putString("host",             c.host)
            .putInt   ("port",             c.port)
            .putString("user",             c.username)
            .putString("pass",             c.password)
            .putBoolean("remote",          c.remote)
            .putString("device_id",        c.deviceId)
            .putString("account_email",    c.accountEmail)
            .putString("account_name",     c.accountName)
            .putString("account_abd_name", c.accountAbdName)
            .apply()

        // Best-effort write to encrypted store for extra security.
        runCatching {
            buildEncrypted()?.edit()
                ?.putString("host",             c.host)
                ?.putInt   ("port",             c.port)
                ?.putString("user",             c.username)
                ?.putString("pass",             c.password)
                ?.putBoolean("remote",          c.remote)
                ?.putString("device_id",        c.deviceId)
                ?.putString("account_email",    c.accountEmail)
                ?.putString("account_name",     c.accountName)
                ?.putString("account_abd_name", c.accountAbdName)
                ?.apply()
        }
    }

    fun load(): NvrCredentials? {
        // Try encrypted first.
        val enc = buildEncrypted()
        if (enc != null) {
            val creds = runCatching { readFrom(enc) }.getOrNull()
            if (creds != null) return creds
        }
        // Fall back to plain store.
        return runCatching { readFrom(plain()) }.getOrNull()
    }

    fun clear() {
        plain().edit().clear().apply()
        runCatching { buildEncrypted()?.edit()?.clear()?.apply() }
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private fun readFrom(p: SharedPreferences): NvrCredentials? {
        val host = p.getString("host", null) ?: return null
        return NvrCredentials(
            host          = host,
            port          = p.getInt("port", 80),
            username      = p.getString("user",             "admin") ?: "admin",
            password      = p.getString("pass",             "")      ?: "",
            remote        = p.getBoolean("remote",          false),
            deviceId      = p.getString("device_id",        "")      ?: "",
            accountEmail  = p.getString("account_email",    "")      ?: "",
            accountName   = p.getString("account_name",     "")      ?: "",
            accountAbdName = p.getString("account_abd_name","")      ?: "",
        )
    }
}
