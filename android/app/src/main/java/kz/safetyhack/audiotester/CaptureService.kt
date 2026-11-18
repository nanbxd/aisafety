package kz.safetyhack.audiotester

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.io.File
import kotlin.math.*

class CaptureService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { running = false; return START_NOT_STICKY }
        if (RtcActivity.audioSessionOpen) { stopSelf(); return START_NOT_STICKY }
        if (worker != null || intent == null) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("capture", "Проверка микрофона", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "capture").setContentTitle("Идёт запись с микрофона")
                .setContentText("Запись остановится через 30 секунд").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notification)
        } catch (_: Exception) {
            CaptureState.update { it.copy(active = false, status = "Не удалось включить микрофон. Разрешите доступ к нему и повторите попытку в открытом приложении.") }
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        CaptureState.update { Snapshot(active = true, status = "Включаем микрофон…") }
        worker = Thread({ capture(intent) }, "microphone-capture").also { it.start() }
        return START_NOT_STICKY
    }

    private fun capture(intent: Intent) {
        var recorder: AudioRecord? = null
        var stream: AudioSocket? = null
        var wav: WavFile? = null
        var success = false
        try {
            val source = intent.getIntExtra("source", MediaRecorder.AudioSource.MIC)
            require(source in listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION))
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Формат записи 16 кГц, моно не поддерживается" }
            recorder = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 16000)).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Не удалось подготовить микрофон" }
            wav = WavFile(File(filesDir, "latest-test.wav"))
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон не включился" }
            if (intent.getBooleanExtra("consent", false)) {
                try { stream = AudioSocket(intent.getStringExtra("endpoint")!!) }
                catch (_: Exception) { CaptureState.update { it.copy(network = "Проверьте настройки соединения. Запись на телефоне продолжается.") } }
            }
            CaptureState.update { it.copy(status = "Идёт запись · не более 30 секунд") }
            val samples = ShortArray(4000)
            val pcm = ByteArray(8000)
            var total = 0
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (running && total < 480000 && SystemClock.elapsedRealtime() < deadline) {
                val count = recorder.read(samples, 0, minOf(samples.size, 480000 - total), AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Не удалось получить звук с микрофона (код $count)" }
                if (count == 0) { Thread.sleep(10); continue }
                var energy = 0.0
                repeat(count) { i ->
                    val sample = samples[i].toInt()
                    pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
                    energy += sample.toDouble() * sample
                }
                wav.write(pcm, count * 2)
                stream?.send(pcm, count * 2)
                total += count
                val db = maxOf(-90.0, 20 * log10(maxOf(sqrt(energy / count) / 32768.0, 0.000001)))
                CaptureState.update { it.copy(db = db, seconds = total / 16000) }
            }
            success = total > 0
            CaptureState.update { it.copy(status = if (success) "Сохраняем запись и ожидаем результат" else "Микрофон не передал звук") }
        } catch (e: Exception) {
            val reason = when (e) {
                is SecurityException -> "Нет доступа к микрофону. Проверьте разрешения приложения."
                is java.io.IOException -> "Не удалось сохранить звук. Проверьте свободное место на телефоне."
                else -> "Микрофон недоступен или формат записи не поддерживается. Попробуйте другой режим микрофона."
            }
            CaptureState.update { it.copy(status = "Ошибка записи. $reason") }
        } finally {
            running = false
            try { recorder?.stop() } catch (_: Exception) { }
            recorder?.release()
            var path: String? = null
            try { wav?.close(); if (success) path = wav?.file?.absolutePath }
            catch (_: Exception) { success = false; CaptureState.update { it.copy(status = "Не удалось сохранить запись") } }
            try { stream?.finish() } catch (_: Exception) { CaptureState.update { it.copy(network = "Не удалось завершить соединение с сервером") } }
            CaptureState.update { it.copy(active = false, wav = path, db = -90.0,
                status = if (success) "Запись готова · можно прослушать" else it.status) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}
