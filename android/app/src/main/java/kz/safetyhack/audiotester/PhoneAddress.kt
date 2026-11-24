package kz.safetyhack.audiotester

/** Converts a user-entered phone number into a stable, non-telephony signaling address. */
object PhoneAddress {
    private val keyPattern = Regex("tel_[0-9]{10,15}")

    fun normalize(value: String): String? {
        if (value.any { !it.isDigit() && it !in "+-() " }) return null
        var digits = value.filter(Char::isDigit)
        if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.drop(1)
        if (digits.length == 10) digits = "7$digits"
        return digits.takeIf { it.length in 10..15 }?.let { "+$it" }
    }

    fun key(value: String): String? = normalize(value)?.drop(1)?.let { "tel_$it" }

    fun display(key: String): String = if (keyPattern.matches(key)) "+${key.removePrefix("tel_")}" else key
}
