package kz.safetyhack.audiotester

import org.junit.Assert.*
import org.junit.Test

class RtcOpenGateTest {
    @Test fun grantWhilePausedWaitsForResumeAndOpensOnlyOnce() {
        val gate = RtcOpenGate()
        gate.request(false)
        assertFalse(gate.consume(false, false))
        gate.permissionResult(true)
        assertFalse(gate.consume(false, true))
        assertTrue(gate.consume(true, true))
        assertFalse(gate.consume(true, true))
    }
    @Test fun resumeBeforeResultCannotOpenWebView() {
        val gate = RtcOpenGate()
        gate.request(false)
        assertFalse(gate.consume(true, true))
        gate.permissionResult(true)
        assertTrue(gate.consume(true, true))
    }
    @Test fun denialAndCanceledRequestNeverOpen() {
        val gate = RtcOpenGate()
        gate.request(false)
        gate.permissionResult(false)
        assertFalse(gate.consume(true, false))
        gate.request(false)
        gate.cancel()
        gate.permissionResult(true)
        assertFalse(gate.consume(true, true))
    }
    @Test fun permissionRevokedBeforeResumeConsumesIntentWithoutOpening() {
        val gate = RtcOpenGate()
        gate.request(false)
        gate.permissionResult(true)
        assertFalse(gate.consume(true, false))
        assertFalse(gate.consume(true, true))
    }
    @Test fun alreadyGrantedPermissionStillNeedsExplicitOpen() {
        val gate = RtcOpenGate()
        assertFalse(gate.consume(true, true))
        gate.request(true)
        assertTrue(gate.consume(true, true))
    }
}
