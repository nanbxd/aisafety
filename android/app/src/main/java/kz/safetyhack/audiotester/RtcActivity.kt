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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

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
    private lateinit var target: EditText
    private lateinit var ownAddress: TextView
    private lateinit var consent: CheckBox
    private lateinit var status: TextView
    private lateinit var statusDot: View
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
    private lateinit var callsTab: Button
    private lateinit var analysisTab: Button
    private var pendingAction: (() -> Unit)? = null
    private var resumeAction: (() -> Unit)? = null
    private var currentTab = 0
    private var wasRinging = false
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val canvasColor get() = color(if (dark) "#101319" else "#F3F5F8")
    private val surface get() = color(if (dark) "#1B2029" else "#FFFFFF")
    private val ink get() = color(if (dark) "#F4F6FA" else "#171C26")
    private val secondary get() = color(if (dark) "#B8C1D1" else "#596579")
    private val primary get() = color(if (dark) "#B8C6FF" else "#3454D1")
    private val onPrimary get() = color(if (dark) "#16245C" else "#FFFFFF")
    private val tonal get() = color(if (dark) "#29334B" else "#E9EDFF")
    private val callSurface get() = color(if (dark) "#222833" else "#171D29")
    private val callInk get() = color("#F7F8FC")
    private val callSecondary get() = color("#AEB8C9")
    private val connected get() = color(if (dark) "#68D5B0" else "#39C79A")
    private val waiting get() = color("#F0B85A")

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
        top.addView(text("Skepticall", 24f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("Мой номер", false) { showAddressSettings() })
        root.addView(top)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(680)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        callPane = column(content)
        callPane.addView(text("Звоните человеку.\nПрямо по номеру.", 34f, ink, true).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        callPane.addView(text("Номер работает как адрес внутри Skepticall. Мобильная сеть и минуты не используются.", 16f, secondary).apply { setPadding(0, dp(12), 0, dp(32)) })
        setup = column(callPane)
        setup.addView(text("Ваш адрес", 18f, ink, true))
        ownAddress = text("", 20f, primary, true).apply { setPadding(0, dp(8), 0, dp(24)) }
        setup.addView(ownAddress)
        setup.addView(text("Кому позвонить", 18f, ink, true).apply { setPadding(0, 0, 0, dp(4)) })
        setup.addView(text("Введите номер, который человек указал в своём приложении.", 14f, secondary))
        target = EditText(this).apply {
            textSize = 20f; setTextColor(ink); setHintTextColor(secondary)
            setSingleLine(); hint = "+7 701 123 45 67"; contentDescription = "Номер получателя"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            backgroundTintList = ColorStateList.valueOf(primary)
            setText(savedInstanceState?.getString("target") ?: addressPrefs().getString("last_target", ""))
            minimumHeight = dp(56)
        }
        setup.addView(target, LinearLayout.LayoutParams(-1, -2))
        target.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        consent = CheckBox(this).apply {
            text = "Все участники согласны на передачу речи собеседника в Google Gemini для распознавания и анализа."
            textSize = 14f; setTextColor(secondary); buttonTintList = ColorStateList.valueOf(primary)
            minHeight = dp(56); isSaveEnabled = false; setPadding(0, dp(16), 0, dp(16))
        }
        setup.addView(consent)
        invite = button("Позвонить", true) { dialAddress() }
        setup.addView(invite, spaced())
        connect = button("Включить входящие", false) { enableIncoming() }
        setup.addView(connect, spaced())
        summary = text("", 16f, primary, true)
        callPane.addView(summary, spaced())
        val live = column(callPane).apply {
            background = rounded(callSurface, 20); setPadding(dp(24), dp(24), dp(24), dp(24)); elevation = dp(3).toFloat()
        }
        live.addView(text("Состояние звонка", 15f, callSecondary, true))
        val statusRow = row(live).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        statusDot = View(this).apply {
            background = rounded(waiting, 999)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
        status = text("Готовы к разговору", 22f, callInk, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        accept = button("Принять звонок", true) { withMicrophone { engine.accept() } }; live.addView(accept, spaced())
        reject = button("Отклонить", false) { engine.end("reject") }.apply { setTextColor(color(if (dark) "#FFB4AB" else "#B3261E")) }; live.addView(reject, spaced())
        val controls = row(live)
        mute = button("Микрофон", false) { engine.toggleMute() }; equal(controls, mute)
        speaker = button("Динамик", false) { engine.toggleSpeaker() }; equal(controls, speaker)
        hangup = button("Завершить звонок", false) { engine.end() }.apply { setTextColor(color(if (dark) "#FFB4AB" else "#B3261E")) }
        live.addView(hangup, spaced())
        disconnect = button("Отключить входящие", false) {
            val own = ownAddressKey()
            if (engine.state.role == "caller" && own != null) CallService.join(this, own, "receiver") else engine.leave()
        }; callPane.addView(disconnect, spaced())
        callPane.addView(text("Можно свернуть приложение: входящие продолжат приходить с рингтоном. После исходящего вызова Skepticall автоматически вернётся на ваш адрес.", 14f, secondary).apply { setPadding(0, dp(16), 0, dp(8)) })
        callPane.addView(button("Настройки входящих звонков", false) { incomingSettings() }, spaced())
        callPane.addView(button("Как это работает", false) { showHelp() }, spaced())
        analysisPane = column(content)
        analysisPane.addView(text("Смысл за словами", 34f, ink, true))
        analysisPane.addView(text("Сигналы риска и расшифровка речи собеседника", 16f, secondary).apply { setPadding(0, dp(8), 0, dp(28)) })
        risk = text("", 20f, ink, true).apply { background = rounded(tonal, 20); setPadding(dp(24), dp(24), dp(24), dp(24)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        analysisPane.addView(risk, spaced())
        analysis = text("", 15f, secondary).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        analysisPane.addView(analysis, spaced())
        analysisPane.addView(text("Речь собеседника", 22f, ink, true).apply { setPadding(0, dp(28), 0, dp(16)) })
        transcript = text("", 18f, ink).apply { setTextIsSelectable(true); setLineSpacing(dp(6).toFloat(), 1f) }
        analysisPane.addView(transcript, spaced())
        analysisPane.addView(text("Модель может ошибаться. Не сообщайте коды и пароли; проверяйте просьбы через официальный канал.", 14f, secondary).apply { setPadding(0, dp(32), 0, dp(8)) })
        val nav = LinearLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(surface); minimumHeight = dp(72); elevation = dp(8).toFloat() }
        callsTab = button("Разговор", false) { currentTab = 0; render(); scroll.scrollTo(0, 0) }; equal(nav, callsTab)
        analysisTab = button("Анализ", false) { currentTab = 1; render(); scroll.scrollTo(0, 0) }; equal(nav, analysisTab)
        root.addView(nav)
        setContentView(root)
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
        statusDot.background = rounded(when {
            s.inCall || (s.connected && s.role == "receiver" && !s.busy) || (s.connected && s.peer && !s.busy) -> connected
            s.connecting || s.ringing || s.busy -> waiting
            else -> callSecondary
        }, 999)
        val ownNumber = ownNumber()
        val ownKey = ownAddressKey()
        val targetKey = PhoneAddress.key(target.text.toString())
        ownAddress.text = ownNumber ?: "Не настроен — нажмите «Мой номер»"
        setup.visibility = View.VISIBLE
        summary.visibility = if (s.connected) View.VISIBLE else View.GONE
        summary.text = if (s.role == "caller") "Звоним на ${PhoneAddress.display(s.room)}" else "Ваш адрес ${ownNumber ?: PhoneAddress.display(s.room)}"
        connect.visibility = if (s.connected && s.role == "receiver") View.GONE else View.VISIBLE
        connect.isEnabled = ownKey != null && !s.connecting && !s.busy
        connect.text = when {
            s.connecting -> "Подключение…"
            s.connected && s.role == "caller" -> "Вернуться на свой адрес"
            else -> "Включить входящие"
        }
        target.isEnabled = !s.connecting && !s.busy
        consent.isEnabled = !s.connecting && !s.busy
        invite.visibility = if (!s.busy && !s.ringing) View.VISIBLE else View.GONE
        invite.isEnabled = ownKey != null && targetKey != null && targetKey != ownKey && !s.connecting
        listOf(connect, invite).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        accept.visibility = if (s.ringing && s.role == "receiver") View.VISIBLE else View.GONE
        reject.visibility = accept.visibility
        mute.visibility = if (s.inCall) View.VISIBLE else View.GONE
        speaker.visibility = mute.visibility
        mute.text = if (s.muted) "Включить микрофон" else "Выключить микрофон"
        speaker.text = if (s.speaker) "Наушник" else "Динамик"
        hangup.visibility = if (s.busy && !(s.ringing && s.role == "receiver")) View.VISIBLE else View.GONE
        disconnect.visibility = if (((s.connected || s.connecting) && s.role == "receiver") || (!s.connected && PushCalls.enabled(this))) View.VISIBLE else View.GONE
        disconnect.text = "Отключить входящие"
        analysis.text = s.analysis
        transcript.text = s.transcript.ifBlank { "Здесь появятся слова собеседника. Анализ доступен принимающему участнику после ответа на звонок." }
        risk.text = s.risk
        risk.setTextColor(if (s.riskLevel == "high") color(if (dark) "#FFB4AB" else "#B3261E") else ink)
        callPane.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
        analysisPane.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
        selected(callsTab, currentTab == 0); selected(analysisTab, currentTab == 1)
    }
    private fun enableIncoming() {
        val own = ownAddressKey()
        if (own == null) { showAddressSettings(); return }
        if (!consent.isChecked) { status.text = "Подтвердите согласие участников на анализ речи."; return }
        if (CaptureState.current.active) { status.text = "Сначала остановите запись микрофона."; return }
        prepareBackgroundSession {
            runCatching { CallService.join(this, own, "receiver") }
                .onFailure { status.text = "Не удалось включить входящие. Проверьте разрешения и повторите." }
        }
    }
    private fun dialAddress() {
        val own = ownAddressKey()
        if (own == null) { showAddressSettings(); return }
        val entered = target.text.toString()
        val destination = PhoneAddress.key(entered)
        if (destination == null) { target.error = "Введите номер от 10 до 15 цифр"; return }
        if (destination == own) { target.error = "Нельзя позвонить на собственный адрес"; return }
        if (!consent.isChecked) { status.text = "Подтвердите согласие участников на анализ речи."; return }
        if (CaptureState.current.active) { status.text = "Сначала остановите запись микрофона."; return }
        addressPrefs().edit().putString("last_target", PhoneAddress.normalize(entered)).apply()
        prepareBackgroundSession {
            runCatching { CallService.join(this, destination, "caller", homeAddress = own, autoInvite = true) }
                .onFailure { status.text = "Не удалось начать звонок. Проверьте разрешения и повторите." }
        }
    }
    private fun addressPrefs() = getSharedPreferences("phone_address", MODE_PRIVATE)
    private fun ownNumber(): String? = addressPrefs().getString("own_number", null)?.let(PhoneAddress::normalize)
    private fun ownAddressKey(): String? = ownNumber()?.let(PhoneAddress::key)
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
            .setMessage("Разрешите уведомления, звук и показ на экране блокировки. В беззвучном режиме и режиме «Не беспокоить» звонок подчиняется настройкам телефона.\n\nПосле принудительной остановки приложения нужно открыть его и снова включить входящие.")
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
    private fun showAddressSettings() {
        if (engine.state.busy) { status.text = "Завершите текущий звонок перед сменой номера."; return }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "+7 701 123 45 67"
            setText(ownNumber().orEmpty())
            setSelection(text.length)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Ваш номер в Skepticall")
            .setMessage("Номер используется только как адрес внутри приложения. SMS-подтверждения и обычного телефонного вызова нет.")
            .setView(input)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = PhoneAddress.normalize(input.text.toString())
                if (normalized == null) input.error = "Введите номер от 10 до 15 цифр"
                else {
                    addressPrefs().edit().putString("own_number", normalized).apply()
                    ownAddress.text = normalized
                    val address = PhoneAddress.key(normalized)!!
                    if ((engine.state.connected || engine.state.connecting) && (engine.state.role != "receiver" || engine.state.room != address)) {
                        CallService.join(this, address, "receiver")
                    }
                    render()
                    status.text = "Адрес сохранён. Включите входящие."
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    private fun showHelp() {
        android.app.AlertDialog.Builder(this).setTitle("Звонок в три шага")
            .setMessage("Укажите свой номер в настройках и включите входящие.\n\nВведите номер пользователя Skepticall и нажмите «Позвонить». Получатель увидит входящий вызов и сможет ответить.\n\nВо вкладке «Анализ» получатель видит речь и предупреждения. Это помощь, а не гарантия безопасности.")
            .setPositiveButton("Понятно", null)
            .setNeutralButton("Проверить микрофон") { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }.show()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("target", target.text.toString()); super.onSaveInstanceState(out) }
    override fun onStop() { CallRuntime.remove(stateObserver); super.onStop() }
    override fun onDestroy() { pendingAction = null; resumeAction = null; super.onDestroy() }
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
        text = value; textSize = 15f; isAllCaps = false; minHeight = dp(54)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color("#305B78FF")), rounded(if (filled) primary else tonal, 14), null)
        setTextColor(if (filled) onPrimary else primary); setOnClickListener { action() }
    }
    private fun selected(button: Button, active: Boolean) {
        button.isSelected = active
        button.backgroundTintList = ColorStateList.valueOf(if (active) primary else tonal)
        button.setTextColor(if (active) onPrimary else primary)
    }
}
