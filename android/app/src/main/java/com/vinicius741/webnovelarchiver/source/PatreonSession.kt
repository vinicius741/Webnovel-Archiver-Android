package com.vinicius741.webnovelarchiver.source

import android.webkit.CookieManager
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * The user's patreon.com `session_id`, copied from a browser where they signed in (Google sign-in
 * cannot run inside the app). It lives only in the shared [CookieManager], so OkHttp sends it via
 * [com.vinicius741.webnovelarchiver.source.network.AndroidCookieJar]; it never enters app storage,
 * backups, diagnostics, or logs.
 */
object PatreonSession {
    private const val ORIGIN = "https://www.patreon.com"
    private const val COOKIE_NAME = "session_id"
    private const val ONE_YEAR_SECONDS = 31_536_000
    private const val NETSCAPE_NAME_FIELD = 5
    private const val NETSCAPE_VALUE_FIELD = 6

    /** What a paste held, without ever echoing the value itself back to the UI. */
    sealed interface Paste {
        data class Found(
            val sessionId: String,
        ) : Paste

        /** Exported cookies that lack `session_id`: the browser was not signed in to Patreon. */
        data object CookiesWithoutSession : Paste

        data object Empty : Paste

        data object Unrecognized : Paste
    }

    /**
     * Epoch millis of the last [store] in this process. Lets a story whose last Patreon check
     * failed for sign-in reasons offer a fresh sync once the user has pasted a new session.
     */
    @Volatile
    var storedAt: Long = 0L
        private set

    fun isPresent(): Boolean = currentValue() != null

    /** The stored value, used only to restore it when a replacement is rejected. Never display it. */
    internal fun currentValue(): String? = runCatching { cookieValue(CookieManager.getInstance().getCookie(ORIGIN)) }.getOrNull()

    fun store(sessionId: String) {
        restore(sessionId)
        storedAt = System.currentTimeMillis()
    }

    /** Puts back a session that was replaced by one Patreon then refused; not a new sign-in. */
    internal fun restore(sessionId: String) {
        val cookies = CookieManager.getInstance()
        cookies.setCookie(ORIGIN, "$COOKIE_NAME=$sessionId; Domain=.patreon.com; Path=/; Secure; HttpOnly; Max-Age=$ONE_YEAR_SECONDS")
        cookies.flush()
    }

    fun clear() {
        val cookies = CookieManager.getInstance()
        cookies.setCookie(ORIGIN, "$COOKIE_NAME=; Domain=.patreon.com; Path=/; Max-Age=0")
        cookies.setCookie(ORIGIN, "$COOKIE_NAME=; Path=/; Max-Age=0")
        cookies.flush()
    }

    /**
     * Finds `session_id` in whatever the user copied: Cookie-Editor's Export (JSON, Netscape, or
     * header string), a `session_id=...` pair, or the bare value.
     */
    internal fun parsePaste(pasted: String?): Paste {
        val text = pasted?.trim().orEmpty()
        if (text.isEmpty()) return Paste.Empty
        fromJson(text)?.let { return it }
        fromNetscape(text)?.let { return it }
        Regex("""(?:^|[;\s])$COOKIE_NAME=([^;\s]+)""").find(text)?.let { match ->
            return cleanValue(match.groupValues[1])?.let(Paste::Found) ?: Paste.Unrecognized
        }
        if (text.contains('=') && Regex("""^[^=\s;]+=[^=]""").containsMatchIn(text)) return Paste.CookiesWithoutSession
        return cleanValue(text)?.let(Paste::Found) ?: Paste.Unrecognized
    }

    private fun fromJson(text: String): Paste? {
        if (!text.startsWith('[') && !text.startsWith('{')) return null
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        val cookies =
            when {
                root.isJsonArray -> root.asJsonArray.filter(JsonElement::isJsonObject).map { it.asJsonObject }
                root.isJsonObject -> listOf(root.asJsonObject)
                else -> return null
            }
        val value =
            cookies.firstNotNullOfOrNull { cookie ->
                val name = cookie.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                when {
                    name == COOKIE_NAME -> cookie.get("value")?.takeIf { it.isJsonPrimitive }?.asString
                    name == null -> cookie.get(COOKIE_NAME)?.takeIf { it.isJsonPrimitive }?.asString
                    else -> null
                }
            }
        return value?.let(::cleanValue)?.let(Paste::Found) ?: Paste.CookiesWithoutSession
    }

    private fun fromNetscape(text: String): Paste? {
        val rows = text.lines().map { it.split('\t') }.filter { it.size > NETSCAPE_VALUE_FIELD }
        if (rows.isEmpty()) return null
        val value = rows.firstOrNull { it[NETSCAPE_NAME_FIELD] == COOKIE_NAME }?.get(NETSCAPE_VALUE_FIELD)
        return value?.let(::cleanValue)?.let(Paste::Found) ?: Paste.CookiesWithoutSession
    }

    private fun cleanValue(raw: String): String? =
        raw
            .trim()
            .trim('"')
            .trim()
            .takeIf { value -> value.isNotEmpty() && value.none { it.isWhitespace() || it == ';' || it == ',' || it == '"' } }

    internal fun cookieValue(cookieHeader: String?): String? =
        cookieHeader
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.removePrefix("$COOKIE_NAME=")
            ?.takeIf { it.isNotEmpty() }
}
