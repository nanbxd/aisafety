package kz.safetyhack.audiotester

import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** Signaling, media and analysis are native; all UI/state transitions run on the main looper. */
class NativeRtcClient(context: Context, private val changed: () -> Unit) {
    private val appContext = context.applicationContext
    private var pushAnswer: String? = null
    data class State(
        var connected: Boolean = false, var connecting: Boolean = false, var peer: Boolean = false,
        var busy: Boolean = false, var ringing: Boolean = false, var inCall: Boolean = false,
        var muted: Boolean = false, var speaker: Boolean = false,
        var room: String = "", var role: String = "receiver", var status: String = "Настройте свой номер, чтобы начать",
        var analysis: String = "Анализ ещё не начался", var transcript: String = "",
        var analysisUnavailable: Boolean = false,
        var risk: String = "Оценки пока нет", var riskLevel: String = "unknown"
    )
    val state = State()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private var audioOwned = false
    private var routeListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private var signal: WebSocket? = null
    private var analysisSocket: WebSocket? = null
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var remoteTrack: AudioTrack? = null
    private var remoteSink: AudioTrackSink? = null
    val actionIdentity get() = sessionGeneration to callGeneration
    private val instanceId = java.util.UUID.randomUUID().toString()
    val actionToken get() = "$instanceId:$sessionGeneration:$callGeneration"
    private var callId: String? = null
    private var sessionGeneration = 0
    private var callGeneration = 0
    private var iceServers = emptyList<PeerConnection.IceServer>()
    private val pendingIce = mutableListOf<IceCandidate>()
    private val localIce = mutableListOf<IceCandidate>()
    private var localDescriptionSent = false
    @Volatile private var analysisReady = false
    private var released = false
    private var signalTimer: Runnable? = null
    private var callTimer: Runnable? = null
    private var analysisTimer: Runnable? = null
    private val origin = RtcPolicy.configuredOrigin(BuildConfig.RTC_BASE_URL)

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        adm = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        if (origin == null) state.status = "Сервис ещё не настроен. Нужна сборка с адресом сервера от организатора."
    }
    private fun notifyChange() { if (!released) changed() }
    private fun timer(delay: Long, action: () -> Unit): Runnable = Runnable(action).also { main.postDelayed(it, delay) }
    private fun cancel(task: Runnable?) { task?.let(main::removeCallbacks) }
    private fun request(path: String) = Request.Builder().url(origin!!.replaceFirst("https://", "wss://") + path).header("Origin", origin).build()
    private fun json(type: String) = JSONObject().put("type", type)
    private fun send(event: JSONObject) {
        if (signal?.send(event.toString()) != true) throw IllegalStateException("Сигнальный канал недоступен")
    }
    private fun callSend(type: String, configure: (JSONObject) -> Unit = {}) {
        val id = callId ?: return
        send(json(type).put("call_id", id).also(configure))
    }
    fun connect(room: String, role: String, resumeCall: String? = null) {
        if (released || state.connected || state.connecting) return
        if (origin == null) { state.status = "Сервис ещё не настроен. Установите сборку с адресом сервера."; notifyChange(); return }
        if (!Regex("[A-Za-z0-9_-]{8,64}").matches(room) || role !in listOf("caller", "receiver")) return
        val generation = ++sessionGeneration
        pushAnswer = resumeCall
        state.room = room; state.role = role; state.connecting = true; state.status = "Подключаем адрес…"
        signal = http.newWebSocket(request("/ws/rtc"), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { main.post {
                if (generation != sessionGeneration) { ws.close(1000, null); return@post }
                ws.send(json("join").put("room", room).put("role", role).put("consent", true).apply {
                    resumeCall?.let { put("resume_call", it) }
                }.toString())
            } }
            override fun onMessage(ws: WebSocket, text: String) { main.post {
                if (generation == sessionGeneration) runCatching { handle(JSONObject(text)) }.onFailure { disconnect("Соединение отклонено. Адрес уже используется или недоступен.") }
            } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { main.post {
                if (generation == sessionGeneration) disconnect("Сервер недоступен. Проверьте сеть и войдите снова.")
            } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { main.post {
                if (generation == sessionGeneration) disconnect("Соединение закрыто. Включите входящие снова.")
            } }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
        })
        signalTimer = timer(20000) { if (generation == sessionGeneration && !state.connected) disconnect("Сервер не ответил. Попробуйте войти ещё раз.") }
        notifyChange()
    }
    private fun handle(event: JSONObject) {
        when (event.getString("type")) {
            "ready" -> {
                check(!state.connected && event.getString("room") == state.room && event.getString("role") == state.role)
                val list = event.getJSONArray("ice_servers")
                iceServers = (0 until list.length()).map { i ->
                    val entry = list.getJSONObject(i)
                    val urls = entry.get("urls")
                    val values = if (urls is JSONArray) (0 until urls.length()).map { urls.getString(it) } else listOf(urls.toString())
                    PeerConnection.IceServer.builder(values).setUsername(entry.optString("username")).setPassword(entry.optString("credential")).createIceServer()
                }
                cancel(signalTimer); state.connected = true; state.connecting = false; state.peer = event.optBoolean("peer_present")
                idleStatus()
                if (state.role == "receiver") {
                    PushCalls.enable(appContext, state.room)
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        if (state.connected && state.role == "receiver") registerPush(token)
                    }.addOnFailureListener {
                        if (state.connected && state.role == "receiver") {
                            state.status = "Входящие включены, но push недоступен: проверьте Google Play services и сеть"
                            notifyChange()
                        }
                    }
                }
            }
            "push-ready" -> { if (!state.busy) state.status = if (event.optBoolean("enabled")) "Входящие включены, в том числе через push" else "Входящие включены. Push ещё не настроен на сервере" }
            "error" -> disconnect("Сервер отклонил действие. Адрес уже используется или недоступен.")
            "peer-joined" -> { state.peer = true; if (!state.busy) idleStatus() }
            "peer-left" -> { state.peer = event.optBoolean("push_available"); stopCall(); state.status = if (state.role == "receiver") "Входящие включены" else if (state.peer) "Абонент доступен через push" else "Абонент отключился" }
            "incoming" -> {
                val incomingId = event.getString("call_id")
                // A resumed receiver can observe the same call through the live socket and FCM path.
                // Treat that delivery as idempotent instead of dropping the whole address session.
                if (callId == incomingId && state.ringing) return
                if (callId != null) return
                // Caller already called beginCall() in invite() and is busy with callId still null.
                // Blocking on busy here dropped "incoming", so callId never bound and every later
                // accepted/offer/answer/ice event was ignored — accept appeared to do nothing.
                if (state.role == "receiver") {
                    if (state.busy) return
                    beginCall()
                } else if (!state.busy) {
                    // Protocol: caller only receives incoming after a local invite.
                    return
                }
                callId = incomingId
                state.ringing = true
                // A manually reopened receiver may already have a push notification.
                // The connected call service now owns ringing; suppress late duplicates.
                if (state.role == "receiver") PushCalls.cancel(appContext, callId!!)
                state.status = if (state.role == "receiver") "Входящий звонок" else "Вызываем собеседника…"
                cancel(callTimer); callTimer = timer(70000) { end() }
                if (pushAnswer == callId) { pushAnswer = null; accept() }
            }
            else -> {
                if (callId == null || event.optString("call_id") != callId) return
                when (event.getString("type")) {
                    "ended", "rejected" -> { stopCall(); state.status = if (event.optString("reason") == "push_unavailable") "Не удалось доставить входящий. Проверьте Firebase на сервере." else "Разговор завершён. Можно позвонить снова." }
                    "accepted" -> {
                        check(track != null)
                        state.ringing = false; state.inCall = true; state.status = "Устанавливаем аудиосвязь…"
                        createPeer()
                        if (state.role == "caller") createDescription(true) else openAnalysis(event.getString("audio_ticket"))
                    }
                    "offer" -> setRemote(SessionDescription(SessionDescription.Type.OFFER, event.getString("sdp"))) { createDescription(false) }
                    "answer" -> setRemote(SessionDescription(SessionDescription.Type.ANSWER, event.getString("sdp"))) {}
                    "ice" -> if (!event.isNull("candidate")) {
                        val c = event.getJSONObject("candidate")
                        val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"))
                        if (pc?.remoteDescription == null) { check(pendingIce.size < 256); pendingIce.add(candidate) } else pc?.addIceCandidate(candidate)
                    }
                }
            }
        }
        notifyChange()
    }
    private fun idleStatus() {
        state.status = if (state.role == "receiver") "Входящие включены"
        else if (state.peer) "Адрес найден" else "Адрес пока недоступен"
    }
    private fun beginCall() {
        callGeneration++; state.busy = true; state.muted = false
        state.analysisUnavailable = false; state.transcript = ""; state.risk = "Оценки пока нет"; state.riskLevel = "unknown"
        state.analysis = if (state.role == "receiver") "Ожидаем начала разговора" else "Анализ доступен принимающему участнику"
    }
    private fun microphone() {
        if (track != null) return
        oldMode = audioManager.mode; oldSpeaker = audioManager.isSpeakerphoneOn; audioOwned = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            routeListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (audioOwned) {
                    state.speaker = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    notifyChange()
                }
            }.also { audioManager.addOnCommunicationDeviceChangedListener(appContext.mainExecutor, it) }
            audioManager.clearCommunicationDevice()
            state.speaker = audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            audioManager.isSpeakerphoneOn = false; state.speaker = audioManager.isSpeakerphoneOn
        }
        source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("microphone", source).apply { setEnabled(true) }
    }
    fun invite() {
        if (!state.connected || !state.peer || state.busy) return
        runCatching { beginCall(); microphone(); send(json("invite")); state.status = "Отправляем вызов…"
            callTimer = timer(15000) { failCall("Сервер не подтвердил вызов. Попробуйте позвонить снова.") }
        }.onFailure { stopCall(); state.status = "Микрофон недоступен. Проверьте разрешение." }
        notifyChange()
    }
    fun accept() {
        if (!state.ringing || state.role != "receiver") return
        runCatching { microphone(); callSend("accept"); state.ringing = false; state.status = "Подключаем разговор…" }
            .onFailure { failCall("Не удалось принять звонок. Проверьте микрофон и попробуйте снова.") }
        notifyChange()
    }
    fun acceptExpected(expected: String) { if (callId == expected) accept() }
    private fun createPeer() {
        val generation = callGeneration
        fun active() = !released && generation == callGeneration && state.busy
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        pc = factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { main.post {
                if (!active()) return@post
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> { cancel(callTimer); state.status = "Разговор идёт"; notifyChange() }
                    PeerConnection.IceConnectionState.FAILED -> failCall("Аудиосвязь не установлена. Проверьте сеть и попробуйте снова.")
                    PeerConnection.IceConnectionState.DISCONNECTED -> { state.status = "Связь прервалась. Восстанавливаем…"; cancel(callTimer); callTimer = timer(10000) { if (active()) failCall("Аудиосвязь потеряна. Можно позвонить снова.") }; notifyChange() }
                    else -> Unit
                }
            } }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) { main.post { if (active()) {
                if (localDescriptionSent) sendIce(c) else localIce.add(c)
            } } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val audio = receiver.track() as? AudioTrack ?: return
                main.post { if (active()) {
                    remoteTrack = audio; audio.setEnabled(true)
                    if (state.role == "receiver") {
                        val sink = makeRemoteSink(generation)
                        remoteSink = sink
                        audio.addSink(sink)
                    }
                } }
            }
        }) ?: error("WebRTC initialization failed")
        pc!!.addTrack(track!!, listOf("voice"))
        cancel(callTimer); callTimer = timer(30000) { if (active()) failCall("Время подключения истекло. Проверьте сеть и попробуйте снова.") }
    }
    private fun sendIce(c: IceCandidate) {
        runCatching { callSend("ice") { it.put("candidate", JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)) } }
            .onFailure { failCall("Не удалось передать параметры связи. Можно позвонить снова.") }
    }
    private fun observer(success: (SessionDescription?) -> Unit) = object : SdpObserver {
        private val generation = callGeneration
        private fun dispatch(action: () -> Unit) { main.post { if (generation == callGeneration && state.busy) action() } }
        override fun onCreateSuccess(sdp: SessionDescription) = dispatch { success(sdp) }
        override fun onSetSuccess() = dispatch { success(null) }
        override fun onCreateFailure(message: String) = dispatch { failCall("Не удалось согласовать аудиосвязь. Можно позвонить снова.") }
        override fun onSetFailure(message: String) = onCreateFailure(message)
    }
    private fun createDescription(offer: Boolean) {
        val callback = observer { description ->
            val sdp = description ?: return@observer
            pc?.setLocalDescription(observer {
                runCatching { callSend(if (offer) "offer" else "answer") { it.put("sdp", sdp.description) }; localDescriptionSent = true; localIce.toList().forEach(::sendIce); localIce.clear() }
                    .onFailure { failCall("Не удалось передать параметры звонка. Можно позвонить снова.") }
            }, sdp)
        }
        if (offer) pc?.createOffer(callback, MediaConstraints()) else pc?.createAnswer(callback, MediaConstraints())
    }
    private fun setRemote(description: SessionDescription, done: () -> Unit) {
        check(pc != null)
        pc!!.setRemoteDescription(observer {
            pendingIce.forEach { pc?.addIceCandidate(it) }; pendingIce.clear(); done()
        }, description)
    }
    // WebRTC's remote sink supplies decoded PCM from the other participant, never the local microphone.
    private fun makeRemoteSink(generation: Int): AudioTrackSink {
        val batcher = PcmBatcher()
        val pending = java.util.concurrent.atomic.AtomicInteger(0)
        val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
        return AudioTrackSink { data, bits, rate, channels, frames, _ ->
            if (analysisReady && bits == 16 && rate >= 16000 && channels in 1..8) {
                for (pcm in batcher.append(RemotePcm.to16k(data, rate, channels, frames))) {
                    if (pending.incrementAndGet() > 10) {
                        pending.decrementAndGet()
                        if (overflow.compareAndSet(false, true)) main.post {
                            if (generation == callGeneration) analysisFailure("Обработка звука не успевает за разговором. Анализ неполный.")
                        }
                        break
                    }
                    main.post {
                        pending.decrementAndGet()
                        if (generation == callGeneration && analysisReady && state.inCall) {
                            val socket = analysisSocket
                            if (socket == null || socket.queueSize() + pcm.size > 32768 || !socket.send(pcm.toByteString())) analysisFailure("Сеть не успевает передавать звук. Анализ неполный; разговор продолжается.")
                        }
                    }
                }
            }
        }
    }
    private var analysisAttempts = 0
    private var analysisTicket: String? = null
    private var analysisEnded = false
    private fun openAnalysis(ticket: String) {
        analysisTicket = ticket
        analysisAttempts = 0
        analysisEnded = false
        state.analysis = "Подключаем распознавание…"
        notifyChange()
        attemptAnalysis()
    }
    private fun attemptAnalysis() {
        val ticket = analysisTicket ?: return
        if (analysisEnded || !state.busy) return
        val generation = callGeneration
        analysisAttempts++
        val ws = http.newWebSocket(request("/ws/rtc-audio"), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { main.post {
                if (generation != callGeneration || analysisSocket !== ws || analysisEnded) { ws.close(1000, null); return@post }
                // Contract matches server/rtc.py /ws/rtc-audio and the /rtc web client.
                ws.send(json("start")
                    .put("audio_ticket", ticket)
                    .put("room", state.room)
                    .put("call_id", callId)
                    .put("sample_rate", 16000)
                    .put("channels", 1)
                    .put("format", "pcm_s16le")
                    .put("consent", true)
                    .put("analysis_provider", "google")
                    .put("language", "ru")
                    .toString())
            } }
            override fun onMessage(ws: WebSocket, text: String) { main.post {
                if (generation != callGeneration || analysisSocket !== ws || analysisEnded) return@post
                runCatching {
                    val e = JSONObject(text)
                    when (e.getString("type")) {
                        "ready" -> {
                            cancel(analysisTimer)
                            if (e.optString("mode") != "gemini") {
                                analysisFailure("Распознавание недоступно в режиме сервера. Разговор продолжается без анализа.")
                            } else {
                                analysisReady = true
                                analysisTicket = null
                                state.analysis = "Gemini Live · ожидаем речь собеседника"
                            }
                        }
                        "transcript" -> if (analysisReady) {
                            state.transcript = (state.transcript + "\n" + e.optString("text").take(12000)).trim().takeLast(24000)
                            state.analysis = "Gemini Live · речь распознаётся"
                        }
                        "risk" -> if (analysisReady) {
                            state.riskLevel = e.optString("level")
                            val label = when (state.riskLevel) {
                                "high" -> "Тревожные признаки"
                                "medium" -> "Проверьте просьбу"
                                "low" -> "Явных признаков не отмечено. Это не гарантия безопасности."
                                else -> "Оценка недоступна"
                            }
                            val reasons = e.optJSONArray("reasons")
                            state.risk = listOf(
                                label,
                                e.optString("message").take(4000),
                                if (reasons == null) "" else (0 until minOf(reasons.length(), 20)).joinToString("\n") { reasons.optString(it).take(2000) }
                            ).filter { it.isNotBlank() }.joinToString("\n\n")
                        }
                        "done" -> analysisComplete(
                            if (state.transcript.isNotBlank()) "Анализ завершён. Разговор можно продолжать без распознавания."
                            else "Анализ завершён. Речь собеседника не была распознана."
                        )
                        "error" -> {
                            val code = e.optString("code")
                            // Busy before ready: ticket is not consumed; retry like the web client.
                            if (code == "busy" && !analysisReady) {
                                cancel(analysisTimer)
                                val current = analysisSocket
                                analysisSocket = null
                                current?.close(1000, null)
                                if (analysisAttempts >= 20) {
                                    analysisFailure("Анализ не запустился: сервер остаётся занят. Разговор продолжается без проверки.")
                                    return@runCatching
                                }
                                state.analysis = "Сервер завершает предыдущий анализ. Повторяем подключение…"
                                notifyChange()
                                main.postDelayed({
                                    if (generation == callGeneration && !analysisEnded && analysisTicket != null) attemptAnalysis()
                                }, 750)
                                return@runCatching
                            }
                            val detail = when (code) {
                                "quota_exceeded" -> "Лимит Google Gemini исчерпан."
                                "model_unavailable" -> "Модель Gemini Live недоступна."
                                "authentication_failed" -> "Google отклонил доступ к Gemini."
                                "session_expired" -> "Сеанс Gemini Live истёк."
                                "upstream_closed" -> "Связь с Google Gemini потеряна."
                                "processing_timeout" -> "Google Gemini не завершил обработку вовремя."
                                "upstream_error" -> "Ошибка Google Gemini."
                                "consent_required" -> "Нужно согласие на отправку аудио в Google."
                                else -> "Анализ остановлен сервером."
                            }
                            analysisFailure("$detail Разговор продолжается; оценка неполная.")
                        }
                    }
                    notifyChange()
                }.onFailure { analysisFailure("Неверный ответ сервера анализа. Разговор продолжается без проверки.") }
            } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { main.post {
                if (generation == callGeneration && analysisSocket === ws && !analysisEnded) {
                    analysisFailure("Анализ недоступен. Разговор продолжается без проверки.")
                }
            } }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { main.post {
                // Ignore clean close after done/error; only flag unexpected drops mid-stream.
                if (generation == callGeneration && analysisSocket === ws && !analysisEnded && analysisReady) {
                    analysisFailure("Связь с анализом потеряна. Оценка неполная.")
                } else if (analysisSocket === ws) {
                    analysisSocket = null
                }
            } }
        })
        analysisSocket = ws
        analysisTimer = timer(15000) {
            if (generation == callGeneration && !analysisReady && !analysisEnded) {
                analysisFailure("Анализ не запустился вовремя. Разговор продолжается без проверки.")
            }
        }
    }
    private fun analysisComplete(message: String) {
        if (analysisEnded) return
        analysisEnded = true
        analysisReady = false
        analysisTicket = null
        cancel(analysisTimer)
        val ws = analysisSocket; analysisSocket = null; ws?.close(1000, null)
        state.analysis = message
        notifyChange()
    }
    private fun analysisFailure(message: String) {
        if (analysisEnded) return
        analysisEnded = true
        analysisReady = false
        analysisTicket = null
        cancel(analysisTimer)
        val ws = analysisSocket; analysisSocket = null; ws?.close(1000, null)
        state.analysisUnavailable = true; state.analysis = message; notifyChange()
    }

    fun end(type: String = "hangup") {
        if (!state.busy) return
        if (callId == null) { failCall("Вызов отменён. Можно позвонить снова."); return }
        runCatching { callSend(type) }.onFailure { failCall("Связь потеряна. Разговор завершён."); return }
        stopCall(); state.status = "Разговор завершён"; notifyChange()
        // Server handles ordered hangup before any subsequent invite on this WebSocket.
    }
    fun toggleMute() { if (state.inCall) { state.muted = !state.muted; track?.setEnabled(!state.muted); notifyChange() } }
    fun toggleSpeaker(): Boolean {
        if (!state.inCall || !audioOwned) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val enable = audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                val devices = audioManager.availableCommunicationDevices
                val device = if (enable) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?: devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (device == null || !audioManager.setCommunicationDevice(device)) return false
                // The device-change listener updates the UI once Android applies the route.
                state.speaker = audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                audioManager.isSpeakerphoneOn = !audioManager.isSpeakerphoneOn
                state.speaker = audioManager.isSpeakerphoneOn
            }
            notifyChange()
            true
        }.getOrDefault(false)
    }
    private fun stopCall() {
        callGeneration++; cancel(callTimer); cancel(analysisTimer)
        analysisReady = false
        analysisEnded = true
        analysisTicket = null
        analysisAttempts = 0
        val ws = analysisSocket; analysisSocket = null; ws?.close(1000, null)
        remoteSink?.let { remoteTrack?.removeSink(it) }; remoteSink = null; remoteTrack = null
        pc?.close(); pc?.dispose(); pc = null
        track?.dispose(); track = null; source?.dispose(); source = null
        if (audioOwned) {
            audioOwned = false
            if (Build.VERSION.SDK_INT >= 31) {
                routeListener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
                routeListener = null
                audioManager.clearCommunicationDevice()
            } else audioManager.isSpeakerphoneOn = oldSpeaker
            audioManager.mode = oldMode
        }
        state.speaker = false
        if (state.inCall && state.role == "receiver") state.analysis = "Разговор завершён. Последние фразы могли не попасть в анализ."
        callId = null; localDescriptionSent = false; pendingIce.clear(); localIce.clear()
        state.busy = false; state.ringing = false; state.inCall = false; state.muted = false
    }
    private fun failCall(message: String) {
        stopCall()
        state.status = message
        notifyChange()
    }
    fun disconnect(message: String) {
        pushAnswer = null
        sessionGeneration++; cancel(signalTimer); stopCall()
        val ws = signal; signal = null; ws?.close(1000, null)
        state.connected = false; state.connecting = false; state.peer = false; state.status = message
        notifyChange()
    }
    fun registerPush(token: String) {
        PushCalls.token(appContext, token)
        if (state.connected && state.role == "receiver") runCatching { send(json("push-register").put("token", token)) }
    }
    fun leave() {
        if (state.role == "receiver") {
            runCatching { send(json("push-unregister")) }
        }
        PushCalls.disable(appContext)
        disconnect(if (state.role == "receiver") "Входящие отключены" else "Вызов отменён")
    }
    fun release() {
        if (released) return
        disconnect("Разговор завершён"); released = true
        factory.dispose(); adm.release(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
