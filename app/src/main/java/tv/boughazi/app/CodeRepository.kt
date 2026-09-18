package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Comprueba y "reclama" el código de activación que el usuario
 * introduce la primera vez que abre la app. Un código solo se
 * puede usar una vez (lo controla la propia base de datos, no esta
 * app, así que no se puede hacer trampa).
 */
class CodeRepository {

    /** Comprueba si esta cuenta ya vinculó un código antes (por ejemplo,
     *  desde otro dispositivo), para no volver a pedírselo. */
    suspend fun checkAlreadyLinked(session: UserSession): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "${SupabaseConfig.URL}/rest/v1/bt_viewers?id=eq.${session.userId}&select=linked_code"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "[]"
            val arr = JSONArray(text)
            if (arr.length() == 0) return@withContext false
            val row = arr.getJSONObject(0)
            !row.isNull("linked_code")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun redeemCode(session: UserSession, code: String): Boolean =
        withContext(Dispatchers.IO) {
            val nowIso = isoNow()
            val claimUrl = URL(
                "${SupabaseConfig.URL}/rest/v1/bt_access_codes" +
                    "?code=eq.${code.trim().uppercase()}&used_by_email=is.null&active=eq.true"
            )
            val claimBody = JSONObject().apply {
                put("used_by_email", session.email)
                put("used_at", nowIso)
            }
            val claimResponse = patchJson(claimUrl, session.accessToken, claimBody)
            if (claimResponse !is JSONArray || claimResponse.length() == 0) {
                return@withContext false
            }

            // Guardamos también en la ficha del propio espectador que ya
            // vinculó su código (por si se quiere consultar más adelante).
            val viewerUrl = URL("${SupabaseConfig.URL}/rest/v1/bt_viewers?id=eq.${session.userId}")
            val viewerBody = JSONObject().apply {
                put("linked_code", code.trim().uppercase())
                put("linked_at", nowIso)
            }
            patchJson(viewerUrl, session.accessToken, viewerBody)
            true
        }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun patchJson(url: URL, accessToken: String, body: JSONObject): Any {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.setRequestProperty("Prefer", "return=representation")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "[]"
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
