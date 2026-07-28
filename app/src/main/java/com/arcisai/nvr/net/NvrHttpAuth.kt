package com.arcisai.nvr.net

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP auth that transparently supports **both Basic and Digest** for the NVR's
 * /netsdk API.
 *
 * The older Adiance NVR firmware accepted preemptive HTTP Basic, but newer /
 * Hikvision- or Dahua-derived 16-ch units answer the HTTP API with a Digest
 * challenge. A browser negotiates Digest automatically; our old code only ever
 * sent a Basic header, so those units replied 401.
 *
 * Strategy (backwards compatible, no per-request regression on Basic units):
 *   • [PreemptiveAuthInterceptor] attaches a Basic header up front — a Basic-only
 *     device is satisfied in a single round trip, exactly as before. Once a
 *     Digest challenge has been seen, it preemptively sends Digest instead so
 *     subsequent calls also stay at one round trip.
 *   • [DigestAuthenticator] is invoked by OkHttp on a 401. If the device offered
 *     a Digest challenge it computes the response and retries; if the challenge
 *     is Basic (or creds are simply wrong) it returns null so the 401 surfaces.
 */
class DigestState {
    private var realm: String? = null
    private var nonce: String? = null
    private var qop: String? = null
    private var opaque: String? = null
    private var algorithm: String? = null
    private val nc = AtomicInteger(0)

    @Synchronized
    fun update(p: Map<String, String>) {
        realm = p["realm"]
        nonce = p["nonce"]
        qop = p["qop"]
        opaque = p["opaque"]
        algorithm = p["algorithm"]
        nc.set(0)
    }

    @Synchronized
    fun hasChallenge(): Boolean = nonce != null

    fun buildHeader(user: String, pass: String, method: String, url: HttpUrl): String {
        val realm = realm ?: ""
        val nonce = nonce ?: ""
        val digestUri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val ncValue = "%08x".format(nc.incrementAndGet())
        val cnonce = randomHex(16)
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$digestUri")
        val qopAuth = qop?.split(",")?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
        val response = if (qopAuth != null)
            md5("$ha1:$nonce:$ncValue:$cnonce:$qopAuth:$ha2")
        else
            md5("$ha1:$nonce:$ha2")

        val sb = StringBuilder("Digest ")
        sb.append("username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", ")
        sb.append("uri=\"$digestUri\", response=\"$response\"")
        algorithm?.let { sb.append(", algorithm=$it") }
        if (qopAuth != null) sb.append(", qop=$qopAuth, nc=$ncValue, cnonce=\"$cnonce\"")
        opaque?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }

    companion object {
        private val CHALLENGE = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,]*))")

        fun parseChallenge(header: String): Map<String, String> {
            val body = header.substringAfter("Digest").trim()
            val map = mutableMapOf<String, String>()
            for (m in CHALLENGE.findAll(body)) {
                val key = m.groupValues[1].lowercase()
                val value = m.groups[2]?.value ?: m.groups[3]?.value?.trim() ?: ""
                map[key] = value
            }
            return map
        }

        private fun md5(s: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}

class PreemptiveAuthInterceptor(
    private val username: String,
    private val password: String,
    private val state: DigestState,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Authorization") != null) return chain.proceed(req)

        val authValue = if (state.hasChallenge())
            state.buildHeader(username, password, req.method, req.url)
        else
            Credentials.basic(username, password)

        return chain.proceed(req.newBuilder().header("Authorization", authValue).build())
    }
}

class DigestAuthenticator(
    private val username: String,
    private val password: String,
    private val state: DigestState,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val digestChallenge = response.headers("WWW-Authenticate")
            .firstOrNull { it.startsWith("Digest", ignoreCase = true) }
            ?: return null  // device wants Basic (or none) — can't help; surface the 401

        val params = DigestState.parseChallenge(digestChallenge)
        val stale = params["stale"]?.equals("true", ignoreCase = true) == true
        val alreadyDigest = response.request.header("Authorization")
            ?.startsWith("Digest", ignoreCase = true) == true

        // We already answered with Digest and it's still 401 (and the nonce isn't
        // just stale) → the credentials are wrong. Stop to avoid a retry loop.
        if (alreadyDigest && !stale) return null

        state.update(params)
        val header = state.buildHeader(username, password, response.request.method, response.request.url)
        return response.request.newBuilder().header("Authorization", header).build()
    }
}
