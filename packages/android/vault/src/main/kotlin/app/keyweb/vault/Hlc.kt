package app.keyweb.vault

/**
 * Hybrid Logical Clock.
 *
 * Last-write-wins ordered by wall time is itself a data-loss bug: a device
 * whose clock runs slow can never win a merge, so its edits are silently
 * discarded forever. An HLC keeps wall-clock readability but guarantees that an
 * edit which causally follows another always sorts after it, whatever the two
 * devices' clocks say.
 *
 * Encoded as a lexicographically sortable string, identical to the TypeScript
 * implementation so both platforms read each other's vaults:
 *
 *     000001723456789-00003-a1b2c3d4
 *     \_____________/ \___/ \______/
 *        wall ms     counter  node
 */
private const val WALL_DIGITS = 15
private const val COUNTER_DIGITS = 5
private const val MAX_COUNTER = 99_999

/** A point in causal time. Ordering is plain string ordering. */
typealias Hlc = String

data class HlcParts(val wall: Long, val counter: Int, val node: String)

fun encodeHlc(parts: HlcParts): Hlc {
    val wall = parts.wall.toString().padStart(WALL_DIGITS, '0')
    val counter = parts.counter.toString().padStart(COUNTER_DIGITS, '0')
    return "$wall-$counter-${parts.node}"
}

fun decodeHlc(value: Hlc): HlcParts {
    require(value.length > WALL_DIGITS + COUNTER_DIGITS + 2) { "Malformed HLC timestamp: $value" }
    val wall = value.substring(0, WALL_DIGITS).toLongOrNull()
    val counter = value.substring(WALL_DIGITS + 1, WALL_DIGITS + 1 + COUNTER_DIGITS).toIntOrNull()
    val node = value.substring(WALL_DIGITS + COUNTER_DIGITS + 2)
    require(wall != null && counter != null && node.isNotEmpty()) {
        "Malformed HLC timestamp: $value"
    }
    return HlcParts(wall, counter, node)
}

/** The zero timestamp; sorts before every real one. Used for absent values. */
val HLC_ZERO: Hlc = encodeHlc(HlcParts(0, 0, ""))

/**
 * Stamps local events and absorbs remote ones.
 *
 * Not thread safe by itself: [VaultSync] holds it behind the same mutex that
 * serialises syncs, and edits go through that same path.
 */
class Clock(
    private val node: String,
    private val physical: () -> Long = { System.currentTimeMillis() },
    resume: Hlc? = null,
) {
    init {
        require(node.isNotEmpty()) { "An HLC needs a non-empty node id." }
    }

    private var last: HlcParts =
        resume?.let { decodeHlc(it).copy(node = node) } ?: HlcParts(0, 0, node)

    private fun advance(candidateWall: Long, candidateCounter: Int): Hlc {
        last = if (candidateCounter > MAX_COUNTER) {
            // Counter saturation means >100k events in one millisecond. Borrow
            // from the next millisecond rather than wrapping, which would break
            // ordering.
            HlcParts(candidateWall + 1, 0, node)
        } else {
            HlcParts(candidateWall, candidateCounter, node)
        }
        return encodeHlc(last)
    }

    /** Stamp a new local event. */
    fun now(): Hlc {
        val wall = physical()
        return if (wall > last.wall) advance(wall, 0) else advance(last.wall, last.counter + 1)
    }

    /** Observe a remote timestamp, advancing local causal time past it. */
    fun observe(remote: Hlc) {
        val parts = decodeHlc(remote)
        val wall = physical()
        val maxWall = maxOf(wall, last.wall, parts.wall)
        val counter = when {
            maxWall == last.wall && maxWall == parts.wall -> maxOf(last.counter, parts.counter) + 1
            maxWall == last.wall -> last.counter + 1
            maxWall == parts.wall -> parts.counter + 1
            else -> 0
        }
        advance(maxWall, counter)
    }

    /** Current state, for persistence across restarts. */
    fun snapshot(): Hlc = encodeHlc(last)
}
