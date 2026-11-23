package kz.safetyhack.audiotester

import android.content.Context

/** Process-scoped media. Activities subscribe; only CallService owns a joined session. Main thread only. */
object CallRuntime {
    private var client: NativeRtcClient? = null
    private val listeners = linkedSetOf<() -> Unit>()
    fun client(context: Context): NativeRtcClient = client ?: NativeRtcClient(context.applicationContext) {
        listeners.toList().forEach { it() }
    }.also { client = it }
    val sessionOpen: Boolean get() = client?.state?.let { it.connected || it.connecting || it.busy } == true
    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun refreshPush(token: String) { client?.registerPush(token) }
}
