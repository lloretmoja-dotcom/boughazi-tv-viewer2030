package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cada cierto tiempo, mientras alguien está viendo la tele, avisa a
 * Supabase de "sigo aquí, viendo este canal". Así el panel de
 * administración puede contar cuánta gente está viendo en directo
 * en cada momento (se considera "conectado" quien avisó en el
 * último minuto).
 */
class PresenceRepository {

    suspend fun ping(session: UserSession, channelId: String?): Unit = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.URL}/rest/v1/bt_presence?on_conflict=viewer_id")
            val body = JSONObject().apply {
                put("viewer_id", session.userId)
                put("channel_id", channelId)
                put("last_ping", isoNow())
            }
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.inputStream?.close()
        } catch (e: Exception) {
            // Si falla un aviso de presencia no pasa nada grave: se
            // reintentará en el siguiente ciclo. No interrumpimos al usuario.
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
