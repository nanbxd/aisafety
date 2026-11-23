package kz.safetyhack.audiotester

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Push only rings; microphone access starts after a visible user action. */
object PushCalls {
    const val ANSWER = "kz.safetyhack.PUSH_ANSWER"
    const val OPEN = "kz.safetyhack.PUSH_OPEN"
    private const val ID = 42
    private val http = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
    private fun prefs(c: Context) = c.getSharedPreferences("push_calls", Context.MODE_PRIVATE)
    private fun manager(c: Context) = c.getSystemService(NotificationManager::class.java)
    fun enable(c: Context, room: String) { prefs(c).edit().putString("room", room).putLong("until", System.currentTimeMillis() + 86400000).apply() }
    fun disable(c: Context) { prefs(c).edit().remove("room").apply(); manager(c).cancel(ID) }
    fun token(c: Context, token: String) { prefs(c).edit().putString("token", token).apply() }
    fun allowed(c: Context, room: String) = prefs(c).getString("room", null) == room && prefs(c).getLong("until", 0) > System.currentTimeMillis()
    fun enabled(c: Context) = prefs(c).getString("room", null) != null && prefs(c).getLong("until", 0) > System.currentTimeMillis()
    fun pending(c: Context, room: String, call: String) = allowed(c, room) && prefs(c).getString("call", null) == call &&
        prefs(c).getLong("expires", 0) > System.currentTimeMillis() && !prefs(c).getStringSet("ended", emptySet())!!.contains(call)
    @Synchronized fun cancel(c: Context, call: String) {
        val p = prefs(c)
        val ended = (p.getStringSet("ended", emptySet()) ?: emptySet()).toMutableSet()
        if (ended.size >= 32) ended.clear()
        ended.add(call)
        p.edit().putStringSet("ended", ended).apply()
        if (p.getString("call", null) == call) manager(c).cancel(ID)
    }
    fun request(c: Context, room: String, call: String, action: String): Boolean {
        val token = prefs(c).getString("token", null) ?: return false
        val origin = RtcPolicy.configuredOrigin(BuildConfig.RTC_BASE_URL) ?: return false
        return runCatching {
            val body = JSONObject().put("room", room).put("call_id", call).put("token", token).put("action", action)
            http.newCall(Request.Builder().url("$origin/api/rtc/push")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
    fun reject(c: Context, room: String, call: String) {
        cancel(c, call)
        Thread { request(c, room, call, "reject") }.start()
    }
    @Synchronized fun show(c: Context, room: String, call: String, expires: Long) {
        if (!allowed(c, room) || expires <= System.currentTimeMillis() ||
            prefs(c).getStringSet("ended", emptySet())!!.contains(call)) return
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = manager(c)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CallService.INCOMING_CHANNEL, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        fun activity(action: String): PendingIntent {
            val intent = Intent(c, RtcActivity::class.java).setAction(action)
                .setData(Uri.parse("safetyhack://push/$call/$action"))
                .putExtra("room", room).putExtra("call_id", call).putExtra("expires_at", expires)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            return PendingIntent.getActivity(c, 30, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
        }
        val reject = PendingIntent.getBroadcast(c, 31, Intent(c, PushRejectReceiver::class.java)
            .setData(Uri.parse("safetyhack://push/$call/reject")).putExtra("room", room).putExtra("call_id", call),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(c, CallService.INCOMING_CHANNEL)
            .setSmallIcon(R.drawable.ic_call_notification).setContentTitle("Входящий звонок Skepticall")
            .setContentText("Собеседник ожидает ответа").setContentIntent(activity(OPEN))
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true).setTimeoutAfter((expires - System.currentTimeMillis()).coerceIn(1, 60000))
        if (Build.VERSION.SDK_INT >= 31) builder.setStyle(Notification.CallStyle.forIncomingCall(
            Person.Builder().setName("Собеседник Skepticall").setImportant(true).build(), reject, activity(ANSWER)))
        else builder.addAction(Notification.Action.Builder(null, "Отклонить", reject).build())
            .addAction(Notification.Action.Builder(null, "Ответить", activity(ANSWER)).build())
        // Cold-start incoming uses CallStyle on the lock screen. Only an explicit
        // notification tap opens the activity; never reveal a previous transcript.
        prefs(c).edit().putString("call", call).putLong("expires", expires).apply()
        nm.notify(ID, builder.build().apply { flags = flags or Notification.FLAG_INSISTENT })
    }
}

class CallMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushCalls.token(this, token)
        android.os.Handler(mainLooper).post { CallRuntime.refreshPush(token) }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val room = message.data["room"] ?: return
        val call = message.data["call_id"] ?: return
        if (!Regex("[A-Za-z0-9_-]{8,64}").matches(room) || !Regex("[A-Za-z0-9_-]{20,64}").matches(call)) return
        if (message.data["type"] == "cancel") { PushCalls.cancel(this, call); return }
        if (message.data["type"] != "incoming" || !PushCalls.allowed(this, room)) return
        val expires = message.data["expires_at"]?.toLongOrNull()?.times(1000) ?: return
        if (expires <= System.currentTimeMillis() || expires > System.currentTimeMillis() + 65000) return
        if (PushCalls.request(this, room, call, "check")) PushCalls.show(this, room, call, expires)
    }
}

class PushRejectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val room = intent.getStringExtra("room") ?: return
        val call = intent.getStringExtra("call_id") ?: return
        val pending = goAsync()
        PushCalls.cancel(context, call)
        Thread { try { PushCalls.request(context, room, call, "reject") } finally { pending.finish() } }.start()
    }
}
