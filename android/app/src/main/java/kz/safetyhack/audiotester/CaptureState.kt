package kz.safetyhack.audiotester

data class Snapshot(
    val active: Boolean = false,
    val status: String = "Можно начать запись на 30 секунд",
    val db: Double = -90.0,
    val seconds: Int = 0,
    val network: String = "Запись только на телефоне",
    val serverMode: String = "",
    val diagnostic: Boolean = false,
    val transcript: String = "",
    val risk: String = "",
    val serverLevel: String = "",
    val wav: String? = null
)

object CaptureState {
    @Volatile var current = Snapshot()
        private set
    @Synchronized fun update(change: (Snapshot) -> Snapshot) { current = change(current) }
}
