package kz.safetyhack.audiotester

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.*
import java.security.SecureRandom

/** Native Android call workspace. No browser, URL field, or JavaScript bridge. */
class RtcActivity : Activity() {
    companion object { val audioSessionOpen get() = CallRuntime.sessionOpen }
    private val engine get() = CallRuntime.client(this)
    private val stateObserver: () -> Unit = { render() }
    private var resumed = false
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var setup: LinearLayout
    private lateinit var callPane: LinearLayout
    private lateinit var analysisPane: LinearLayout
    private lateinit var room: EditText
    private lateinit var consent: CheckBox
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var analysis: TextView
    private lateinit var transcript: TextView
    private lateinit var risk: TextView
    private lateinit var connect: Button
    private lateinit var invite: Button
    private lateinit var accept: Button
    private lateinit var reject: Button
    private lateinit var hangup: Button
    private lateinit var mute: Button
    private lateinit var speaker: Button
    private lateinit var disconnect: Button
    private lateinit var receiver: Button
    private lateinit var caller: Button
    private lateinit var callsTab: Button
    private lateinit var analysisTab: Button
    private var role = "receiver"
    private var pendingAction: (() -> Unit)? = null
    private var resumeAction: (() -> Unit)? = null
    private var currentTab = 0
    private var wasRinging = false
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val canvasColor get() = color(if (dark) "#11141B" else "#F5F6FA")
    private val surface get() = color(if (dark) "#1D2230" else "#FFFFFF")
    private val ink get() = color(if (dark) "#F1F2FA" else "#192237")
    private val secondary get() = color(if (dark) "#B8C2D8" else "#526078")
    private val primary get() = color(if (dark) "#B7C6FF" else "#2949BA")
    private val onPrimary get() = color(if (dark) "#14245F" else "#FFFFFF")
    private val tonal get() = color(if (dark) "#293450" else "#E8EDFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvasColor
        window.navigationBarColor = canvasColor
        if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i
            }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        top.addView(text("Skepticall", 22f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("Помощь", false) { showHelp() })
        root.addView(top)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(680)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        callPane = column(content)
        callPane.addView(text("На связи.\nПод вашим контролем.", 32f, ink, true))
        callPane.addView(text("Интернет-звонок с анализом речи собеседника", 16f, secondary).apply { setPadding(0, dp(12), 0, dp(28)) })
        setup = column(callPane)
        setup.addView(text("Как вы участвуете?", 18f, ink, true))
        val roles = row(setup)
        receiver = button("Принимаю", false) { role = "receiver"; render() }
        caller = button("Звоню", false) { role = "caller"; render() }
        equal(roles, receiver); equal(roles, caller)
        setup.addView(text("Комната", 18f, ink, true).apply { setPadding(0, dp(20), 0, dp(4)) })
        setup.addView(text("Один код на двоих. Выберите разные роли.", 14f, secondary))
        room = EditText(this).apply {
            textSize = 20f; setTextColor(ink); setHintTextColor(secondary)
            setSingleLine(); hint = "Код комнаты"; contentDescription = "Код комнаты"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            backgroundTintList = ColorStateList.valueOf(primary)
            setText(savedInstanceState?.getString("room") ?: getPreferences(0).getString("room", null) ?: newRoom())
            minimumHeight = dp(56)
        }
        setup.addView(room, LinearLayout.LayoutParams(-1, -2))
        val roomActions = row(setup)
        equal(roomActions, button("Новый код", false) { room.setText(newRoom()) })
        equal(roomActions, button("Поделиться", false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Комната Skepticall: ${room.text.toString().trim()}. Откройте приложение и введите этот код.")
            }, "Код комнаты"))
        })
        consent = CheckBox(this).apply {
            text = "Все участники согласны на передачу речи собеседника в Google Gemini для распознавания и анализа."
            textSize = 14f; setTextColor(secondary); buttonTintList = ColorStateList.valueOf(primary)
            minHeight = dp(56); isSaveEnabled = false; setPadding(0, dp(16), 0, dp(16))
        }
        setup.addView(consent)
        connect = button("Войти в комнату", true) {
            val code = room.text.toString().trim()
            if (!Regex("[A-Za-z0-9_-]{8,64}").matches(code)) { room.error = "От 8 до 64 латинских букв, цифр, _ или -"; return@button }
            if (!consent.isChecked) { status.text = "Для подключения нужно согласие всех участников."; return@button }
            if (CaptureState.current.active) { status.text = "Сначала остановите запись микрофона."; return@button }
            getPreferences(0).edit().putString("room", code).apply()
            val selectedRole = role
            prepareBackgroundSession {
                runCatching { CallService.join(this, code, selectedRole) }
                    .onFailure { status.text = "Не удалось включить фоновый режим. Повторите вход из открытого приложения." }
            }
        }
        setup.addView(connect, spaced())
        summary = text("", 16f, primary, true)
        callPane.addView(summary, spaced())
        val live = column(callPane).apply {
            background = rounded(surface, 28); setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        live.addView(text("Ваш разговор", 22f, ink, true))
        status = text("Готовы к разговору", 20f, ink).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setPadding(0, dp(16), 0, dp(20)) }
        live.addView(status)
        invite = button("Позвонить", true) { withMicrophone { engine.invite() } }; live.addView(invite, spaced())
        accept = button("Принять звонок", true) { withMicrophone { engine.accept() } }; live.addView(accept, spaced())
        reject = button("Отклонить", false) { engine.end("reject") }; live.addView(reject, spaced())
        val controls = row(live)
        mute = button("Микрофон", false) { engine.toggleMute() }; equal(controls, mute)
        speaker = button("Динамик", false) { engine.toggleSpeaker() }; equal(controls, speaker)
        hangup = button("Завершить звонок", false) { engine.end() }.apply { setTextColor(color(if (dark) "#FFB4AB" else "#B3261E")) }
        live.addView(hangup, spaced())
        disconnect = button("Выйти из комнаты", false) { engine.leave() }; callPane.addView(disconnect, spaced())
        callPane.addView(text("Можно свернуть приложение: разговор продолжится. Пока вы в комнате, входящие приходят с рингтоном. Для отключения нажмите «Выйти из комнаты».", 14f, secondary).apply { setPadding(0, dp(16), 0, dp(8)) })
        callPane.addView(button("Настройки входящих звонков", false) { incomingSettings() }, spaced())
        analysisPane = column(content)
        analysisPane.addView(text("Смысл за словами", 32f, ink, true))
        analysisPane.addView(text("Анализ разговора", 18f, secondary).apply { setPadding(0, dp(8), 0, dp(24)) })
        risk = text("", 20f, ink, true).apply { background = rounded(tonal, 24); setPadding(dp(24), dp(24), dp(24), dp(24)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        analysisPane.addView(risk, spaced())
        analysis = text("", 15f, secondary).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        analysisPane.addView(analysis, spaced())
        analysisPane.addView(text("Речь собеседника", 22f, ink, true).apply { setPadding(0, dp(28), 0, dp(16)) })
        transcript = text("", 18f, ink).apply { setTextIsSelectable(true); setLineSpacing(dp(6).toFloat(), 1f) }
        analysisPane.addView(transcript, spaced())
        analysisPane.addView(text("Модель может ошибаться. Не сообщайте коды и пароли; проверяйте просьбы через официальный канал.", 14f, secondary).apply { setPadding(0, dp(32), 0, dp(8)) })
        val nav = LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(surface) }
        callsTab = button("Разговор", false) { currentTab = 0; render(); scroll.scrollTo(0, 0) }; equal(nav, callsTab)
        analysisTab = button("Анализ", false) { currentTab = 1; render(); scroll.scrollTo(0, 0) }; equal(nav, analysisTab)
        root.addView(nav)
        setContentView(root)
        role = if (engine.state.connected || engine.state.connecting) engine.state.role else savedInstanceState?.getString("role") ?: "receiver"
        render()
    }
    private fun render() {
        if (!::status.isInitialized) return
        val s = engine.state
        if (s.ringing && !wasRinging && s.role == "receiver") {
            currentTab = 0
            scroll.post { scroll.scrollTo(0, 0); accept.requestFocus() }
        }
        wasRinging = s.ringing
        if (Build.VERSION.SDK_INT >= 27) {
            // Only the incoming controls belong above the keyguard; transcripts require unlocking.
            setShowWhenLocked(s.ringing && s.role == "receiver")
            setTurnScreenOn(s.ringing && s.role == "receiver")
        } else {
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (s.ringing && s.role == "receiver") window.addFlags(flags) else window.clearFlags(flags)
        }
        status.text = s.status
        setup.visibility = if (s.connected) View.GONE else View.VISIBLE
        summary.visibility = if (s.connected) View.VISIBLE else View.GONE
        summary.text = "${s.room} · ${if (s.role == "caller") "Вы звоните" else "Вы принимаете"}"
        connect.isEnabled = !s.connecting
        connect.text = if (s.connecting) "Подключение…" else "Войти в комнату"
        room.isEnabled = !s.connecting; consent.isEnabled = !s.connecting
        receiver.isEnabled = !s.connecting; caller.isEnabled = !s.connecting
        selected(receiver, role == "receiver"); selected(caller, role == "caller")
        invite.visibility = if (s.connected && s.role == "caller" && !s.busy) View.VISIBLE else View.GONE
        invite.isEnabled = s.peer
        listOf(connect, invite, receiver, caller).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        accept.visibility = if (s.ringing && s.role == "receiver") View.VISIBLE else View.GONE
        reject.visibility = accept.visibility
        mute.visibility = if (s.inCall) View.VISIBLE else View.GONE
        speaker.visibility = mute.visibility
        mute.text = if (s.muted) "Включить микрофон" else "Выключить микрофон"
        speaker.text = if (s.speaker) "Наушник" else "Динамик"
        hangup.visibility = if (s.busy && !(s.ringing && s.role == "receiver")) View.VISIBLE else View.GONE
        disconnect.visibility = if (s.connected || s.connecting || PushCalls.enabled(this)) View.VISIBLE else View.GONE
        disconnect.text = if (s.connected || s.connecting) "Выйти из комнаты" else "Отключить входящие"
        analysis.text = s.analysis
        transcript.text = s.transcript.ifBlank { "Здесь появятся слова собеседника. Анализ доступен принимающему участнику после ответа на звонок." }
        risk.text = s.risk
        risk.setTextColor(if (s.riskLevel == "high") color(if (dark) "#FFB4AB" else "#B3261E") else ink)
        callPane.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
        analysisPane.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
        selected(callsTab, currentTab == 0); selected(analysisTab, currentTab == 1)
    }
    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else {
            val identity = engine.actionIdentity
            pendingAction = { if (engine.actionIdentity == identity) action() }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 20)
        }
    }
    private fun prepareBackgroundSession(action: () -> Unit) {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isEmpty()) {
            if (getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) action()
            else { status.text = "Разрешите уведомления в настройках входящих, чтобы не пропускать звонки."; incomingSettings() }
        } else {
            pendingAction = action
            requestPermissions(permissions.toTypedArray(), 21)
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 20 || code == 21) {
            val action = pendingAction; pendingAction = null
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (resumed) action?.invoke() else resumeAction = action
            } else status.text = if (code == 21) "Для фоновых звонков разрешите микрофон и уведомления. Затем войдите снова." else "Разрешите микрофон, чтобы говорить. Затем повторите действие."
        }
    }
    private fun incomingSettings() {
        val builder = android.app.AlertDialog.Builder(this).setTitle("Входящие звонки")
            .setMessage("Разрешите уведомления, звук и показ на экране блокировки. В беззвучном режиме и режиме «Не беспокоить» звонок подчиняется настройкам телефона.\n\nПосле принудительной остановки приложения нужно открыть его и войти снова.")
            .setPositiveButton("Уведомления") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }.setNegativeButton("Закрыть", null)
        if (Build.VERSION.SDK_INT >= 34) builder.setNeutralButton("Поверх блокировки") { _, _ ->
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName")))
        }
        builder.show()
    }
    override fun onStart() {
        super.onStart()
        CallRuntime.observe(stateObserver)
        if (engine.state.connected || engine.state.connecting) { role = engine.state.role; room.setText(engine.state.room) }
        render()
    }
    override fun onResume() {
        super.onResume(); resumed = true
        val action = resumeAction; resumeAction = null; action?.invoke()
        consumeAnswerIntent()
    }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (resumed) consumeAnswerIntent()
    }
    private fun consumeAnswerIntent() {
        if (intent?.action == PushCalls.ANSWER || intent?.action == PushCalls.OPEN) {
            val answer = intent.action == PushCalls.ANSWER
            intent.action = null
            val code = intent.getStringExtra("room") ?: return
            val call = intent.getStringExtra("call_id") ?: return
            val expires = intent.getLongExtra("expires_at", 0)
            if (!PushCalls.pending(this, code, call) || expires <= System.currentTimeMillis()) {
                PushCalls.cancel(this, call); status.text = "Вызов уже завершён"; return
            }
            val acceptPush: () -> Unit = {
                prepareBackgroundSession {
                    if (PushCalls.pending(this, code, call) && expires > System.currentTimeMillis() && !CaptureState.current.active) {
                        PushCalls.cancel(this, call)
                        if (engine.state.ringing && engine.state.room == code) engine.acceptExpected(call)
                        else if (!engine.state.connected && !engine.state.connecting) {
                            role = "receiver"; room.setText(code)
                            runCatching { CallService.join(this, code, "receiver", call) }
                                .onFailure { status.text = "Не удалось принять звонок. Повторите вход." }
                        }
                    }
                }
            }
            if (answer) acceptPush() else android.app.AlertDialog.Builder(this)
                .setTitle("Входящий звонок Skepticall").setMessage("Ответить собеседнику?")
                .setPositiveButton("Ответить") { _, _ -> acceptPush() }
                .setNegativeButton("Отклонить") { _, _ -> PushCalls.reject(this, code, call) }.show()
            return
        }
        if (intent?.action != CallService.ANSWER) return
        val token = intent.getStringExtra(CallService.TOKEN)
        intent.action = null // A rotation or returning to the activity must not answer twice.
        if (CallNotificationState.validAction(token, engine.actionToken) && engine.state.ringing && engine.state.role == "receiver") withMicrophone { engine.accept() }
    }
    private fun showHelp() {
        android.app.AlertDialog.Builder(this).setTitle("Разговор в три шага")
            .setMessage("Передайте код комнаты собеседнику. Один участник выбирает «Звоню», другой — «Принимаю».\n\nОба входят в комнату. Звонящий нажимает «Позвонить», принимающий отвечает.\n\nВо вкладке «Анализ» принимающий видит речь и предупреждения. Это помощь, а не гарантия безопасности.")
            .setPositiveButton("Понятно", null)
            .setNeutralButton("Проверить микрофон") { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }.show()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("room", room.text.toString()); out.putString("role", role); super.onSaveInstanceState(out) }
    override fun onStop() { CallRuntime.remove(stateObserver); super.onStop() }
    override fun onDestroy() { pendingAction = null; resumeAction = null; super.onDestroy() }
    private fun newRoom(): String = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun color(hex: String) = Color.parseColor(hex)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun rounded(value: Int, radius: Int) = GradientDrawable().apply { setColor(value); cornerRadius = dp(radius).toFloat() }
    private fun column(parent: LinearLayout) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this, LinearLayout.LayoutParams(-1, -2)) }
    private fun row(parent: LinearLayout) = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; parent.addView(this, spaced()) }
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
    private fun equal(parent: LinearLayout, child: View) { parent.addView(child, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); marginStart = dp(4) }) }
    private fun text(value: String, size: Float, tint: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(tint); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 15f; isAllCaps = false; minHeight = dp(52)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color("#305B78FF")), rounded(if (filled) primary else tonal, 18), null)
        setTextColor(if (filled) onPrimary else primary); setOnClickListener { action() }
    }
    private fun selected(button: Button, active: Boolean) {
        button.isSelected = active
        button.backgroundTintList = ColorStateList.valueOf(if (active) primary else tonal)
        button.setTextColor(if (active) onPrimary else primary)
    }
}
