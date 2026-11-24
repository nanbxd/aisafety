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
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    companion object { val audioSessionOpen get() = CallRuntime.sessionOpen }
    private val engine get() = CallRuntime.client(this)
    private val stateObserver: () -> Unit = { render() }
    private var resumed = false
    private lateinit var root: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var lobbyExtras: LinearLayout
    private lateinit var live: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var avatar: CallAvatar
    private lateinit var sceneTitle: TextView
    private lateinit var sceneDescription: TextView
    private lateinit var brand: TextView
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
    private var dialing = false
    private lateinit var home: FrameLayout
    private lateinit var menu: Button
    private lateinit var settingsBody: LinearLayout
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val canvasColor get() = color(if (dark) "#202124" else "#FFFFFF")
    private val surface get() = color(if (dark) "#292A2D" else "#FFFFFF")
    private val ink get() = color(if (dark) "#E8EAED" else "#202124")
    private val secondary get() = color(if (dark) "#BDC1C6" else "#5F6368")
    private val primary get() = color(if (dark) "#A8C7FA" else "#0B57D0")
    private val onPrimary get() = color(if (dark) "#062E6F" else "#FFFFFF")
    private val tonal get() = color(if (dark) "#3C4043" else "#D3E3FD")
    private val callSurface get() = color(if (dark) "#292A2D" else "#202124")
    private val callInk get() = color("#E8EAED")
    private val callSecondary get() = color("#BDC1C6")
    private val connected get() = color(if (dark) "#68D5B0" else "#39C79A")
    private val waiting get() = color("#F0B85A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialing = savedInstanceState?.getBoolean("dialing") ?: false
        window.statusBarColor = canvasColor
        window.navigationBarColor = canvasColor
        if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i
            }
        }
        top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        brand = text("Skepticall", 24f, secondary).apply { icon(this, "logo", secondary) }
        menu = button("\u2630", false) { showMenu() }.apply { contentDescription = getString(R.string.menu_title) }
        top.addView(menu)
        root.addView(top)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(680)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        home = FrameLayout(this)
        val homeStatus = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        homeStatus.addView(ImageView(this).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(android.graphics.drawable.PictureDrawable(
                com.caverock.androidsvg.SVG.getFromAsset(assets, "home-logo.svg").renderToPicture()))
            contentDescription = "Skepticall"
        }, LinearLayout.LayoutParams(dp(160), dp(208)))
        homeStatus.addView(text(getString(R.string.home_call_status_hint), 15f, secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2))
        home.addView(homeStatus, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        home.addView(ImageButton(this).apply {
            val picture = com.caverock.androidsvg.SVG.getFromAsset(assets, "icons/phone.svg").renderToPicture()
            val bitmap = android.graphics.Bitmap.createBitmap(dp(28), dp(28), android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).drawPicture(picture, android.graphics.Rect(0, 0, dp(28), dp(28)))
            setImageBitmap(bitmap); imageTintList = ColorStateList.valueOf(onPrimary)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(tonal), rounded(primary, 24), null)
            contentDescription = getString(R.string.new_call)
            setOnClickListener { dialing = true; currentTab = 0; render() }
        }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
        root.addView(home, LinearLayout.LayoutParams(-1, 0, 1f))
        callPane = column(content)
        setup = column(callPane)
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        ownAddress = text("", 20f, primary, true)
        settingsBody.addView(ownAddress)
        setup.addView(button("×", false) { dialing = false; render() }.apply {
            textSize = 28f
            contentDescription = getString(R.string.close)
            background = rounded(Color.TRANSPARENT, 24)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END; bottomMargin = dp(12)
        })
        target = EditText(this).apply {
            textSize = 36f; gravity = Gravity.CENTER; showSoftInputOnFocus = false; setTextColor(ink); setHintTextColor(secondary)
            setSingleLine(); hint = "+7 701 123 45 67"; contentDescription = getString(R.string.recipient_number_cd)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            background = rounded(surface, 8).apply { setStroke(dp(1), secondary) }
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
            text = getString(R.string.consent_text)
            textSize = 14f; setTextColor(secondary); buttonTintList = ColorStateList.valueOf(primary)
            minHeight = dp(56); isSaveEnabled = false; setPadding(0, dp(16), 0, dp(16))
        }
        consent.isChecked = addressPrefs().getBoolean("demo_consent", true)
        consent.setOnCheckedChangeListener { _, checked -> addressPrefs().edit().putBoolean("demo_consent", checked).apply() }
        settingsBody.addView(consent)
        for (keys in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))) {
            val keypadRow = row(setup)
            keys.forEach { key ->
                val digit = button(key, false) {
                    val start = target.selectionStart.coerceAtLeast(0)
                    val end = target.selectionEnd.coerceAtLeast(start)
                    target.text.replace(start, end, key)
                }.apply { textSize = 30f; minimumHeight = dp(64) }
                if (key == "0") digit.setOnLongClickListener { target.text.insert(target.selectionStart.coerceAtLeast(0), "+"); true }
                equal(keypadRow, digit)
            }
        }
        val editRow = row(setup)
        equal(editRow, button("+", false) { target.text.insert(target.selectionStart.coerceAtLeast(0), "+") })
        equal(editRow, button("\u232b", false) {
            val end = target.selectionEnd.coerceAtLeast(0)
            val start = target.selectionStart.coerceAtLeast(0)
            if (end > start) target.text.delete(start, end) else if (start > 0) target.text.delete(start - 1, start)
        }.apply { contentDescription = getString(R.string.delete_digit) })
        invite = button(getString(R.string.new_call), true) { dialAddress() }.apply { icon(this, "phone", onPrimary) }
        setup.addView(invite, spaced())
        connect = button(getString(R.string.enable_incoming), false) { enableIncoming() }
        settingsBody.addView(connect, spaced())
        summary = text("", 16f, primary, true)
        callPane.addView(summary, spaced())
        live = column(callPane).apply {
            background = rounded(callSurface, 24); setPadding(dp(24), dp(20), dp(24), dp(24)); minimumHeight = dp(380)
        }
        val statusRow = row(live).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        statusDot = View(this).apply {
            background = rounded(waiting, 999)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
        status = text(getString(R.string.ready_to_talk), 22f, callInk, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        avatar = CallAvatar(this)
        live.addView(avatar, LinearLayout.LayoutParams(dp(176), dp(176)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(32); bottomMargin = dp(28) })
        sceneTitle = text(getString(R.string.scene_idle_title), 24f, callInk).apply { gravity = Gravity.CENTER }
        live.addView(sceneTitle, LinearLayout.LayoutParams(-1, -2))
        sceneDescription = text(getString(R.string.scene_idle_desc), 14f, callSecondary).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(16)) }
        live.addView(sceneDescription, LinearLayout.LayoutParams(-1, -2))
        dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(color("#202124")) }
        accept = button(getString(R.string.accept_call), true) { withMicrophone { engine.accept() } }.apply { icon(this, "phone", onPrimary) }; dock.addView(accept, spaced())
        reject = button(getString(R.string.decline), false) { engine.end("reject") }.apply { setTextColor(color("#F28B82")); background = rounded(color("#3C4043"), 28) }; dock.addView(reject, spaced())
        val controls = row(dock)
        mute = button(getString(R.string.microphone), false) { engine.toggleMute() }; equal(controls, mute)
        speaker = button(getString(R.string.speaker), false) {
            if (!engine.toggleSpeaker()) Toast.makeText(this, R.string.speaker_switch_failed, Toast.LENGTH_SHORT).show()
        }; equal(controls, speaker)
        hangup = button("", false) { engine.end() }.apply { contentDescription = getString(R.string.end_call_cd); background = rounded(color("#EA4335"), 28); setTextColor(Color.WHITE); icon(this, "hangup", Color.WHITE) }
        equal(controls, hangup)
        lobbyExtras = column(settingsBody)
        disconnect = button(getString(R.string.disable_incoming), false) {
            val own = ownAddressKey()
            if (engine.state.role == "caller" && own != null) CallService.join(this, own, "receiver") else engine.leave()
        }; lobbyExtras.addView(disconnect, spaced())
        lobbyExtras.addView(text(getString(R.string.lobby_hint), 14f, secondary).apply { setPadding(0, dp(16), 0, dp(8)) })
        lobbyExtras.addView(button(getString(R.string.incoming_settings_btn), false) { incomingSettings() }, spaced())
        lobbyExtras.addView(button(getString(R.string.how_it_works), false) { showHelp() }, spaced())
        analysisPane = column(content)
        analysisPane.addView(text(getString(R.string.analysis_title), 28f, ink))
        analysisPane.addView(text(getString(R.string.analysis_subtitle), 16f, secondary).apply { setPadding(0, dp(8), 0, dp(28)) })
        risk = text("", 20f, ink, true).apply { background = rounded(tonal, 20); setPadding(dp(24), dp(24), dp(24), dp(24)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        analysisPane.addView(risk, spaced())
        analysis = text("", 15f, secondary).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        analysisPane.addView(analysis, spaced())
        analysisPane.addView(text(getString(R.string.peer_speech), 22f, ink, true).apply { setPadding(0, dp(28), 0, dp(16)) })
        transcript = text("", 18f, ink).apply { setTextIsSelectable(true); setLineSpacing(dp(6).toFloat(), 1f) }
        analysisPane.addView(transcript, spaced())
        analysisPane.addView(text(getString(R.string.analysis_disclaimer), 14f, secondary).apply { setPadding(0, dp(32), 0, dp(8)) })
        nav = LinearLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(surface); minimumHeight = dp(72); elevation = dp(8).toFloat() }
        callsTab = button(getString(R.string.tab_conversation), false) { currentTab = 0; render(); scroll.scrollTo(0, 0) }; equal(nav, callsTab)
        analysisTab = button(getString(R.string.tab_analysis), false) { currentTab = 1; render(); scroll.scrollTo(0, 0) }; equal(nav, analysisTab)
        root.addView(dock)
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
        status.text = UiText.translate(this, s.status)
        statusDot.background = rounded(when {
            s.inCall || (s.connected && s.role == "receiver" && !s.busy) || (s.connected && s.peer && !s.busy) -> connected
            s.connecting || s.ringing || s.busy -> waiting
            else -> callSecondary
        }, 999)
        val ownNumber = ownNumber()
        val ownKey = ownAddressKey()
        val targetKey = PhoneAddress.key(target.text.toString())
        ownAddress.text = ownNumber ?: getString(R.string.address_not_set)
        val onCall = s.busy || s.ringing || s.inCall
        if (onCall) dialing = false
        home.visibility = if (!onCall && !dialing) View.VISIBLE else View.GONE
        scroll.visibility = if (onCall || dialing) View.VISIBLE else View.GONE
        nav.visibility = if (onCall) View.VISIBLE else View.GONE
        top.visibility = if (onCall) View.GONE else View.VISIBLE
        live.visibility = if (onCall) View.VISIBLE else View.GONE
        setup.visibility = if (!onCall && dialing) View.VISIBLE else View.GONE
        lobbyExtras.visibility = if (onCall) View.GONE else View.VISIBLE
        dock.visibility = if (onCall) View.VISIBLE else View.GONE
        root.setBackgroundColor(if (onCall) color("#202124") else canvasColor)
        nav.setBackgroundColor(if (onCall) color("#202124") else surface)
        summary.setTextColor(if (onCall) callSecondary else primary)
        live.background = rounded(if (onCall) color("#292A2D") else callSurface, 24)
        brand.setTextColor(if (onCall) callInk else secondary)
        icon(brand, "logo", if (onCall) callInk else secondary)
        window.statusBarColor = if (onCall) color("#202124") else canvasColor
        window.navigationBarColor = window.statusBarColor
        window.decorView.systemUiVisibility = if (!dark && !onCall) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        analysisPane.background = rounded(surface, 24)
        analysisPane.setPadding(dp(20), dp(20), dp(20), dp(20))
        val face = when {
            !onCall -> "idle"
            !s.inCall -> "ringing"
            s.role != "receiver" -> "listening"
            s.analysisUnavailable -> "unavailable"
            s.riskLevel in listOf("high", "medium", "low") -> s.riskLevel
            else -> "listening"
        }
        avatar.showState(face)
        sceneTitle.text = when (face) {
            "high" -> getString(R.string.scene_high_title)
            "medium" -> getString(R.string.scene_medium_title)
            "low" -> getString(R.string.scene_low_title)
            "unavailable" -> getString(R.string.scene_unavailable_title)
            "ringing" -> getString(R.string.scene_ringing_title)
            "listening" -> getString(R.string.scene_listening_title)
            else -> getString(R.string.scene_idle_title)
        }
        sceneDescription.text = when {
            face == "high" -> getString(R.string.scene_high_desc)
            face == "medium" -> getString(R.string.scene_medium_desc)
            face == "low" -> getString(R.string.scene_low_desc)
            face == "unavailable" -> getString(R.string.scene_unavailable_desc)
            s.inCall && s.role != "receiver" -> getString(R.string.scene_caller_desc)
            face == "listening" -> getString(R.string.scene_listening_desc)
            face == "ringing" -> getString(R.string.scene_ringing_desc)
            else -> getString(R.string.scene_idle_desc_long)
        }
        summary.visibility = if (onCall && s.connected) View.VISIBLE else View.GONE
        summary.text = if (s.role == "caller") getString(R.string.calling_to, PhoneAddress.display(s.room)) else getString(R.string.your_address_is, ownNumber ?: PhoneAddress.display(s.room))
        connect.visibility = if (s.connected && s.role == "receiver") View.GONE else View.VISIBLE
        connect.isEnabled = ownKey != null && !s.connecting && !s.busy
        connect.text = when {
            s.connecting -> getString(R.string.connecting)
            s.connected && s.role == "caller" -> getString(R.string.return_to_own)
            else -> getString(R.string.enable_incoming)
        }
        target.isEnabled = !s.connecting && !s.busy
        consent.isEnabled = !s.connecting && !s.busy
        invite.visibility = if (!s.busy && !s.ringing) View.VISIBLE else View.GONE
        invite.isEnabled = targetKey != null && targetKey != ownKey && !s.connecting
        listOf(connect, invite).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        accept.visibility = if (s.ringing && s.role == "receiver") View.VISIBLE else View.GONE
        reject.visibility = accept.visibility
        mute.visibility = if (s.inCall) View.VISIBLE else View.GONE
        speaker.visibility = mute.visibility
        mute.text = ""
        mute.contentDescription = if (s.muted) getString(R.string.mic_unmute_cd) else getString(R.string.mic_mute_cd)
        mute.background = rounded(color(if (s.muted) "#F9DEDC" else "#3C4043"), 28)
        mute.setTextColor(color(if (s.muted) "#601410" else "#E8EAED"))
        icon(mute, if (s.muted) "mic-off" else "mic", color(if (s.muted) "#601410" else "#E8EAED"))
        speaker.isSelected = s.speaker
        speaker.background = rounded(color(if (s.speaker) "#D3E3FD" else "#3C4043"), 28)
        val speakerTint = if (s.speaker) color("#062E6F") else callInk
        speaker.setTextColor(speakerTint)
        icon(speaker, "speaker", speakerTint)
        speaker.text = ""
        speaker.contentDescription = if (s.speaker) getString(R.string.speaker_earpiece_cd) else getString(R.string.speaker_on_cd)
        hangup.visibility = if (s.busy && !(s.ringing && s.role == "receiver")) View.VISIBLE else View.GONE
        disconnect.visibility = if (((s.connected || s.connecting) && s.role == "receiver") || (!s.connected && PushCalls.enabled(this))) View.VISIBLE else View.GONE
        disconnect.text = getString(R.string.disable_incoming)
        analysis.text = UiText.translate(this, s.analysis)
        transcript.text = s.transcript.ifBlank { getString(R.string.transcript_placeholder) }
        risk.text = UiText.translate(this, s.risk)
        risk.setTextColor(if (s.riskLevel == "high") color(if (dark) "#FFB4AB" else "#B3261E") else ink)
        callPane.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
        analysisPane.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
        selected(callsTab, currentTab == 0); selected(analysisTab, currentTab == 1)
    }
    private fun showMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            background = rounded(canvasColor, 28)
        }
        val dialog = android.app.Dialog(this)
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        options.addView(text(getString(R.string.menu_title), 28f, ink, true), spaced())
        options.addView(text(getString(R.string.menu_subtitle), 14f, secondary).apply {
            setPadding(0, dp(4), 0, dp(24))
        })
        fun entry(title: String, detail: String, action: () -> Unit) {
            options.addView(button("$title\n$detail", false) { dialog.dismiss(); action() }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(ink)
                setPadding(dp(20), dp(18), dp(20), dp(18))
                setLineSpacing(dp(6).toFloat(), 1f)
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(tonal),
                    rounded(if (dark) color("#303238") else color("#F3F5F8"), 20), null)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        entry(getString(R.string.menu_settings), getString(R.string.menu_settings_hint)) { showSettings() }
        val themes = resources.getStringArray(R.array.appearance_options)
        val theme = getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", 0).coerceIn(0, 2)
        entry(getString(R.string.appearance_title), themes[theme]) { showThemePicker() }
        entry(getString(R.string.language), LocaleHelper.displayName(LocaleHelper.preferredCode(this), this)) { showLanguagePicker() }
        panel.addView(ScrollView(this).apply { isFillViewport = false; addView(options) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            background = rounded(tonal, 20)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); showAddressSettings() }
        }
        footer.addView(text(getString(R.string.my_number), 13f, secondary))
        footer.addView(text(ownNumber() ?: getString(R.string.address_not_set), 20f, ink, true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        panel.addView(footer, spaced())
        panel.addView(button("×", false) { dialog.dismiss() }.apply {
            textSize = 26f; contentDescription = getString(R.string.close)
            background = rounded(Color.TRANSPARENT, 24)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.START)
            setLayout(minOf(dp(360), resources.displayMetrics.widthPixels - dp(24)), -1)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
    }
    private fun showThemePicker() {
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        android.app.AlertDialog.Builder(this).setTitle(R.string.appearance_title)
            .setSingleChoiceItems(R.array.appearance_options, prefs.getInt("theme", 0)) { dialog, which ->
                prefs.edit().putInt("theme", which).apply()
                dialog.dismiss()
                recreate()
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun showSettings() {
        (settingsBody.parent as? android.view.ViewGroup)?.removeView(settingsBody)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        body.addView(button(getString(R.string.my_number), false) { showAddressSettings() }, spaced())
        body.addView(button(getString(R.string.check_microphone), false) { startActivity(Intent(this, MainActivity::class.java)) }, spaced())
        settingsBody.setPadding(0, dp(20), 0, 0)
        body.addView(settingsBody)
        android.app.AlertDialog.Builder(this).setTitle(R.string.menu_settings)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    override fun onBackPressed() {
        if (dialing) { dialing = false; render() } else super.onBackPressed()
    }
    private fun enableIncoming() {
        val own = ownAddressKey()
        if (own == null) { showAddressSettings(); return }
        if (!consent.isChecked) { Toast.makeText(this, getString(R.string.need_consent), Toast.LENGTH_LONG).show(); return }
        if (CaptureState.current.active) { Toast.makeText(this, getString(R.string.stop_mic_first), Toast.LENGTH_LONG).show(); return }
        prepareBackgroundSession {
            runCatching { CallService.join(this, own, "receiver") }
                .onFailure { Toast.makeText(this, getString(R.string.enable_incoming_failed), Toast.LENGTH_LONG).show() }
        }
    }
    private fun dialAddress() {
        val own = ownAddressKey()
        if (own == null) { showAddressSettings(); return }
        val entered = target.text.toString()
        val destination = PhoneAddress.key(entered)
        if (destination == null) { target.error = getString(R.string.invalid_number); return }
        if (destination == own) { target.error = getString(R.string.cannot_call_self); return }
        if (!consent.isChecked) { Toast.makeText(this, getString(R.string.need_consent), Toast.LENGTH_LONG).show(); return }
        if (CaptureState.current.active) { Toast.makeText(this, getString(R.string.stop_mic_first), Toast.LENGTH_LONG).show(); return }
        addressPrefs().edit().putString("last_target", PhoneAddress.normalize(entered)).apply()
        prepareBackgroundSession {
            runCatching { CallService.join(this, destination, "caller", homeAddress = own, autoInvite = true) }
                .onFailure { Toast.makeText(this, getString(R.string.start_call_failed), Toast.LENGTH_LONG).show() }
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
            else { status.text = getString(R.string.need_notifications); incomingSettings() }
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
            } else status.text = if (code == 21) getString(R.string.need_mic_notif_bg) else getString(R.string.need_mic_permission)
        }
    }
    private fun incomingSettings() {
        val builder = android.app.AlertDialog.Builder(this).setTitle(getString(R.string.incoming_settings_title))
            .setMessage(getString(R.string.incoming_settings_msg))
            .setPositiveButton(getString(R.string.notifications)) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }.setNegativeButton(getString(R.string.close), null)
        if (Build.VERSION.SDK_INT >= 34) builder.setNeutralButton(getString(R.string.over_lockscreen)) { _, _ ->
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
                PushCalls.cancel(this, call); status.text = getString(R.string.call_already_ended); return
            }
            val acceptPush: () -> Unit = {
                prepareBackgroundSession {
                    if (PushCalls.pending(this, code, call) && expires > System.currentTimeMillis() && !CaptureState.current.active) {
                        PushCalls.cancel(this, call)
                        if (engine.state.ringing && engine.state.room == code) engine.acceptExpected(call)
                        else if (!engine.state.connected && !engine.state.connecting) {
                            runCatching { CallService.join(this, code, "receiver", call) }
                                .onFailure { status.text = getString(R.string.accept_failed) }
                        }
                    }
                }
            }
            if (answer) acceptPush() else android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.incoming_call_title)).setMessage(getString(R.string.answer_peer))
                .setPositiveButton(getString(R.string.answer)) { _, _ -> acceptPush() }
                .setNegativeButton(getString(R.string.decline)) { _, _ -> PushCalls.reject(this, code, call) }.show()
            return
        }
        if (intent?.action != CallService.ANSWER) return
        val token = intent.getStringExtra(CallService.TOKEN)
        intent.action = null // A rotation or returning to the activity must not answer twice.
        if (CallNotificationState.validAction(token, engine.actionToken) && engine.state.ringing && engine.state.role == "receiver") withMicrophone { engine.accept() }
    }
    private fun showAddressSettings() {
        if (engine.state.busy) { status.text = getString(R.string.finish_current_first); return }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "+7 701 123 45 67"
            setText(ownNumber().orEmpty())
            setSelection(text.length)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.your_number_title))
            .setMessage(getString(R.string.your_number_msg))
            .setView(input)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = PhoneAddress.normalize(input.text.toString())
                if (normalized == null) input.error = getString(R.string.invalid_number)
                else {
                    addressPrefs().edit().putString("own_number", normalized).apply()
                    ownAddress.text = normalized
                    val address = PhoneAddress.key(normalized)!!
                    if ((engine.state.connected || engine.state.connecting) && (engine.state.role != "receiver" || engine.state.room != address)) {
                        CallService.join(this, address, "receiver")
                    }
                    render()
                    status.text = getString(R.string.address_saved)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    private fun showHelp() {
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.help_title))
            .setMessage(getString(R.string.help_msg))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.check_microphone)) { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }.show()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("dialing", dialing); out.putString("target", target.text.toString()); super.onSaveInstanceState(out) }
    override fun onStop() { CallRuntime.remove(stateObserver); super.onStop() }
    override fun onDestroy() { pendingAction = null; resumeAction = null; super.onDestroy() }

    private fun showLanguagePicker() {
        val codes = LocaleHelper.allOptions()
        val labels = codes.map { LocaleHelper.displayName(it, this) }.toTypedArray()
        val current = LocaleHelper.preferredCode(this)
        val checked = codes.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_language))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = codes[which]
                dialog.dismiss()
                if (selected != current) {
                    LocaleHelper.applyAndRecreate(this, selected)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

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
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color("#305B78FF")), rounded(if (filled) primary else tonal, 28), null)
        setTextColor(if (filled) onPrimary else primary); setOnClickListener { action() }
    }
    private fun icon(view: TextView, name: String, tint: Int) {
        val picture = com.caverock.androidsvg.SVG.getFromAsset(assets, "icons/$name.svg").renderToPicture()
        val bitmap = android.graphics.Bitmap.createBitmap(dp(24), dp(24), android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawPicture(picture, android.graphics.Rect(0, 0, dp(24), dp(24)))
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap).apply { setTint(tint); setBounds(0, 0, dp(24), dp(24)) }
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.compoundDrawablePadding = dp(10)
    }
    private fun selected(button: Button, active: Boolean) {
        button.isSelected = active
        button.backgroundTintList = ColorStateList.valueOf(if (active) primary else tonal)
        button.setTextColor(if (active) onPrimary else primary)
    }
}
