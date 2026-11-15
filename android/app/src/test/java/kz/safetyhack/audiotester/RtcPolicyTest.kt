package kz.safetyhack.audiotester

import org.junit.Assert.*
import org.junit.Test

class RtcPolicyTest {
    @Test fun canonicalHttpsOrigin() {
        assertEquals("https://example.com", RtcPolicy.configuredOrigin(" https://EXAMPLE.com:443/ "))
        assertEquals("https://example.com:8443", RtcPolicy.configuredOrigin("https://example.com:8443"))
    }
    @Test fun rejectUnsafeConfiguration() {
        listOf("http://example.com", "file:///tmp/x", "https://user:secret@example.com",
            "https://example.com/rtc", "https://example.com?token=x", "https://example.com#x",
            "https://example.com:0", "https://example.com:65536", "https://", "javascript:alert(1)",
            "https://example.com\\@evil.com").forEach { assertNull(it, RtcPolicy.configuredOrigin(it)) }
    }
    @Test fun navigationRequiresExactOrigin() {
        assertTrue(RtcPolicy.sameOrigin("https://example.com", "https://EXAMPLE.com:443/rtc?role=receiver"))
        listOf("https://example.com.evil.test", "https://evil.test", "http://example.com",
            "https://example.com:8443", "https://example.com@evil.test", "data:text/html,x",
            "intent://example.com", "about:blank", "https://user@example.com").forEach {
            assertFalse(it, RtcPolicy.sameOrigin("https://example.com", it))
        }
    }
    private fun grant(resources: List<String> = listOf(RtcPolicy.AUDIO), permission: Boolean = true,
                      consent: Boolean = true, foreground: Boolean = true, origin: String = "https://example.com") =
        RtcPolicy.allowAudio("https://example.com", origin, resources, permission, consent, foreground)

    @Test fun audioOnlyAfterAllGates() { assertTrue(grant()) }
    @Test fun denyMissingPermissionConsentOrForeground() {
        assertFalse(grant(permission = false)); assertFalse(grant(consent = false)); assertFalse(grant(foreground = false))
    }
    @Test fun denyOtherAndMixedResources() {
        assertFalse(grant(emptyList()))
        assertFalse(grant(listOf("android.webkit.resource.VIDEO_CAPTURE")))
        assertFalse(grant(listOf(RtcPolicy.AUDIO, "android.webkit.resource.VIDEO_CAPTURE")))
        assertFalse(grant(listOf("future-resource")))
    }
    @Test fun denyForeignPermissionOrigin() {
        assertFalse(grant(origin = "https://evil.test")); assertFalse(grant(origin = "http://example.com"))
        assertFalse(grant(origin = "https://example.com:444"))
    }
}
