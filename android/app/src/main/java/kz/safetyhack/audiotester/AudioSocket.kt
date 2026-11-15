package kz.safetyhack.audiotester

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale

/** No audio is retained while awaiting ready; at most 32 KB is queued. */
class AudioSocket(endpoint: String, token: String) : WebSocketListener() {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var ready = false
    private var failed = false
    private var stopping = false
    private var disposed = false
    private val done = CountDownLatch(1)
    private val started = System.nanoTime()

    init {
        CaptureState.update { it.copy(network = "Подключение… Ожидаем готовности сервера") }
        socket = client.newWebSocket(Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $token").build(), this)
    }

    @Synchronized override fun onOpen(webSocket: WebSocket, response: Response) {
        if (failed || stopping) { webSocket.cancel(); return }
        if (!webSocket.send(JSONObject().put("type", "start").put("sample_rate", 16000)
                .put("channels", 1).put("format", "pcm_s16le").put("language", "ru").toString())) {
            fail("Не удалось отправить команду начала записи")
        }
    }

    @Synchronized override fun onMessage(webSocket: WebSocket, text: String) {
        if (failed || disposed) return
        if (text.length > 65536) { fail("Сервер прислал слишком большое сообщение"); return }
        try {
            val event = JSONObject(text)
            when (event.getString("type")) {
                "ready" -> if (!stopping) {
                    ready = true
                    val mode = event.optString("mode")
                    val description = when (mode) {
                        "diagnostic" -> "Диагностика звука — без распознавания речи"
                        "whisper" -> "Распознавание речи включено (Whisper)"
                        else -> "Сервер не указал режим распознавания речи"
                    }
                    CaptureState.update { it.copy(network = "Звук передаётся на сервер", serverMode = description, diagnostic = mode == "diagnostic") }
                }
                "level" -> {
                    val dbfs = event.getDouble("dbfs")
                    val seconds = event.getDouble("audio_seconds")
                    require(dbfs.isFinite() && seconds.isFinite() && seconds >= 0)
                    val sound = if (event.getBoolean("silent")) "Тишина или слишком тихий звук" else "Звук поступает"
                    val reading = String.format(Locale.forLanguageTag("ru-RU"),
                        "%s · %.1f дБFS\nПолучено звука: %.1f сек.", sound, dbfs, seconds)
                    CaptureState.update { it.copy(serverLevel = reading) }
                }
                "transcript" -> CaptureState.update {
                    it.copy(transcript = (it.transcript + "\n" + event.getString("text")).takeLast(12000).trim())
                }
                "risk" -> {
                    val level = event.getString("level")
                    require(level in listOf("high", "medium", "low"))
                    val riskLabel = when (level) { "high" -> "Высокий"; "medium" -> "Средний"; else -> "Низкий" }
                    val reasons = event.optJSONArray("reasons")
                    val explanation = if (reasons == null) "" else (0 until reasons.length())
                        .joinToString("; ") { reasons.getString(it) }
                    CaptureState.update { it.copy(risk = "$riskLabel: ${event.optString("message")}\n$explanation".take(4000)) }
                }
                "error" -> fail("Ошибка сервера: ${event.optString("message", "Повторите проверку или обратитесь к организатору демонстрации").take(500)}")
                "done" -> {
                    if (!stopping) fail("Сервер завершил передачу раньше времени")
                    else CaptureState.update { it.copy(network = "Сервер завершил обработку") }
                    ready = false
                    done.countDown()
                }
                else -> fail("Сервер прислал неизвестное сообщение")
            }
        } catch (_: Exception) { fail("Не удалось прочитать ответ сервера") }
    }

    @Synchronized fun send(data: ByteArray, count: Int) {
        if (failed || stopping) return
        if (!ready) {
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(10)) fail("Сервер не подтвердил готовность за 10 секунд")
            return
        }
        val ws = socket ?: return
        if (ws.queueSize() + count > 32000 || !ws.send(data.toByteString(0, count))) {
            fail("Соединение закрыто или сервер не успевает принимать звук")
        }
    }

    @Synchronized private fun fail(reason: String) {
        if (failed || disposed) return
        failed = true
        ready = false
        CaptureState.update { it.copy(network = if (stopping) "$reason. Запись сохранена на телефоне." else "$reason. Передача остановлена; запись на телефоне продолжается.") }
        socket?.cancel()
        done.countDown()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        fail("Ошибка соединения${response?.let { " (код ${it.code})" } ?: ""}. Проверьте адрес сервера, ключ доступа и сеть")
    }
    @Synchronized override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        if (!stopping || done.count > 0) fail("Сервер закрыл соединение до завершения обработки (код $code)")
        webSocket.close(code, null)
    }

    fun finish() {
        synchronized(this) {
            stopping = true
            if (!failed && ready) {
                CaptureState.update { it.copy(network = "Запись остановлена. Ожидаем результат сервера — до 2 минут") }
                if (socket?.send("{\"type\":\"stop\"}") != true) fail("Не удалось отправить команду остановки")
            } else done.countDown()
            ready = false
        }
        if (!done.await(120, TimeUnit.SECONDS)) {
            synchronized(this) { fail("Сервер не завершил обработку за 2 минуты") }
        }
        synchronized(this) { disposed = true }
        socket?.close(1000, "Capture stopped")
        socket?.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
