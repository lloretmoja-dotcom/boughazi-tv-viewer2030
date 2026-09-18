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
 * Resultado de intentar activar un código. A diferencia de antes, si
 * falla guardamos el motivo REAL (código HTTP + texto que responde
 * Supabase) en vez de solo decir "false" — así se puede ver en
 * pantalla qué está pasando de verdad, en lugar de adivinar.
 */
sealed class RedeemResult {
    object Success : RedeemResult()
    data class Failure(val httpStatus: Int, val detail: String) : RedeemResult()
}

private data class PatchResult(val status: Int, val array: JSONArray?, val rawBody: String)

class CodeRepository {

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

    suspend fun redeemCode(session: UserSession, code: String): RedeemResult =
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
            val claimResult = patchJson(claimUrl, session.accessToken, claimBody)

            if (claimResult.status !in 200..299) {
                return@withContext RedeemResult.Failure(
                    claimResult.status,
                    describeError(claimResult.rawBody)
                )
            }
            if (claimResult.array == null || claimResult.array.length() == 0) {
                // La petición fue "correcta" (200) pero no devolvió ninguna
                // fila: o el código ya no cumple el filtro (ya usado / no
                // existe), o una política de RLS está bloqueando la
                // lectura de la fila tras actualizarla.
                return@withContext RedeemResult.Failure(
                    claimResult.status,
                    "No se actualizó ninguna fila (respuesta vacía: '${claimResult.rawBody}')"
                )
            }

            val viewerUrl = URL("${SupabaseConfig.URL}/rest/v1/bt_viewers?id=eq.${session.userId}")
            val viewerBody = JSONObject().apply {
                put("linked_code", code.trim().uppercase())
                put("linked_at", nowIso)
            }
            patchJson(viewerUrl, session.accessToken, viewerBody)
            RedeemResult.Success
        }

    private fun describeError(rawBody: String): String {
        return try {
            val obj = JSONObject(rawBody)
            obj.optString("message", obj.optString("msg", rawBody)).ifBlank { rawBody }
        } catch (e: Exception) {
            rawBody
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun patchJson(url: URL, accessToken: String, body: JSONObject): PatchResult {
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

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            null
        }
        return PatchResult(status, array, text)
    }
}
