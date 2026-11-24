package kz.safetyhack.audiotester

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*
import java.io.File
import java.net.URI
import java.util.Locale

class MainActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    private lateinit var source: Spinner
    private lateinit var endpoint: EditText
    private lateinit var consent: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var replay: Button
    private lateinit var delete: Button
    private lateinit var status: TextView
    private lateinit var meterText: TextView
    private lateinit var network: TextView
    private lateinit var results: TextView
    private lateinit var meter: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pendingPermissionStart = false
    private var starting = false
    private var startTime = 0L
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 150) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xffffffff.toInt()
        window.navigationBarColor = 0xffffffff.toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
            setBackgroundColor(0xffffffff.toInt())
        }
        body.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(24), dp(20) + insets.systemWindowInsetTop, dp(24), dp(32) + insets.systemWindowInsetBottom)
            insets
        }
        fun label(text: String, size: Float = 18f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(0xff202124.toInt())
            setPadding(0, dp(12), 0, dp(8)); body.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 16f; isAllCaps = false; minHeight = dp(56)
            setTextColor(0xff0b57d0.toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xffd3e3fd.toInt()); cornerRadius = dp(28).toFloat() }
            body.addView(this, LinearLayout.LayoutParams(-1, -2)); setOnClickListener { action() }
        }
        label("Проверка микрофона", 28f)
        label("Запишите до 30 секунд и прослушайте запись: слышны ли оба собеседника? Во время телефонного звонка Android или Vivo может блокировать запись звука.")
        status = label("Всё готово", 20f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 90; contentDescription = "Уровень звука микрофона"; body.addView(this, LinearLayout.LayoutParams(-1, dp(16)))
        }
        meterText = label("Уровень: −90 дБFS · 0 из 30 секунд")
        label("Режим микрофона")
        source = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Обычный микрофон", "Для распознавания речи", "Для голосовой связи"))
            minimumHeight = dp(48); body.addView(this)
        }
        start = button("Начать запись на 30 секунд") { startRequested() }
        stop = button("Остановить запись") {
            if (CaptureState.current.active) startService(Intent(this, CaptureService::class.java).setAction("STOP"))
        }
        replay = button(getString(R.string.mic_replay)) { play() }
        delete = button("Удалить запись") { deleteRecording() }
        label("Передача на сервер — по желанию", 20f)
        label("Без передачи запись остаётся на телефоне. Включайте передачу только с согласия всех участников. Используйте защищённое соединение wss://.")
        label("Адрес сервера")
        endpoint = EditText(this).apply {
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getPreferences(0).getString("endpoint", "wss://your-server.example/ws/audio"))
            body.addView(this)
        }
        consent = CheckBox(this).apply {
            text = "Я согласен передать звук этой записи на указанный сервер"
            textSize = 18f
            isSaveEnabled = false; minHeight = dp(48); body.addView(this)
        }
        network = label("Запись только на телефоне")
        label("Результаты проверки", 20f)
        results = label("Здесь появятся распознанная речь и оценка риска.").apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        if (!CaptureState.current.active && CaptureState.current.wav == null) {
            val previous = File(filesDir, "latest-test.wav")
            if (previous.exists() && previous.length() > 44) {
                // An interrupted process can leave an unfinished header: only offer finalized files.
                val valid = runCatching { java.io.RandomAccessFile(previous, "r").use { it.readInt() == 0x52494646 } }.getOrDefault(false)
                if (valid) CaptureState.update { it.copy(wav = previous.absolutePath) }
            }
        }
    }

    private fun startRequested() {
        if (RtcActivity.audioSessionOpen) {
            Toast.makeText(this, "Сначала завершите звонок в приложении", Toast.LENGTH_LONG).show(); return
        }
        if (CaptureState.current.active || starting) return
        if (consent.isChecked) {
            val valid = runCatching {
                val uri = URI(endpoint.text.toString().trim())
                uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
            }.getOrDefault(false)
            if (!valid) {
                Toast.makeText(this, "Проверьте адрес сервера: нужен корректный wss:// адрес", Toast.LENGTH_LONG).show(); return
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionStart = true
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(permissions.toTypedArray(), 10); return
        }
        releasePlayer()
        getPreferences(0).edit().putString("endpoint", endpoint.text.toString().trim()).apply()
        val selected = intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION)[source.selectedItemPosition]
        val intent = Intent(this, CaptureService::class.java).putExtra("source", selected)
            .putExtra("consent", consent.isChecked).putExtra("endpoint", endpoint.text.toString().trim())
        starting = true; startTime = SystemClock.elapsedRealtime()
        try { startForegroundService(intent) }
        catch (_: Exception) {
            starting = false
            CaptureState.update { it.copy(status = "Не удалось начать запись. Откройте приложение и попробуйте снова.") }
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && pendingPermissionStart) {
            pendingPermissionStart = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRequested()
            else CaptureState.update { it.copy(status = "Нет доступа к микрофону. Разрешите его в настройках приложения и попробуйте снова.") }
        }
    }

    private fun play() {
        if (player != null) { releasePlayer(); render(); return }
        val path = CaptureState.current.wav ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { releasePlayer(); render() }
                setOnErrorListener { _, _, _ -> releasePlayer(); Toast.makeText(this@MainActivity, "Не удалось воспроизвести запись", Toast.LENGTH_LONG).show(); true }
                prepare(); start()
            }
        } catch (_: Exception) { releasePlayer(); Toast.makeText(this, "Не удалось открыть запись", Toast.LENGTH_LONG).show() }
        render()
    }
    private fun deleteRecording() {
        if (CaptureState.current.active || starting) return
        releasePlayer()
        val recording = File(filesDir, "latest-test.wav")
        val deleted = runCatching { !recording.exists() || recording.delete() }.getOrDefault(false)
        if (deleted) {
            CaptureState.update { it.copy(wav = null, status = getString(R.string.mic_deleted)) }
        } else {
            Toast.makeText(this, getString(R.string.mic_delete_failed), Toast.LENGTH_LONG).show()
        }
        render()
    }
    private fun releasePlayer() { player?.release(); player = null }
    private fun render() {
        val state = CaptureState.current
        if (state.active || SystemClock.elapsedRealtime() - startTime > 2500) starting = false
        val busy = state.active || starting
        start.isEnabled = !busy; stop.isEnabled = state.active
        replay.isEnabled = !busy && state.wav != null
        delete.isEnabled = !busy && (state.wav != null || File(filesDir, "latest-test.wav").exists())
        replay.text = if (player != null) getString(R.string.mic_stop_replay) else getString(R.string.mic_replay)
        source.isEnabled = !busy; endpoint.isEnabled = !busy; consent.isEnabled = !busy
        status.text = if (starting) getString(R.string.mic_preparing) else state.status
        meter.progress = (state.db + 90).toInt().coerceIn(0, 90)
        meterText.text = String.format(Locale.forLanguageTag("ru-RU"), getString(R.string.mic_level), state.db, state.seconds)
        if (state.active && state.db < -55) {
            meterText.append("\n" + getString(R.string.mic_silence_hint))
        }
        network.text = listOf(state.network, state.serverMode).filter { it.isNotBlank() }.joinToString("\n")
        val noTranscript = if (state.diagnostic) getString(R.string.mic_diagnostic_no_speech) else getString(R.string.mic_no_transcript)
        val noRisk = if (state.diagnostic) getString(R.string.mic_diagnostic_no_risk) else getString(R.string.mic_no_risk)
        results.text = listOf("Речь: ${state.transcript.ifBlank { noTranscript }}",
            "Риск: ${state.risk.ifBlank { noRisk }}", "Звук на сервере: ${state.serverLevel.ifBlank { getString(R.string.mic_no_server_data) }}").joinToString("\n\n")
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); releasePlayer(); super.onPause() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

