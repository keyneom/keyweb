package app.keyweb.vault

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratorTest {

    private val strong = PasswordRules(length = 20)

    @Test
    fun `honours the requested length exactly`() {
        for (length in listOf(4, 8, 12, 20, 64, 128)) {
            assertEquals(length, PasswordGenerator.generate(strong.copy(length = length)).length)
        }
    }

    @Test
    fun `always includes one of every required kind`() {
        // The point of the whole feature: a password the site rejects sends
        // people back to typing their dog's name.
        repeat(300) {
            val value = PasswordGenerator.generate(strong.copy(length = 8))
            assertTrue(value.any { it.isLowerCase() }, value)
            assertTrue(value.any { it.isUpperCase() }, value)
            assertTrue(value.any { it.isDigit() }, value)
            assertTrue(value.any { it in PasswordGenerator.DEFAULT_SYMBOLS }, value)
        }
    }

    @Test
    fun `never includes a kind that was excluded`() {
        val rules = PasswordRules(
            length = 24,
            include = setOf(CharacterSet.LOWER, CharacterSet.DIGITS),
            require = setOf(CharacterSet.DIGITS),
        )
        repeat(200) {
            val value = PasswordGenerator.generate(rules)
            assertTrue(value.all { it.isLowerCase() || it.isDigit() }, value)
        }
    }

    @Test
    fun `respects a restricted symbol set`() {
        // Some sites accept only a handful of symbols, and reject the rest
        // without saying which.
        val rules = PasswordRules(length = 16, symbols = "!#", require = setOf(CharacterSet.SYMBOLS))
        repeat(200) {
            val value = PasswordGenerator.generate(rules)
            assertTrue(value.any { it == '!' || it == '#' }, value)
            assertTrue(value.none { it in "@$%^&*-_=+?" }, value)
        }
    }

    @Test
    fun `drops look-alike characters when asked`() {
        val rules = strong.copy(length = 64, avoidLookalikes = true)
        repeat(50) {
            assertTrue(PasswordGenerator.generate(rules).none { it in "lI1O0" })
        }
    }

    @Test
    fun `spreads the required characters across the whole password`() {
        // Seeding required sets first and shuffling afterwards is what avoids
        // this. A generator that always put the digit last would hand an
        // attacker a character for free.
        val positions = mutableSetOf<Int>()
        repeat(400) {
            positions.add(PasswordGenerator.generate(strong.copy(length = 8)).indexOfFirst { it.isDigit() })
        }
        assertTrue(positions.size >= 7, "digits only ever landed at $positions")
    }

    @Test
    fun `does not favour any character over another`() {
        // A crude uniformity check: a materially skewed generator would fall
        // well outside this band.
        val rules = PasswordRules(
            length = 26,
            include = setOf(CharacterSet.LOWER),
            require = emptySet(),
        )
        val counts = HashMap<Char, Int>()
        repeat(1000) {
            for (character in PasswordGenerator.generate(rules)) {
                counts[character] = (counts[character] ?: 0) + 1
            }
        }
        assertEquals(26, counts.size)
        val expected = 26000.0 / 26
        for (count in counts.values) {
            assertTrue(abs(count - expected) / expected < 0.2, "skew at $count vs $expected")
        }
    }

    @Test
    fun `every preset produces something matching its own rules`() {
        for (preset in PasswordGenerator.presets) {
            assertNull(PasswordGenerator.problem(preset.rules), preset.name)
            val value = PasswordGenerator.generate(preset.rules)
            assertEquals(preset.rules.length, value.length)
            for (set in preset.rules.require) {
                val pool = PasswordGenerator.charactersFor(set, preset.rules)
                assertTrue(value.any { it in pool }, "${preset.name} missing $set")
            }
        }
    }

    @Test
    fun `refuses impossible rules rather than quietly producing something weaker`() {
        // Silently dropping a requirement is the dangerous failure: the
        // password looks fine and is rejected, or worse, a weaker one is taken.
        assertNotNull(
            PasswordGenerator.problem(
                PasswordRules(length = 20, include = emptySet(), require = emptySet()),
            ),
        )
        assertNotNull(PasswordGenerator.problem(strong.copy(length = 2)))
        assertNotNull(PasswordGenerator.problem(strong.copy(length = 200)))
        assertNotNull(
            PasswordGenerator.problem(
                PasswordRules(length = 16, symbols = "", require = setOf(CharacterSet.SYMBOLS)),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.generate(strong.copy(length = 2))
        }
    }

    @Test
    fun `treats a required set as included even if the caller forgot`() {
        val rules = PasswordRules(
            length = 16,
            include = setOf(CharacterSet.LOWER),
            require = setOf(CharacterSet.DIGITS),
        )
        assertNull(PasswordGenerator.problem(rules))
        assertTrue(PasswordGenerator.alphabetFor(rules).contains("0123456789"))
    }

    @Test
    fun `counts a requirement as the constraint it is`() {
        // "Must contain a symbol" rules out every password that has none, so
        // the real space is smaller than length x log2(alphabet). Quoting the
        // naive product overstates strength, which is the wrong way to be wrong.
        val short = strong.copy(length = 8)
        val naive = 8 * (Math.log(PasswordGenerator.alphabetFor(short).length.toDouble()) / Math.log(2.0))
        val actual = PasswordGenerator.entropyBits(short)
        assertTrue(actual < naive, "$actual should be below $naive")
        assertTrue(actual > naive - 2)
    }

    @Test
    fun `matches the simple calculation when nothing is required`() {
        val rules = strong.copy(require = emptySet())
        val naive = 20 * (Math.log(PasswordGenerator.alphabetFor(rules).length.toDouble()) / Math.log(2.0))
        assertTrue(abs(PasswordGenerator.entropyBits(rules) - naive) < 1e-6)
    }

    @Test
    fun `describes a PIN as weak and a long password as very strong`() {
        val pin = PasswordGenerator.presets.first { it.id == "pin" }
        assertEquals("Weak", PasswordGenerator.describeStrength(pin.rules).label)
        assertEquals("Very strong", PasswordGenerator.describeStrength(strong).label)
    }
}
