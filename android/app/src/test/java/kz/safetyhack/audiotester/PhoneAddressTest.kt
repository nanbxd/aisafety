package kz.safetyhack.audiotester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAddressTest {
    @Test fun normalizes_kazakhstan_and_russia_local_formats() {
        assertEquals("+77011234567", PhoneAddress.normalize("8 (701) 123-45-67"))
        assertEquals("tel_77011234567", PhoneAddress.key("+7 701 123 45 67"))
    }

    @Test fun keeps_international_numbers_and_rejects_invalid_input() {
        assertEquals("+447911123456", PhoneAddress.normalize("+44 7911 123456"))
        assertEquals("+447911123456", PhoneAddress.display("tel_447911123456"))
        assertNull(PhoneAddress.key("call-me"))
        assertNull(PhoneAddress.key("123"))
    }
}
