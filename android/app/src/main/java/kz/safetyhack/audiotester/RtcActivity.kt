package kz.safetyhack.audiotester

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*

/** Foreground-only receiver. Authentication and WebRTC belong exclusively to the trusted page. */
class RtcActivity : Activity() {
    companion object { @Volatile var audioSessionOpen = false; private set }
    private var web: WebView? = null
    private var origin = ""
    private var foreground = false
    private var pending: PermissionRequest? = null
    private var consentDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closing = false
    private val openGate = RtcOpenGate()
    private fun hasMicrophonePermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        showSetup()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun showSetup(message: String = "") {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff5f6f2.toInt())
            setPadding(dp(24), dp(20), dp(24), dp(32))
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(dp(24), dp(20) + i.systemWindowInsetTop, dp(24), dp(32) + i.systemWindowInsetBottom); i
            }
        }
        fun label(value: String, size: Float = 18f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xff172b29.toInt())
            setPadding(0, dp(12), 0, dp(8)); body.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; textSize = 18f; isAllCaps = false; minHeight = dp(56)
            body.addView(this, LinearLayout.LayoutParams(-1, -2)); setOnClickListener { action() }
        }
        label("Звонки с поддержкой", 28f)
        label("Оставьте приложение открытым. При сворачивании, повороте экрана или нажатии «Назад» звонок завершается. В фоне входящие звонки не принимаются.")
        val addressLabel = label("Доверенный сервер HTTPS")
        val address = EditText(this).apply {
            id = View.generateViewId()
            setSingleLine(); minHeight = dp(48)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://your-server.example"
            setText(getPreferences(0).getString("rtc_origin", ""))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        addressLabel.labelFor = address.id
        label("Сохраняется только адрес. Комнату и ключ доступа укажите на странице сервера; ключ действует только до закрытия страницы.")
        if (message.isNotEmpty()) label(message).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        button("Сохранить и открыть") {
            val valid = RtcPolicy.configuredOrigin(address.text.toString())
            when {
                valid == null -> address.error = "Введите только HTTPS-адрес сервера, без пути, ключа и параметров"
                CaptureState.current.active -> Toast.makeText(this, "Сначала остановите проверку микрофона", Toast.LENGTH_LONG).show()
                closing || openGate.waitingForPermission -> Unit
                else -> {
                    origin = valid
                    getPreferences(0).edit().putString("rtc_origin", valid).apply()
                    openGate.request(hasMicrophonePermission())
                    if (openGate.waitingForPermission) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31)
                    } else openWhenReady()
                }
            }
        }
        button("Проверить микрофон отдельно") {
            if (!closing && !openGate.waitingForPermission) {
                openGate.cancel()
                startActivity(Intent(this, MainActivity::class.java))
            }
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
    }

    private fun openWhenReady() {
        if (openGate.consume(foreground && !isFinishing && !isDestroyed, hasMicrophonePermission())) openReceiver()
    }

    @Suppress("DEPRECATION")
    private fun openReceiver() {
        if (!foreground || web != null || closing || audioSessionOpen) return
        if (!hasMicrophonePermission()) {
            showSetup("Нет доступа к микрофону. Нажмите «Сохранить и открыть» и разрешите доступ.")
            return
        }
        if (CaptureState.current.active) {
            showSetup("Сначала остановите проверку микрофона, затем откройте сервер.")
            return
        }
        val view = WebView(this)
        web = view
        audioSessionOpen = true
        view.isSaveEnabled = false
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                !RtcPolicy.sameOrigin(origin, r.url.toString())
            override fun shouldOverrideUrlLoading(v: WebView, url: String) =
                !RtcPolicy.sameOrigin(origin, url)
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                if (RtcPolicy.sameOrigin(origin, r.url.toString())) null
                else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                cancelPermission()
                if (!RtcPolicy.sameOrigin(origin, url)) {
                    v.stopLoading()
                    closeReceiver("Переход на другой сервер заблокирован.")
                }
            }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                h.cancel()
                closeReceiver("Ошибка сертификата HTTPS. Проверьте сертификат сервера.")
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) closeReceiver("Не удалось открыть сервер. Проверьте адрес и подключение.")
            }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                closeReceiver("Страница закрылась. Откройте сервер повторно.", false)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                cancelPermission()
                if (!hasMicrophonePermission()) {
                    request.deny()
                    closeReceiver("Доступ к микрофону отозван. Нажмите «Сохранить и открыть», чтобы разрешить его снова.")
                    return
                }
                if (!RtcPolicy.allowAudio(origin, request.origin.toString(), request.resources.toList(),
                        true, true, foreground) || CaptureState.current.active || closing) {
                    request.deny(); return
                }
                pending = request
                // A page request alone is not proof of informed consent: ask explicitly for this origin.
                consentDialog = AlertDialog.Builder(this@RtcActivity)
                    .setTitle("Разрешить микрофон?")
                    .setMessage("Сервер $origin запрашивает микрофон для звонка. Продолжайте только после согласия на странице.")
                    .setNegativeButton("Отмена") { _, _ -> cancelPermission() }
                    .setPositiveButton("Разрешить") { _, _ ->
                        consentDialog = null
                        grantAudio()
                    }
                    .setOnCancelListener { cancelPermission() }.show()
            }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pending === request) cancelPermission()
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff5f6f2.toInt())
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
            addView(TextView(this@RtcActivity).apply {
                text = "Не закрывайте приложение · в фоне звонок завершится"
                textSize = 16f; setTextColor(0xff172b29.toInt()); setPadding(dp(16), dp(8), dp(16), dp(8))
            })
            addView(Button(this@RtcActivity).apply {
                text = "Закрыть звонок и вернуться"; isAllCaps = false; minHeight = dp(48)
                setOnClickListener { closeReceiver() }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(container)
        view.loadUrl("$origin/rtc?role=receiver")
    }

    private fun grantAudio() {
        val request = pending ?: return
        pending = null
        if (!hasMicrophonePermission()) {
            request.deny()
            closeReceiver("Доступ к микрофону отозван. Нажмите «Сохранить и открыть», чтобы разрешить его снова.")
            return
        }
        if (RtcPolicy.allowAudio(origin, request.origin.toString(), request.resources.toList(),
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                true, foreground && web != null && !closing) && !CaptureState.current.active) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else request.deny()
    }

    private fun cancelPermission() {
        val request = pending; pending = null
        consentDialog?.dismiss(); consentDialog = null
        request?.deny()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 31 && openGate.waitingForPermission) {
            val granted = results.isNotEmpty() && hasMicrophonePermission()
            openGate.permissionResult(granted)
            if (granted) openWhenReady()
            else showSetup("Нет доступа к микрофону. Нажмите «Сохранить и открыть» для повтора; при постоянном отказе разрешите микрофон в настройках Android.")
        }
    }

    private fun closeReceiver(message: String = "Звонок закрыт. Для нового звонка откройте сервер снова.", runHook: Boolean = true) {
        cancelPermission()
        val old = web ?: return
        web = null
        closing = true
        var destroyed = false
        fun destroy() {
            if (destroyed) return
            destroyed = true
            (old.parent as? ViewGroup)?.removeView(old)
            old.stopLoading()
            old.clearHistory()
            old.clearCache(true)
            old.destroy()
            audioSessionOpen = false
            closing = false
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        // Stop synchronously in the page; destruction is the bounded fallback if JS is unresponsive.
        if (runHook) {
            runCatching {
                old.evaluateJavascript("(function(){try{if(typeof window.safetyRtcStop==='function')window.safetyRtcStop();}finally{window.location.replace('about:blank');}})();") { destroy() }
            }.onFailure { destroy() }
            handler.postDelayed({ destroy() }, 250)
        } else destroy()
        if (!isFinishing && !isDestroyed) showSetup(message)
    }

    override fun onResume() { super.onResume(); foreground = true; openWhenReady() }
    override fun onPause() {
        foreground = false
        closeReceiver()
        super.onPause()
    }
    @Deprecated("Activity back dispatch")
    override fun onBackPressed() {
        openGate.cancel()
        if (web != null) closeReceiver() else super.onBackPressed()
    }
    override fun onDestroy() { openGate.cancel(); closeReceiver(); super.onDestroy() }
}
