package app.keyweb.vault

import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * Making a password to a site's rules.
 *
 * Sites impose arbitrary and often silly constraints — "at least one number",
 * "no symbols", "8 to 16 characters" — and the moment a generated password is
 * rejected, people stop using the generator and type their dog's name instead.
 * So the rules are the feature, not the decoration.
 *
 * A counterpart to `packages/vault-core/src/generator.ts`. The outputs are
 * random, so there is nothing to keep in parity byte for byte; what has to
 * match is the vocabulary of rules, since a saved rule set is meant to mean the
 * same thing on a phone as in a browser.
 */
enum class CharacterSet { LOWER, UPPER, DIGITS, SYMBOLS }

@Serializable
data class PasswordRules(
    val length: Int = 20,
    /** Sets a character may be drawn from. */
    val include: Set<CharacterSet> = CharacterSet.entries.toSet(),
    /** Sets that must each contribute at least one character. */
    val require: Set<CharacterSet> = CharacterSet.entries.toSet(),
    /** Overrides the symbol set, for sites that only accept some. */
    val symbols: String? = null,
    val avoidLookalikes: Boolean = false,
)

@Serializable
data class SavedRules(val id: String, val name: String, val rules: PasswordRules)

object PasswordGenerator {

    /** Broadly accepted, and none of them shell-hostile. */
    const val DEFAULT_SYMBOLS = "!@#$%^&*-_=+?"

    /**
     * Characters with no shape of their own.
     *
     * Excluding them is offered rather than imposed: it costs entropy and only
     * helps when a password will be read off a screen and typed somewhere else.
     * Colour-coding the display solves the same problem for free, so this is
     * for paper.
     */
    private const val LOOKALIKES = "lI1O0"

    private val sets = mapOf(
        CharacterSet.LOWER to "abcdefghijklmnopqrstuvwxyz",
        CharacterSet.UPPER to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        CharacterSet.DIGITS to "0123456789",
        CharacterSet.SYMBOLS to DEFAULT_SYMBOLS,
    )

    val presets: List<SavedRules> = listOf(
        SavedRules("strong", "Strong", PasswordRules(length = 20)),
        SavedRules(
            "no-symbols",
            "Letters and numbers only",
            PasswordRules(
                length = 20,
                include = setOf(CharacterSet.LOWER, CharacterSet.UPPER, CharacterSet.DIGITS),
                require = setOf(CharacterSet.LOWER, CharacterSet.UPPER, CharacterSet.DIGITS),
            ),
        ),
        SavedRules(
            "easy-to-read",
            "Easy to read out loud",
            PasswordRules(
                length = 20,
                include = setOf(CharacterSet.LOWER, CharacterSet.UPPER, CharacterSet.DIGITS),
                require = setOf(CharacterSet.LOWER, CharacterSet.DIGITS),
                avoidLookalikes = true,
            ),
        ),
        SavedRules(
            "old-fashioned",
            "Short, for fussy sites",
            PasswordRules(length = 12, symbols = "!@#$%*"),
        ),
        SavedRules(
            "pin",
            "Numbers only (PIN)",
            PasswordRules(
                length = 6,
                include = setOf(CharacterSet.DIGITS),
                require = setOf(CharacterSet.DIGITS),
            ),
        ),
    )

    fun charactersFor(set: CharacterSet, rules: PasswordRules): String {
        val base = if (set == CharacterSet.SYMBOLS) rules.symbols ?: DEFAULT_SYMBOLS else sets.getValue(set)
        return if (rules.avoidLookalikes) base.filterNot { it in LOOKALIKES } else base
    }

    fun alphabetFor(rules: PasswordRules): String =
        (rules.include + rules.require).joinToString("") { charactersFor(it, rules) }

