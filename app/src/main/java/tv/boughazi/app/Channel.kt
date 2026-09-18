package tv.boughazi.app

/**
 * Un canal de televisión. Coincide con las columnas de la tabla
 * "bt_channels" en Supabase.
 */
data class Channel(
    val id: String,
    val channelNumber: Int?,
    val name: String,
    val category: String,
    val logoUrl: String?,
    val streamUrl: String
)
