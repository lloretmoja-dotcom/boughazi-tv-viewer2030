package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Habla directamente con la API de autenticación de Supabase (por
 * eso no hace falta ninguna librería extra). Se encarga de:
 *  - iniciar sesión / registrarse con Gmail y contraseña
 *  - "olvidé mi contraseña" (envía un correo real al Gmail)
 * Ya NO existe ninguna comprobación de pago/suscripción: con tener
 * la cuenta creada es suficiente para entrar (después toca el paso
 * del código de activación, que va aparte, ver CodeRepository).
 */
sealed class AuthResult {
    data class Success(val session: UserSession) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}

class AuthRepository {

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.URL}/auth/v1/token?grant_type=password")
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val json = postJson(url, body)
            if (json.has("access_token")) {
                AuthResult.Success(toSession(json))
            } else {
                AuthResult.Failure(errorMessage(json))
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "No se pudo conectar. Revisa tu conexión a internet.")
        }
    }

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.URL}/auth/v1/signup")
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val json = postJson(url, body)
            if (json.has("access_token")) {
                AuthResult.Success(toSession(json))
            } else if (json.has("id")) {
                // Cuenta creada pero requiere confirmar el correo antes de dar sesión.
                AuthResult.Failure("Cuenta creada. Revisa tu Gmail para confirmar la cuenta y luego inicia sesión.")
            } else {
                AuthResult.Failure(errorMessage(json))
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "No se pudo conectar. Revisa tu conexión a internet.")
        }
    }

    suspend fun sendPasswordReset(email: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.URL}/auth/v1/recover")
            val body = JSONObject().apply { put("email", email) }
            postJson(url, body)
            // Supabase siempre responde con éxito aquí (por seguridad, no
            // revela si el correo existe o no), así que damos el aviso normal.
            AuthResult.Failure("Si esa cuenta existe, te hemos enviado un correo a tu Gmail para restablecer la contraseña.")
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "No se pudo conectar. Revisa tu conexión a internet.")
        }
    }

    private fun toSession(json: JSONObject): UserSession {
        val user = json.getJSONObject("user")
        return UserSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = user.getString("id"),
            email = user.optString("email", ""),
            hasLinkedCode = false
        )
    }

    private fun errorMessage(json: JSONObject): String {
        return json.optString("error_description", json.optString("msg", "Correo o contraseña incorrectos."))
    }

    private fun postJson(url: URL, body: JSONObject): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
