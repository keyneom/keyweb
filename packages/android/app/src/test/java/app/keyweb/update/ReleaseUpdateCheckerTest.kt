package app.keyweb.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

private class RememberedDismissals : UpdateDismissals {
    private val dismissed = mutableSetOf<String>()
    override fun isDismissed(version: String) = SemVer.normalize(version) in dismissed
    override fun dismiss(version: String) {
        dismissed += SemVer.normalize(version)
    }
}

private fun checker(
    dismissals: UpdateDismissals = RememberedDismissals(),
    tag: suspend () -> String?,
) = ReleaseUpdateChecker(dismissals, tag)

class ReleaseUpdateCheckerTest {

    @Test
    fun `offers a newer release`() = runTest {
        val update = checker { "v0.1.2" }.checkForUpdate("0.1.1")
        assertEquals("0.1.2", assertNotNull(update).version)
        assertEquals(ReleaseUpdateChecker.RELEASES_URL, update.downloadUrl)
    }

    @Test
    fun `says nothing when this is the newest`() = runTest {
        assertNull(checker { "v0.1.1" }.checkForUpdate("0.1.1"))
    }

    @Test
    fun `says nothing when the release is older than this build`() = runTest {
        // A local build ahead of the last release must not be told to
        // "update" itself backwards.
        assertNull(checker { "v0.1.0" }.checkForUpdate("0.1.1-beta.3"))
    }

    @Test
    fun `offers the next beta`() = runTest {
        val update = checker { "v0.1.1-beta.3" }.checkForUpdate("0.1.1-beta.2")
        assertEquals("0.1.1-beta.3", assertNotNull(update).version)
    }

    @Test
    fun `a dismissed version is not offered again`() = runTest {
        val dismissals = RememberedDismissals()
        val subject = checker(dismissals) { "v0.1.2" }

        assertNotNull(subject.checkForUpdate("0.1.1"))
        subject.dismiss("0.1.2")
        assertNull(subject.checkForUpdate("0.1.1"))
    }

    @Test
    fun `dismissing one version does not silence the next`() = runTest {
        // The failure that would quietly end the feature: a "not now" that
        // turns off every future notice as well.
        val dismissals = RememberedDismissals()
        checker(dismissals) { "v0.1.2" }.dismiss("0.1.2")

        val update = checker(dismissals) { "v0.1.3" }.checkForUpdate("0.1.1")
        assertEquals("0.1.3", assertNotNull(update).version)
    }

    @Test
    fun `dismissal ignores the leading v`() = runTest {
        val dismissals = RememberedDismissals()
        val subject = checker(dismissals) { "v0.1.2" }
        subject.dismiss("v0.1.2")
        assertNull(subject.checkForUpdate("0.1.1"))
    }

    @Test
    fun `being offline is not an update and not an error`() = runTest {
        assertNull(checker { throw java.io.IOException("no network") }.checkForUpdate("0.1.1"))
        assertNull(checker { null }.checkForUpdate("0.1.1"))
        assertNull(checker { "" }.checkForUpdate("0.1.1"))
    }

    @Test
    fun `a malformed tag never reads as an upgrade`() = runTest {
        // Otherwise one bad release nags every user until the next good one.
        assertNull(checker { "latest" }.checkForUpdate("0.1.1"))
        assertNull(checker { "not-a-version" }.checkForUpdate("0.1.1"))
    }
}
