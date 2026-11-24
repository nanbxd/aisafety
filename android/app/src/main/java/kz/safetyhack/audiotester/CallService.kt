package kz.safetyhack.audiotester

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager

/** Started from a visible, permission-granted activity; survives Home, Back and task removal. */
class CallService : Service() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(LocaleHelper.wrap(base)) }

    companion object {
        const val JOIN = "kz.safetyhack.JOIN"
        const val ANSWER = "kz.safetyhack.ANSWER"
        const val DECLINE = "kz.safetyhack.DECLINE"
        const val HANGUP = "kz.safetyhack.HANGUP"
        const val LEAVE = "kz.safetyhack.LEAVE"
        const val TOKEN = "call_token"
        const val INCOMING_CHANNEL = "incoming_calls_v1"
        private const val SESSION_CHANNEL = "call_session_v1"
        private const val SESSION_ID = 40
        private const val INCOMING_ID = 41
        fun join(context: Context, room: String, role: String, resumeCall: String? = null,
                 homeAddress: String? = null, autoInvite: Boolean = false) {
            context.startForegroundService(Intent(context, CallService::class.java).setAction(JOIN)
                .putExtra("room", room).putExtra("role", role).putExtra("consent", true)
                .putExtra("resume_call", resumeCall).putExtra("home_address", homeAddress)
                .putExtra("auto_invite", autoInvite))
        }
    }

    private lateinit var engine: NativeRtcClient
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val observer: () -> Unit = { update() }
    private var started = false
    private var stopping = false
    private var incomingToken: String? = null
    private var lastSummary = ""
    private var switching = false
    private var pendingInvite = false
    private var returnAddress: String? = null
    private var outgoingStarted = false

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        engine = CallRuntime.client(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(SESSION_CHANNEL, getString(R.string.localized_message_86), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.localized_message_87)
            setSound(null, null)
        })
        notifications.createNotificationChannel(NotificationChannel(INCOMING_CHANNEL, getString(R.string.notif_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.notif_channel_incoming_desc)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 500, 700, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Skepticall:Call").apply { setReferenceCounted(false) }
        CallRuntime.observe(observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == JOIN) {
            val room = intent.getStringExtra("room") ?: ""
            val role = intent.getStringExtra("role") ?: ""
            if (!intent.getBooleanExtra("consent", false) || !Regex("[A-Za-z0-9_-]{8,64}").matches(room) || role !in listOf("caller", "receiver") || CaptureState.current.active) {
                stopSelf(); return START_NOT_STICKY
            }
            try {
                check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                check(notifications.areNotificationsEnabled())
                if (!started) {
                    val notification = sessionNotification(getString(R.string.status_connecting_address))
                    // Declare microphone access while the app is visible, before it can move to the background.
                    if (Build.VERSION.SDK_INT >= 29) startForeground(SESSION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(SESSION_ID, notification)
                    started = true
                }
                if (role == "caller") {
                    returnAddress = intent.getStringExtra("home_address")
                    pendingInvite = intent.getBooleanExtra("auto_invite", false)
                    outgoingStarted = false
                } else {
                    returnAddress = null
                    pendingInvite = false
                    outgoingStarted = false
                }
                switchSession(room, role, intent.getStringExtra("resume_call"))
                update()
            } catch (_: Exception) {
                engine.disconnect(getString(R.string.status_background_failed))
                stopSelf()
            }
        } else if (started && CallNotificationState.validAction(intent?.getStringExtra(TOKEN), engine.actionToken)) {
            when (intent?.action) {
                // Answer can also arrive via activity; handling it here makes notification
                // CallStyle reliable when the activity is slow to resume or not in foreground.
                ANSWER -> if (CallNotificationState.isIncoming(engine.state.role, engine.state.ringing)) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        engine.accept()
                    }
                }
                DECLINE -> if (CallNotificationState.isIncoming(engine.state.role, engine.state.ringing)) engine.end("reject")
                HANGUP -> engine.end()
                LEAVE -> { pendingInvite = false; returnAddress = null; outgoingStarted = false; engine.leave() }
            }
        } else if (!started) stopSelf()
        // A killed process must not silently resurrect consent or pretend to restore a dead WebRTC call.
        return START_NOT_STICKY
    }

    private fun update() {
        if (!started || stopping || switching) return
        val state = engine.state
        if (state.connected && state.role == "caller" && pendingInvite && state.peer && !state.busy) {
            pendingInvite = false
            outgoingStarted = true
            engine.invite()
            return
        }
        if (state.role == "caller" && outgoingStarted && !state.busy && state.connected) {
            val address = returnAddress
            outgoingStarted = false
            returnAddress = null
            if (address != null) {
                switchSession(address, "receiver", null)
                return
            }
        }
        if (!CallNotificationState.shouldRun(state.connected, state.connecting, state.busy)) {
            stopping = true
            notifications.cancel(INCOMING_ID)
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (state.busy && !wakeLock.isHeld) wakeLock.acquire()
        if (!state.busy && wakeLock.isHeld) wakeLock.release()
        val incoming = CallNotificationState.isIncoming(state.role, state.ringing)
        if (incoming && incomingToken != engine.actionToken) {
            incomingToken = engine.actionToken
            postNotification(INCOMING_ID, incomingNotification())
        } else if (!incoming && incomingToken != null) {
            incomingToken = null
            notifications.cancel(INCOMING_ID) // Also stops the system-managed looping ringtone/vibration.
        }
        val summary = "${engine.actionToken}:${state.status}:${state.inCall}:${state.busy}"
        if (lastSummary != summary) {
            lastSummary = summary
            postNotification(SESSION_ID, sessionNotification(UiText.translate(this, state.status)))
        }
    }

    private fun switchSession(room: String, role: String, resumeCall: String?) {
        if ((engine.state.connected || engine.state.connecting) && engine.state.room == room && engine.state.role == role) return
        switching = true
        if (engine.state.connected || engine.state.connecting) {
            if (engine.state.role == "receiver") engine.leave() else engine.disconnect(getString(R.string.status_switching_address))
        }
        if (role == "caller") PushCalls.disable(this)
        engine.connect(room, role, resumeCall)
        switching = false
    }

    private fun postNotification(id: Int, notification: Notification) {
        // Permission can be revoked after joining. Keep the existing call alive without throwing.
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(id, notification)
        }
    }

    private fun open(action: String = "OPEN"): PendingIntent {
        val intent = Intent(this, RtcActivity::class.java).setAction(action)
            .setData(Uri.parse("safetyhack://call/${engine.actionToken}/$action"))
            .putExtra(TOKEN, engine.actionToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        return PendingIntent.getActivity(this, if (action == ANSWER) 11 else 10, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
    }
    private fun command(action: String): PendingIntent {
        val requestCode = when (action) {
            ANSWER -> 12
            DECLINE -> 13
            HANGUP -> 14
            LEAVE -> 15
            else -> 16
        }
        return PendingIntent.getService(this, requestCode,
            Intent(this, CallService::class.java).setAction(action)
                .setData(Uri.parse("safetyhack://call/${engine.actionToken}/$action"))
                .putExtra(TOKEN, engine.actionToken), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun base(channel: String) = Notification.Builder(this, channel)
        .setSmallIcon(R.drawable.ic_call_notification)
        .setContentIntent(open())
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setColor(0xff3454d1.toInt())

    private fun incomingNotification(): Notification {
        val builder = base(INCOMING_CHANNEL).setContentTitle(getString(R.string.notif_incoming_title))
            .setContentText(getString(R.string.notif_incoming_text))
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(70000)
        // Answer goes to the service so accept works even when the activity is not resumed
        // (OEM battery policies, lock screen). Decline stays on the service path too.
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(getString(R.string.notif_peer_name)).setImportant(true).build(), command(DECLINE), command(ANSWER)))
        } else {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.decline), command(DECLINE)).build())
                .addAction(Notification.Action.Builder(null, getString(R.string.answer), command(ANSWER)).build())
        }
        // Full-screen / tap still opens the activity so the user can see the call UI.
        if (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()) builder.setFullScreenIntent(open(ANSWER), true)
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    private fun sessionNotification(message: String): Notification {
        val builder = base(SESSION_CHANNEL).setContentTitle(if (engine.state.inCall) getString(R.string.notif_ongoing_title) else getString(R.string.notif_session_title))
            .setContentText(message)
            .setCategory(if (engine.state.busy) Notification.CATEGORY_CALL else Notification.CATEGORY_SERVICE)
        if (engine.state.inCall && Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(Notification.CallStyle.forOngoingCall(Person.Builder().setName(getString(R.string.notif_peer_name)).build(), command(HANGUP)))
        } else if (engine.state.busy) {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.notif_hangup), command(HANGUP)).build())
        } else builder.addAction(Notification.Action.Builder(null, getString(R.string.disable_incoming), command(LEAVE)).build())
        return builder.build()
    }

    override fun onDestroy() {
        stopping = true
        CallRuntime.remove(observer)
        notifications.cancel(INCOMING_ID)
        if (wakeLock.isHeld) wakeLock.release()
        if (started && CallRuntime.sessionOpen) engine.disconnect(getString(R.string.localized_message_89))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
