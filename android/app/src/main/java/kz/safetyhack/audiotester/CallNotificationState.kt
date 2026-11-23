package kz.safetyhack.audiotester

/** Pure notification policy; never use an old notification to control a different call. */
object CallNotificationState {
    fun isIncoming(role: String, ringing: Boolean) = role == "receiver" && ringing
    fun validAction(suppliedToken: String?, currentToken: String) =
        !suppliedToken.isNullOrEmpty() && suppliedToken == currentToken
    fun shouldRun(connected: Boolean, connecting: Boolean, busy: Boolean) = connected || connecting || busy
}
