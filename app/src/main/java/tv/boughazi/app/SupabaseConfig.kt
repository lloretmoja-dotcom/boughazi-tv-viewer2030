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

    // Página donde la persona escribe su contraseña nueva (o ve el aviso
    // de "cuenta confirmada"), a la que Supabase manda a la gente desde
    // los correos de confirmación de cuenta y de "olvidé mi contraseña".
    // Está en el mismo sitio donde ya vive el panel de Admin.
    const val RESET_PASSWORD_URL = "https://internacional1.netlify.app/reset-password.html"
}
