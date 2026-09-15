package app.keyweb.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A device, as far as opening a browser is concerned.
 *
 * [refuse] is the interesting knob: a package can be resolvable and still fail
 * to start, which is the case that used to leave someone with nothing.
 */
private class FakeDevice(
    private val default: String? = null,
    private val installed: List<String> = emptyList(),
    private val refuse: Set<String?> = emptySet(),
    override val ownPackage: String = "app.keyweb",
) : GrantBrowser.Environment {

    /** Every launch tried, in order. null means an unaddressed intent. */
    val attempts = mutableListOf<String?>()

    override fun defaultHandler(url: String): String? = default

    override fun handlers(url: String): List<String> = installed

    override fun launch(url: String, target: String?): Boolean {
        attempts.add(target)
        return target !in refuse
    }
}

class GrantBrowserTest {

    @Test
    fun `opens the default browser`() {
        val device = FakeDevice(default = "com.android.chrome")

        assertTrue(GrantBrowser.choose(device))
        assertEquals(listOf<String?>("com.android.chrome"), device.attempts)
    }

    @Test
    fun `falls back to any browser when no default is set`() {
        // The resolver activity answers instead of a browser, which is what a
        // device with two browsers and no default does.
        val device = FakeDevice(
            default = "android",
            installed = listOf("android", "org.mozilla.firefox"),
        )

        assertTrue(GrantBrowser.choose(device))
        assertEquals(listOf<String?>("org.mozilla.firefox"), device.attempts)
    }

    @Test
    fun `never routes back into Keyweb`() {
        // Keyweb owning the App Link for its own origin is the one answer that
        // cannot work: the grant page would open inside the app it is for.
        val device = FakeDevice(
            default = "app.keyweb",
            installed = listOf("app.keyweb", "com.android.chrome"),
        )

        assertTrue(GrantBrowser.choose(device))
        assertEquals(listOf<String?>("com.android.chrome"), device.attempts)
    }

    @Test
    fun `tries an unaddressed intent when nothing resolves`() {
        // What a phone reported before the manifest declared <queries>: no
        // browser visible at all. The system may still route a plain intent.
        val device = FakeDevice()

        assertTrue(GrantBrowser.choose(device))
        assertEquals(listOf<String?>(null), device.attempts)
    }

    @Test
    fun `tries an unaddressed intent when the named browser will not start`() {
        val device = FakeDevice(
            default = "com.android.chrome",
            refuse = setOf("com.android.chrome"),
        )

        assertTrue(GrantBrowser.choose(device))
        assertEquals(listOf<String?>("com.android.chrome", null), device.attempts)
    }

    @Test
    fun `reports failure only when every attempt fails`() {
        // The caller shows the copied link at this point, so this must be the
        // genuinely hopeless case and not merely an unusual one.
        val device = FakeDevice(
            default = "com.android.chrome",
            refuse = setOf("com.android.chrome", null),
        )

        assertFalse(GrantBrowser.choose(device))
        assertEquals(listOf<String?>("com.android.chrome", null), device.attempts)
    }

    @Test
    fun `sends the grant url, not the url it probed with`() {
        var opened: String? = null
        val device = object : GrantBrowser.Environment {
            override val ownPackage = "app.keyweb"
            override fun defaultHandler(url: String) = "com.android.chrome"
            override fun handlers(url: String) = listOf("com.android.chrome")
            override fun launch(url: String, target: String?): Boolean {
                opened = url
                return true
            }
        }

        assertTrue(GrantBrowser.choose(device))
        assertEquals(GrantBrowser.GRANT_URL, opened)
    }
}
