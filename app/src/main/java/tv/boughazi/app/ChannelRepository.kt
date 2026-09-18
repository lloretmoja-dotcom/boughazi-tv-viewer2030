package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga la lista de canales de Supabase. Se llama una sola vez
 * al entrar (o al tirar hacia abajo para refrescar) y luego todo
 * el "zapping" ocurre sobre la lista ya guardada en memoria — así
 * es instantáneo, como pide el estilo TiviMate.
 */
class ChannelRepository {

    suspend fun fetchChannels(session: UserSession): List<Channel> = withContext(Dispatchers.IO) {
        val url = URL(
            "${SupabaseConfig.URL}/rest/v1/bt_channels" +
                "?select=id,channel_number,name,category,logo_url,stream_url,is_broken" +
                "&is_broken=eq.false" +
                "&order=channel_number.asc.nullslast"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "[]"

        val result = mutableListOf<Channel>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    Channel(
                        id = o.getString("id"),
                        channelNumber = if (o.isNull("channel_number")) null else o.getInt("channel_number"),
                        name = o.optString("name", ""),
                        category = o.optString("category", "General"),
                        logoUrl = if (o.isNull("logo_url")) null else o.getString("logo_url"),
                        streamUrl = o.optString("stream_url", "")
                    )
                )
            }
        } catch (e: Exception) {
            // Si algo falla, devolvemos lo que llevemos (lista vacía en el peor caso).
        }
        result
    }
}
