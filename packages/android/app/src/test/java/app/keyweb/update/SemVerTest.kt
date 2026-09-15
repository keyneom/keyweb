package app.keyweb.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Version ordering, with the pre-release cases that actually ship.
 *
 * Every release of Keyweb so far has been a `-beta.N`, so the pre-release
 * rules are not an edge case here — they are the normal path, and getting them
 * wrong means the update notice silently stops appearing.
 */
class SemVerTest {

    @Test
    fun `later releases are newer`() {
        assertTrue(SemVer.isNewer("0.2.0", "0.1.1"))
        assertTrue(SemVer.isNewer("1.0.0", "0.9.9"))
        assertFalse(SemVer.isNewer("0.1.1", "0.2.0"))
        assertFalse(SemVer.isNewer("0.1.1", "0.1.1"))
    }

    @Test
    fun `a leading v is not part of the version`() {
        assertFalse(SemVer.isNewer("v0.1.1", "0.1.1"))
        assertTrue(SemVer.isNewer("v0.1.2", "0.1.1"))
    }

    @Test
    fun `a later beta of the same version is newer`() {
        assertTrue(SemVer.isNewer("0.1.1-beta.3", "0.1.1-beta.2"))
        assertFalse(SemVer.isNewer("0.1.1-beta.2", "0.1.1-beta.3"))
    }

    @Test
    fun `beta identifiers compare as numbers, not as text`() {
        // The case plain text ordering gets wrong: "10" sorts before "2".
        assertTrue(SemVer.isNewer("0.1.1-beta.10", "0.1.1-beta.2"))
        assertFalse(SemVer.isNewer("0.1.1-beta.2", "0.1.1-beta.10"))
    }

    @Test
    fun `a release is newer than its own betas`() {
        // A pre-release is the run-up to a release, not its successor.
        assertTrue(SemVer.isNewer("0.1.1", "0.1.1-beta.3"))
        assertFalse(SemVer.isNewer("0.1.1-beta.3", "0.1.1"))
    }

    @Test
    fun `a beta of a later version beats the release before it`() {
        assertTrue(SemVer.isNewer("0.2.0-beta.1", "0.1.1"))
        assertFalse(SemVer.isNewer("0.1.1", "0.2.0-beta.1"))
    }

    @Test
    fun `a two-digit patch is not mistaken for a smaller one`() {
        // The bug this guards: splitting "0.1.10-beta.1" on dots leaves
        // "10-beta" as the patch, which is not a number and reads as zero — so
        // the newest build would look older than 0.1.9 and never be offered.
        assertTrue(SemVer.isNewer("0.1.10-beta.1", "0.1.9"))
        assertTrue(SemVer.isNewer("0.1.10-beta.1", "0.1.9-beta.1"))
        assertTrue(SemVer.isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun `fewer identifiers rank below more that start the same way`() {
        assertTrue(SemVer.isNewer("0.1.1-beta.1", "0.1.1-beta"))
        assertFalse(SemVer.isNewer("0.1.1-beta", "0.1.1-beta.1"))
    }

    @Test
    fun `text ranks above numbers within a pre-release`() {
        assertTrue(SemVer.isNewer("0.1.1-beta", "0.1.1-1"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertFalse(SemVer.isNewer("0.1.1+abc123", "0.1.1"))
        assertFalse(SemVer.isNewer("0.1.1", "0.1.1+abc123"))
    }

    @Test
    fun `a missing patch is treated as zero rather than as unknown`() {
        assertFalse(SemVer.isNewer("0.1", "0.1.0"))
        assertTrue(SemVer.isNewer("0.1.1", "0.1"))
    }

    @Test
    fun `nonsense does not read as an upgrade`() {
        // A malformed tag must never look newer than a real version, or a bad
        // release would nag every user forever.
        assertFalse(SemVer.isNewer("", "0.1.1"))
        assertFalse(SemVer.isNewer("not-a-version", "0.1.1"))
    }
}
