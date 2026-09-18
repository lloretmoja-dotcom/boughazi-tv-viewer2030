package tv.boughazi.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Cargador de logos muy sencillo (sin librerías externas): descarga
 * la imagen una vez y la guarda en memoria para no volver a
 * descargarla cada vez que aparece el mismo canal.
 */
object ImageLoader {
    private val cache = HashMap<String, Bitmap>()

    fun load(scope: CoroutineScope, url: String?, into: ImageView) {
        if (url.isNullOrBlank()) {
            into.setImageDrawable(null)
            return
        }
        cache[url]?.let {
            into.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeStream(URL(url).openStream())
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                cache[url] = bitmap
                into.setImageBitmap(bitmap)
            }
        }
    }
}
