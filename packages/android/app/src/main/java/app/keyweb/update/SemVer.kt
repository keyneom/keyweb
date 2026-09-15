package app.keyweb.update

/**
 * Comparing two version strings, including pre-releases.
 *
 * Pre-releases are the whole difficulty, and Keyweb ships nothing else at the
 * moment. The obvious implementation — strip the leading "v", split on dots,
 * compare each part as a number — gets them wrong in a way that only shows up
 * later: `"0.1.10-beta.1"` splits into `0`, `1`, `10-beta`, `1`, and the third
 * part is not a number, so it reads as zero. The version then compares as
 * *older* than `0.1.9`, and the app stops offering an update it should offer.
 * Nothing fails visibly; the notice simply never appears again.
 *
 * So the pre-release is separated before any of it is split, and then ordered
 * by the semver rules, which are not arbitrary:
 *
 *  - `1.0.0-beta` is **older** than `1.0.0`. A pre-release is a run-up to a
 *    release, not a successor to it.
 *  - numeric identifiers compare numerically, so `beta.2` precedes `beta.10`
 *    rather than following it as text would.
 *  - a numeric identifier sorts below an alphanumeric one, and a shorter run
 *    of identifiers below a longer one that starts the same way, so
 *    `beta` precedes `beta.1`.
 *
 * Build metadata after a `+` is ignored, as the spec requires.
 */
object SemVer {

    fun normalize(value: String): String = value.trim().removePrefix("v").removePrefix("V")

    /** Positive when [left] is newer than [right], zero when they rank equally. */
    fun compare(left: String, right: String): Int {
        val (leftCore, leftPre) = split(left)
        val (rightCore, rightPre) = split(right)

        for (index in 0 until maxOf(leftCore.size, rightCore.size)) {
            val delta = leftCore.getOrElse(index) { 0 } - rightCore.getOrElse(index) { 0 }
            if (delta != 0) return delta.coerceIn(-1, 1)
        }

        // Same release. Having no pre-release wins; that is the release itself.
        if (leftPre.isEmpty() && rightPre.isEmpty()) return 0
        if (leftPre.isEmpty()) return 1
        if (rightPre.isEmpty()) return -1

        for (index in 0 until maxOf(leftPre.size, rightPre.size)) {
            val l = leftPre.getOrNull(index) ?: return -1
            val r = rightPre.getOrNull(index) ?: return 1
            val delta = compareIdentifier(l, r)
            if (delta != 0) return delta
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** The numeric release parts, and the dot-separated pre-release identifiers. */
    private fun split(value: String): Pair<List<Int>, List<String>> {
        val withoutBuild = normalize(value).substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val pre = withoutBuild.substringAfter('-', "")
        return core.split('.').map { it.trim().toIntOrNull() ?: 0 } to
            pre.split('.').filter { it.isNotEmpty() }
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null ->
                leftNumber.compareTo(rightNumber).coerceIn(-1, 1)
            // Numeric identifiers always have lower precedence than text.
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right).coerceIn(-1, 1)
        }
    }
}
