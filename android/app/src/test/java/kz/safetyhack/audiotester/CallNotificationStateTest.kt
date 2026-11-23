package kz.safetyhack.audiotester

import org.junit.Assert.*
import org.junit.Test

class CallNotificationStateTest {
    @Test fun onlyReceiverHasAnIncomingNotification() {
        assertTrue(CallNotificationState.isIncoming("receiver", true))
        assertFalse(CallNotificationState.isIncoming("caller", true))
        assertFalse(CallNotificationState.isIncoming("receiver", false))
    }
    @Test fun staleCallAndPreviousProcessNotificationsCannotAnswer() {
        val current = "process-b:4:7"
        assertFalse(CallNotificationState.validAction("process-b:4:6", current))
        assertFalse(CallNotificationState.validAction("process-b:3:7", current))
        assertFalse(CallNotificationState.validAction("process-a:4:7", current))
        assertTrue(CallNotificationState.validAction(current, current))
    }
    @Test fun missingNotificationIdentityCannotControlCalls() {
        assertFalse(CallNotificationState.validAction(null, "live"))
        assertFalse(CallNotificationState.validAction("", "live"))
        assertFalse(CallNotificationState.validAction("", ""))
    }
    @Test fun serviceRemainsAliveBetweenCallsAndDuringConnection() {
        assertTrue(CallNotificationState.shouldRun(true, false, false))
        assertTrue(CallNotificationState.shouldRun(false, true, false))
        assertTrue(CallNotificationState.shouldRun(true, false, true))
        assertFalse(CallNotificationState.shouldRun(false, false, false))
    }
}
