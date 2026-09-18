package tv.boughazi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.launch

/**
 * Pantalla única de Boughazi-TV. Pasa por tres estados, uno visible
 * cada vez: iniciar sesión -> código de activación (solo la primera
 * vez) -> televisión en directo.
 */
class MainActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()
    private val codeRepository = CodeRepository()
    private val channelRepository = ChannelRepository()
    private val presenceRepository = PresenceRepository()
    private lateinit var sessionManager: SessionManager

    private var session: UserSession? = null
    private var allChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentIndex = -1

    private var exoPlayer: ExoPlayer? = null
    private val numberBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var osdHideRunnable: Runnable? = null
    private var numberEntryRunnable: Runnable? = null
    private var presenceRunnable: Runnable? = null

    // ---- vistas ----
    private lateinit var loginSection: View
    private lateinit var codeSection: View
    private lateinit var mainSection: View
    private lateinit var playerView: PlayerView
    private lateinit var categoriesList: RecyclerView
    private lateinit var channelsList: RecyclerView
    private lateinit var osdContainer: View
    private lateinit var osdNumber: TextView
    private lateinit var osdName: TextView
    private lateinit var loadingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sessionManager = SessionManager(this)
        MobileAds.initialize(this)

        loginSection = findViewById(R.id.loginSection)
        codeSection = findViewById(R.id.codeSection)
        mainSection = findViewById(R.id.mainSection)
        playerView = findViewById(R.id.playerView)
        categoriesList = findViewById(R.id.categoriesList)
        channelsList = findViewById(R.id.channelsList)
        osdContainer = findViewById(R.id.osdContainer)
        osdNumber = findViewById(R.id.osdNumber)
        osdName = findViewById(R.id.osdName)
        loadingText = findViewById(R.id.loadingText)

        setupLoginSection()
        setupCodeSection()
        setupAdBanner()

        val saved = sessionManager.load()
        if (saved == null) {
            showOnly(loginSection)
        } else {
            session = saved
            if (saved.hasLinkedCode) {
                enterMainSection()
            } else {
                lifecycleScope.launch {
                    val already = codeRepository.checkAlreadyLinked(saved)
                    if (already) {
                        saved.hasLinkedCode = true
                        sessionManager.markCodeLinked()
                        enterMainSection()
                    } else {
                        showOnly(codeSection)
                    }
                }
            }
        }
    }

    /* ============================================================ */
    /* LOGIN                                                          */
    /* ============================================================ */

    private fun setupLoginSection() {
        val emailField = findViewById<EditText>(R.id.loginEmail)
        val passwordField = findViewById<EditText>(R.id.loginPassword)
        val errorText = findViewById<TextView>(R.id.loginError)

        findViewById<View>(R.id.loginSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.isEmpty()) {
                showError(errorText, "Escribe tu correo y tu contraseña.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                when (val result = authRepository.signIn(email, password)) {
                    is AuthResult.Success -> onAuthSuccess(result.session)
                    is AuthResult.Failure -> showError(errorText, result.message)
                }
            }
        }

        findViewById<View>(R.id.signUpBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.length < 6) {
                showError(errorText, "Escribe un correo y una contraseña de al menos 6 caracteres.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                when (val result = authRepository.signUp(email, password)) {
                    is AuthResult.Success -> onAuthSuccess(result.session)
                    is AuthResult.Failure -> showError(errorText, result.message)
                }
            }
        }

        findViewById<View>(R.id.forgotPasswordBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            if (email.isEmpty()) {
                showError(errorText, "Escribe tu correo arriba y vuelve a tocar aquí.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val result = authRepository.sendPasswordReset(email)
                if (result is AuthResult.Failure) showError(errorText, result.message)
            }
        }
    }

    private fun onAuthSuccess(newSession: UserSession) {
        session = newSession
        sessionManager.save(newSession)
        lifecycleScope.launch {
            val already = codeRepository.checkAlreadyLinked(newSession)
            if (already) {
                newSession.hasLinkedCode = true
                sessionManager.markCodeLinked()
                enterMainSection()
            } else {
                showOnly(codeSection)
            }
        }
    }

    /* ============================================================ */
    /* CÓDIGO DE ACTIVACIÓN                                           */
    /* ============================================================ */

    private fun setupCodeSection() {
        val codeInput = findViewById<EditText>(R.id.codeInput)
        val errorText = findViewById<TextView>(R.id.codeError)

        findViewById<View>(R.id.codeSubmitBtn).setOnClickListener {
            val code = codeInput.text.toString().trim()
            val currentSession = session ?: return@setOnClickListener
            if (code.isEmpty()) {
                showError(errorText, "Escribe el código que te han dado.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                attemptRedeem(currentSession, code, errorText, allowRetry = true)
            }
        }
    }

    /**
     * Intenta activar el código. Si Supabase dice que la sesión ha
     * caducado (pasa cuando ha pasado más de una hora desde que se
     * inició sesión, por ejemplo tras estar probando otras cosas),
     * renueva la sesión automáticamente y lo vuelve a intentar una
     * vez. Si falla por otro motivo, muestra el detalle técnico real
     * en pantalla en vez de un mensaje genérico.
     */
    private suspend fun attemptRedeem(
        currentSession: UserSession,
        code: String,
        errorText: TextView,
        allowRetry: Boolean
    ) {
        when (val result = codeRepository.redeemCode(currentSession, code)) {
            is RedeemResult.Success -> {
                currentSession.hasLinkedCode = true
                sessionManager.markCodeLinked()
                enterMainSection()
            }
            is RedeemResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            attemptRedeem(renewed, code, errorText, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            showError(
                                errorText,
                                "Tu sesión caducó y no se pudo renovar. Cierra la app, entra otra vez con tu Gmail y prueba el código de nuevo."
                            )
                        }
                    }
                } else {
                    showError(
                        errorText,
                        "Ese código no es válido o ya se ha usado. (Detalle: HTTP ${result.httpStatus} — ${result.detail})"
                    )
                }
            }
        }
    }

    /* ============================================================ */
    /* PANTALLA PRINCIPAL / TELEVISIÓN                                */
    /* ============================================================ */

    private fun enterMainSection() {
        showOnly(mainSection)
        loadingText.visibility = View.VISIBLE
        val currentSession = session ?: return

        exoPlayer = ExoPlayer.Builder(this).build().also { playerView.player = it }

        categoriesList.layoutManager = LinearLayoutManager(this)
        channelsList.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            loadingText.text = "Cargando canales…"
            loadChannels(currentSession, allowRetry = true)
        }
    }

    /**
     * Igual que con el código de activación: si la sesión había
     * caducado (por ejemplo, la tele lleva rato encendida con la app
     * abierta), la renueva sola y lo vuelve a intentar una vez. Si
     * falla por otro motivo, muestra el detalle técnico real en vez
     * de decir sin más "no hay canales".
     */
    private suspend fun loadChannels(currentSession: UserSession, allowRetry: Boolean) {
        when (val result = channelRepository.fetchChannels(currentSession)) {
            is ChannelsResult.Success -> {
                allChannels = result.channels
                categories = allChannels.map { it.category }.distinct()
                loadingText.visibility = View.GONE

                if (allChannels.isEmpty()) {
                    loadingText.text = "Todavía no hay canales disponibles."
                    loadingText.visibility = View.VISIBLE
                    return
                }

                categoriesList.adapter = RowAdapter(
                    categories.map { RowItem(title = it) }
                ) { position -> onCategorySelected(categories[position]) }

                playChannel(0)
                startPresenceHeartbeat()
            }
            is ChannelsResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            loadChannels(renewed, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            loadingText.text = "Tu sesión ha caducado. Sal de la app y vuelve a entrar con tu Gmail."
                            loadingText.visibility = View.VISIBLE
                        }
                    }
                } else {
                    loadingText.text =
                        "No se pudieron cargar los canales. (Detalle: HTTP ${result.httpStatus} — ${result.detail})"
                    loadingText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun onCategorySelected(category: String) {
        val channelsInCategory = allChannels.filter { it.category == category }
        channelsList.adapter = RowAdapter(
            channelsInCategory.map { RowItem(title = "${it.channelNumber ?: "-"}  ${it.name}") }
        ) { position ->
            val chosen = channelsInCategory[position]
            val flatIndex = allChannels.indexOfFirst { it.id == chosen.id }
            if (flatIndex >= 0) playChannel(flatIndex)
            hideChannelBrowser()
        }
        channelsList.visibility = View.VISIBLE
        channelsList.requestFocus()
    }

    private fun showChannelBrowser() {
        categoriesList.visibility = View.VISIBLE
        categoriesList.requestFocus()
    }

    private fun hideChannelBrowser() {
        categoriesList.visibility = View.GONE
        channelsList.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun playChannel(index: Int) {
        if (index !in allChannels.indices) return
        currentIndex = index
        val channel = allChannels[index]
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            prepare()
            playWhenReady = true
        }
        showOsd(channel)
        updatePresenceChannel(channel.id)
    }

    private fun zapNext() = playChannel((currentIndex + 1).let { if (it >= allChannels.size) 0 else it })
    private fun zapPrevious() = playChannel((currentIndex - 1).let { if (it < 0) allChannels.size - 1 else it })

    private fun showOsd(channel: Channel) {
        osdNumber.text = (channel.channelNumber ?: "").toString()
        osdName.text = channel.name
        ImageLoader.load(lifecycleScope, channel.logoUrl, findViewById(R.id.osdLogo))
        osdContainer.visibility = View.VISIBLE
        osdHideRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { osdContainer.visibility = View.GONE }
        osdHideRunnable = runnable
        handler.postDelayed(runnable, 3000)
    }

    /* ---- mando a distancia ---- */

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mainSection.visibility != View.VISIBLE) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zapNext(); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapPrevious(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                if (categoriesList.visibility == View.VISIBLE) hideChannelBrowser() else showChannelBrowser()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (categoriesList.visibility == View.VISIBLE || channelsList.visibility == View.VISIBLE) {
                    hideChannelBrowser()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (categoriesList.visibility != View.VISIBLE && channelsList.visibility != View.VISIBLE) {
                    showChannelBrowser()
                    return true
                }
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_0
                onDigitEntered(digit)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onDigitEntered(digit: Int) {
        numberBuffer.append(digit)
        osdNumber.text = numberBuffer.toString()
        osdName.text = "Introduce el número de canal…"
        osdContainer.visibility = View.VISIBLE

        numberEntryRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { confirmNumberEntry() }
        numberEntryRunnable = runnable
        handler.postDelayed(runnable, 1500)
    }

    private fun confirmNumberEntry() {
        val typed = numberBuffer.toString().toIntOrNull()
        numberBuffer.clear()
        if (typed == null) return
        val index = allChannels.indexOfFirst { it.channelNumber == typed }
        if (index >= 0) {
            playChannel(index)
        } else {
            osdName.text = "Canal $typed no encontrado"
            handler.postDelayed({ osdContainer.visibility = View.GONE }, 1500)
        }
    }

    /* ---- presencia (para el contador "viendo ahora" del admin) ---- */

    private fun startPresenceHeartbeat() {
        val runnable = object : Runnable {
            override fun run() {
                val currentSession = session ?: return
                val channelId = allChannels.getOrNull(currentIndex)?.id
                lifecycleScope.launch { presenceRepository.ping(currentSession, channelId) }
                handler.postDelayed(this, 20000)
            }
        }
        presenceRunnable = runnable
        handler.post(runnable)
    }

    private fun updatePresenceChannel(channelId: String) {
        val currentSession = session ?: return
        lifecycleScope.launch { presenceRepository.ping(currentSession, channelId) }
    }

    /* ---- publicidad ----
       Banner de prueba de Google (no genera dinero). Cuando tengas tu
       propio "Ad Unit ID" de AdMob, cámbialo en la constante de abajo. */
    private fun setupAdBanner() {
        val testAdUnitId = "ca-app-pub-3940256099942544/6300978111"
        val adView = AdView(this)
        adView.adUnitId = testAdUnitId
        adView.setAdSize(AdSize.BANNER)
        findViewById<android.widget.FrameLayout>(R.id.adContainer).addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun showOnly(view: View) {
        loginSection.visibility = if (view == loginSection) View.VISIBLE else View.GONE
        codeSection.visibility = if (view == codeSection) View.VISIBLE else View.GONE
        mainSection.visibility = if (view == mainSection) View.VISIBLE else View.GONE
        // En la tele, con mando, hay que decirle explícitamente a Android
        // dónde poner el foco al entrar en cada pantalla — si no, a veces
        // el mando se queda "perdido" sin saber desde dónde navegar.
        view.post {
            when (view) {
                loginSection -> findViewById<View>(R.id.loginEmail)?.requestFocus()
                codeSection -> findViewById<View>(R.id.codeInput)?.requestFocus()
            }
        }
    }

    private fun showError(textView: TextView, message: String) {
        textView.text = message
        textView.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        presenceRunnable?.let { handler.removeCallbacks(it) }
        osdHideRunnable?.let { handler.removeCallbacks(it) }
        numberEntryRunnable?.let { handler.removeCallbacks(it) }
        exoPlayer?.release()
    }
}
