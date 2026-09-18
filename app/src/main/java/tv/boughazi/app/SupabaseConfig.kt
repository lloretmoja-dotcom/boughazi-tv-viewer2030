package tv.boughazi.app

/**
 * Datos de conexión a Supabase. Es el mismo proyecto que usa la web
 * y el panel de administración. La "anon key" es pública y segura
 * de incluir aquí (la seguridad real la da la base de datos, no
 * esta clave) — aun así, cámbiala por la tuya real antes de publicar.
 */
object SupabaseConfig {
    const val URL = "https://oansihwqjcfjackfhgbd.supabase.co"
    const val ANON_KEY = "sb_publishable_tn7CCQaV5otb7w5nKVdGgQ_uhOQCvwa"
}
