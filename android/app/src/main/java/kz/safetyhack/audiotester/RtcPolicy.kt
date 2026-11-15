package kz.safetyhack.audiotester

import java.net.URI
import java.util.Locale

/** Setup permission result may arrive before or after onResume. Consume each intent once. */
class RtcOpenGate {
    var waitingForPermission = false
        private set
    private var ready = false
    fun request(hasPermission: Boolean) {
        waitingForPermission = !hasPermission
        ready = hasPermission
    }
    fun permissionResult(granted: Boolean) {
        if (!waitingForPermission) return
        waitingForPermission = false
        ready = granted
    }
    fun consume(foreground: Boolean, hasPermission: Boolean): Boolean {
        if (!foreground || !ready || waitingForPermission) return false
        ready = false
        return hasPermission
    }
    fun cancel() { waitingForPermission = false; ready = false }
}

/** Pure, fail-closed policy shared by navigation and permission decisions. */
object RtcPolicy {
    const val AUDIO = "android.webkit.resource.AUDIO_CAPTURE"
    private fun uri(value: String): URI? = runCatching {
        URI(value).takeIf {
            it.scheme.equals("https", true) && !it.host.isNullOrBlank() &&
                it.rawUserInfo == null && it.port in -1..65535 && it.port != 0
        }
    }.getOrNull()

    fun origin(value: String): String? {
        val u = uri(value) ?: return null
        val port = if (u.port == -1 || u.port == 443) "" else ":${u.port}"
        return "https://${u.host.lowercase(Locale.ROOT)}$port"
    }

    fun configuredOrigin(value: String): String? {
        val u = uri(value.trim()) ?: return null
        if (u.rawPath !in listOf("", "/") || u.rawQuery != null || u.rawFragment != null) return null
        return origin(value.trim())
    }

    fun sameOrigin(configured: String, target: String) =
        configuredOrigin(configured) != null && origin(target) == configuredOrigin(configured)

    fun allowAudio(configured: String, requestOrigin: String, resources: List<String>,
                   androidPermission: Boolean, consent: Boolean, foreground: Boolean) =
        sameOrigin(configured, requestOrigin) && resources == listOf(AUDIO) &&
            androidPermission && consent && foreground
}
