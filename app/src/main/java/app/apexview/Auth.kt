package app.apexview

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Keeps the F1 TV subscription ("ascendon") token between launches, in a store of its own that
 * Android's backups and phone-to-phone transfers leave out (see res/xml), so the sign-in never
 * leaves this phone. Up to 0.53 it lived with the settings; it's moved on first read.
 */
object TokenStore {
    private fun prefs(context: Context) = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private fun legacy(context: Context) = context.getSharedPreferences("f1mv", Context.MODE_PRIVATE)

    fun load(context: Context): String? = prefs(context).getString(KEY, null)
        ?: legacy(context).getString(KEY, null)?.also { token ->
            save(context, token)
            legacy(context).edit().remove(KEY).apply()
        }

    fun save(context: Context, token: String) = prefs(context).edit().putString(KEY, token).apply()

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
        legacy(context).edit().remove(KEY).apply()
    }

    private const val KEY = "ascendon"
}

/**
 * After sign-in, account.formula1.com sets a `login-session` cookie whose JSON value holds
 * `data.subscriptionToken`. Same approach as eXViewer's auth.ts.
 */
object LoginCookie {
    const val LOGIN_URL = "https://account.formula1.com/#/en/login"

    fun readToken(): String? {
        val cookies = CookieManager.getInstance().getCookie("https://account.formula1.com") ?: return null
        val raw = cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("login-session=") }
            ?.substringAfter("=")
            ?: return null

        // The value is JSON, either raw or URL-encoded.
        for (candidate in listOf(raw, URLDecoder.decode(raw, "UTF-8"))) {
            try {
                val token = JSONObject(candidate).optJSONObject("data")?.optString("subscriptionToken")
                if (!token.isNullOrEmpty()) return token
            } catch (_: JSONException) {
                // Try the next encoding.
            }
        }
        return null
    }

    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
