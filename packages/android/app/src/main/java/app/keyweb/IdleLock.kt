package app.keyweb

/**
 * Locking Keyweb when nobody is using it.
 *
 * Android keeps a backgrounded app's process for as long as it likes — hours,
 * often days — and the view model with the decrypted vault in it lives as long
 * as the process does. So a phone put down unlocked on Monday opened straight
 * to the passwords on Friday, for whoever picked it up.
 *
 * The counterpart of the web's `idleLock.ts`, with the same choices. Measured
 * against a clock that keeps counting while the phone sleeps, and checked when
 * the app comes back to the front as well as on a tick while it is showing,
 * because a backgrounded app runs no ticks at all.
 */
enum class LockAfter(val minutes: Int, val label: String) {
    FIVE(5, "After 5 minutes"),
    FIFTEEN(15, "After 15 minutes"),
    HOUR(60, "After 1 hour"),
    FOUR_HOURS(240, "After 4 hours"),
    ;

    val millis: Long get() = minutes * 60_000L

    /** In words, for the lock screen: "15 minutes", "1 hour". */
    val words: String
        get() = when {
            minutes < 60 -> "$minutes minutes"
            minutes == 60 -> "1 hour"
            else -> "${minutes / 60} hours"
        }

    companion object {
        val DEFAULT = FIFTEEN

        fun fromMinutes(minutes: Int?): LockAfter = entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

/**
 * When the app was last used, by a clock that does not stop in deep sleep.
 *
 * [now] is `SystemClock.elapsedRealtime` in the app: not the wall clock, which
 * the network or the person can move, and not uptime, which pauses while the
 * phone sleeps — exactly the time that most needs counting.
 */
class IdleClock(private val now: () -> Long) {
    private var last = now()

    /** Someone touched the app, or it has just been unlocked. */
    fun touch() {
        last = now()
    }

    fun expired(after: LockAfter): Boolean = now() - last >= after.millis
}