    /** Why these rules cannot produce a password, or null when they can. */
    fun problem(rules: PasswordRules): String? {
        if (rules.include.isEmpty() && rules.require.isEmpty()) {
            return "Choose at least one kind of character."
        }
        for (set in rules.require) {
            if (charactersFor(set, rules).isEmpty()) {
                return if (set == CharacterSet.SYMBOLS) {
                    "No symbols are allowed by these rules, so one can't be required."
                } else {
                    "There are no ${set.name.lowercase()} characters left to use."
                }
            }
        }
        if (rules.length < rules.require.size) {
            return "A password this short can't include one of each. Make it at least " +
                "${rules.require.size} characters."
        }
        if (rules.length < 4) return "Use at least 4 characters."
        if (rules.length > 128) return "Keyweb tops out at 128 characters."
        return null
    }

    private val random = SecureRandom()

    /**
     * Generate a password satisfying the rules.
     *
     * Required sets are seeded first, the rest drawn from the whole alphabet,
     * then the lot shuffled. Seeding rather than retrying matters for the
     * awkward cases — "16 characters, must contain a symbol, only ! and #
     * allowed" — where rejection sampling would loop for a long time.
     *
     * The shuffle is Fisher–Yates, so no position is likelier to hold a given
     * category than any other. A generator that always put the digit last would
     * hand an attacker a character for free.
     */
    fun generate(rules: PasswordRules, rng: SecureRandom = random): String {
        problem(rules)?.let { throw IllegalArgumentException(it) }

        val alphabet = alphabetFor(rules)
        val characters = ArrayList<Char>(rules.length)

        for (set in rules.require) {
            val pool = charactersFor(set, rules)
            characters.add(pool[rng.nextInt(pool.length)])
        }
        while (characters.size < rules.length) {
            characters.add(alphabet[rng.nextInt(alphabet.length)])
        }
        for (i in characters.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val swap = characters[i]
            characters[i] = characters[j]
            characters[j] = swap
        }
        return characters.joinToString("")
    }

    /**
     * How many bits of guessing this actually costs.
     *
     * Not `length * log2(alphabet)`, which is the figure people quote and is
     * wrong whenever a set is required: "must contain a symbol" rules out every
     * password that has none, so the space is smaller than the naive product.
     * Counted by inclusion–exclusion over the required sets — exact, and cheap
     * for the handful involved. Worked in logs, because the counts overflow a
     * double long before the entropy does.
     */
    fun entropyBits(rules: PasswordRules): Double {
        if (problem(rules) != null) return 0.0
        val total = alphabetFor(rules).length
        val required = rules.require.map { charactersFor(it, rules).length }

        var sum = 0.0
        for (mask in 0 until (1 shl required.size)) {
            var excluded = 0
            var bits = 0
            for (i in required.indices) {
                if (mask and (1 shl i) != 0) {
                    excluded += required[i]
                    bits += 1
                }
            }
            val remaining = total - excluded
            if (remaining <= 0) continue
            val term = rules.length * (log2(remaining.toDouble()) - log2(total.toDouble()))
            sum += (if (bits % 2 == 0) 1 else -1) * Math.pow(2.0, term)
        }
        return if (sum > 0) rules.length * log2(total.toDouble()) + log2(sum) else 0.0
    }

    private fun log2(value: Double) = Math.log(value) / Math.log(2.0)

    data class Strength(val bits: Double, val label: String, val detail: String)

    /**
     * Strength in words rather than a coloured bar.
     *
     * The thresholds are about offline cracking of a stolen password database,
     * which is what a generated password defends against. Online guessing is
     * rate-limited and irrelevant at any of these sizes.
     */
    fun describeStrength(rules: PasswordRules): Strength {
        val bits = entropyBits(rules)
        return when {
            bits < 40 -> Strength(
                bits,
                "Weak",
                "A determined attacker could work this out. Make it longer.",
            )
            bits < 60 -> Strength(
                bits,
                "Fair",
                "Fine for something unimportant, not for money.",
            )
            bits < 80 -> Strength(bits, "Strong", "Good for almost anything.")
            else -> Strength(
                bits,
                "Very strong",
                "Far beyond what anyone could guess, however long they tried.",
            )
        }
    }
}
