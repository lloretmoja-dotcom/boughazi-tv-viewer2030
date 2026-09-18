package tv.boughazi.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda la sesión del usuario en el propio dispositivo, para que
 * no tenga que volver a escribir el correo y la contraseña (ni el
 * código) cada vez que abre la app.
 */
data class UserSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    var hasLinkedCode: Boolean
)

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("boughazi_tv_session", Context.MODE_PRIVATE)

    fun save(session: UserSession) {
        prefs.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .putBoolean("has_linked_code", session.hasLinkedCode)
            .apply()
    }

    fun load(): UserSession? {
        val token = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val userId = prefs.getString("user_id", null) ?: return null
        val email = prefs.getString("email", null) ?: return null
        val linked = prefs.getBoolean("has_linked_code", false)
        return UserSession(token, refresh, userId, email, linked)
    }

    fun markCodeLinked() {
        prefs.edit().putBoolean("has_linked_code", true).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
