package com.sleektv.data

import android.content.Context
import java.util.UUID

/** Saved login and cached layout, kept in SharedPreferences. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("sleektv", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("server", null)
        set(value) = prefs.edit().putString("server", value).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    var userId: String?
        get() = prefs.getString("userId", null)
        set(value) = prefs.edit().putString("userId", value).apply()

    var userName: String?
        get() = prefs.getString("userName", null)
        set(value) = prefs.edit().putString("userName", value).apply()

    /** Raw JSON from the plugin, so the app looks right even before the server answers. */
    var cachedLayoutJson: String?
        get() = prefs.getString("layout", null)
        set(value) = prefs.edit().putString("layout", value).apply()

    /** Downloaded @import theme CSS, plus which URLs it came from, so themes work offline. */
    var importsKey: String?
        get() = prefs.getString("importsKey", null)
        set(value) = prefs.edit().putString("importsKey", value).apply()

    /** The fully worked-out layout from last time, so the app starts without re-reading themes. */
    var finalLayoutJson: String?
        get() = prefs.getString("finalLayout", null)
        set(value) = prefs.edit().putString("finalLayout", value).apply()

    /** When the @import themes were last downloaded (ms since epoch). */
    var importsFetchedAt: Long
        get() = prefs.getLong("importsFetchedAt", 0L)
        set(value) = prefs.edit().putLong("importsFetchedAt", value).apply()

    var importsCss: String
        get() = prefs.getString("importsCss", null).orEmpty()
        set(value) = prefs.edit().putString("importsCss", value).apply()

    val deviceId: String by lazy {
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    val isLoggedIn: Boolean
        get() = !serverUrl.isNullOrBlank() && !token.isNullOrBlank() && !userId.isNullOrBlank()

    fun logout() {
        token = null
        userId = null
        userName = null
    }
}
