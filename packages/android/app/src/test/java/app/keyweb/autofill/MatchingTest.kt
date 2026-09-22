package app.keyweb.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which passwords get offered to whom.
 *
 * The dangerous direction is being too loose. A wrong offer that the user
 * accepts has handed a password to a site that did not have it, and the tap
 * looked exactly like a correct one — so the near-miss cases here are the
 * point of the file, not edge-case padding.
 */
class MatchingTest {

    @Test
    fun `reduces a host to what a person would call the site`() {
        assertEquals("example.com", Matching.registrableDomain("https://www.example.com/login"))
        assertEquals("example.com", Matching.registrableDomain("accounts.example.com"))
        assertEquals("example.com", Matching.registrableDomain("EXAMPLE.COM"))
        assertEquals("example.com", Matching.registrableDomain("http://example.com:8443/x?y#z"))
    }

    @Test
    fun `keeps multi-part suffixes whole`() {
        // Without this every British site reduces to "co.uk" and they all
        // match each other.
        assertEquals("bbc.co.uk", Matching.registrableDomain("https://www.bbc.co.uk"))
        assertEquals("example.co.uk", Matching.registrableDomain("login.example.co.uk"))
        assertFalse(Matching.urlMatches("https://bbc.co.uk", "https://example.co.uk"))
    }

    @Test
    fun `subdomains of one site are that site`() {
        assertTrue(Matching.urlMatches("https://www.example.com/login", "accounts.example.com"))
        assertTrue(Matching.urlMatches("example.com", "https://m.example.com/"))
    }

    @Test
    fun `a lookalike domain is not a match`() {
        // The whole reason this is not a suffix comparison.
        assertFalse(Matching.urlMatches("https://example.com", "https://notexample.com"))
        assertFalse(Matching.urlMatches("https://example.com", "https://example.com.evil.net"))
        assertFalse(Matching.urlMatches("https://example.com", "https://evil-example.com"))
    }

    @Test
    fun `credentials in a url do not become the host`() {
        // "https://example.com@evil.net/" is evil.net, and reading it as
        // example.com is a classic way to be fooled.
        assertEquals("evil.net", Matching.registrableDomain("https://example.com@evil.net/"))
        assertFalse(Matching.urlMatches("https://example.com", "https://example.com@evil.net/"))
    }

    @Test
    fun `nothing matches nothing`() {
        assertNull(Matching.registrableDomain(null))
        assertNull(Matching.registrableDomain(""))
        assertNull(Matching.registrableDomain("   "))
        assertFalse(Matching.urlMatches(null, "example.com"))
        assertFalse(Matching.urlMatches("https://example.com", null))
        assertFalse(Matching.urlMatches("", ""))
    }

    @Test
    fun `an entry with no url is never offered by domain`() {
        // Most imported entries have a URL; the ones that do not must not
        // become a match for everything.
        assertFalse(Matching.urlMatches("", "example.com"))
        assertFalse(Matching.urlMatches("   ", "example.com"))
    }

    @Test
    fun `guesses a domain from a package name, for apps that have no url`() {
        assertEquals("example.com", Matching.domainGuessedFromPackage("com.example.android"))
        assertEquals("example.com", Matching.domainGuessedFromPackage("com.example"))
        assertEquals("bbc.co.uk", Matching.domainGuessedFromPackage("uk.co.bbc.iplayer"))
    }

    @Test
    fun `a package name that says nothing yields nothing`() {
        assertNull(Matching.domainGuessedFromPackage(null))
        assertNull(Matching.domainGuessedFromPackage(""))
        assertNull(Matching.domainGuessedFromPackage("localhost"))
    }

    @Test
    fun `a hosting suffix is its own site per tenant`() {
        assertEquals("alice.github.io", Matching.registrableDomain("https://alice.github.io/login"))
        assertEquals("eve.github.io", Matching.registrableDomain("https://www.eve.github.io/"))
        assertFalse(Matching.urlMatches("https://alice.github.io", "https://eve.github.io"))
        assertFalse(Matching.urlMatches("https://mine.netlify.app", "https://theirs.netlify.app"))
        // The suffix itself is not a site anyone saved a password for.
        assertNull(Matching.registrableDomain("https://github.io"))
    }

    @Test
    fun `an unlisted suffix matches the full host only`() {
        assertEquals("accounts.example.test", Matching.registrableDomain("accounts.example.test"))
        assertFalse(Matching.urlMatches("https://example.test", "https://accounts.example.test"))
    }

    @Test
    fun `fields on different sites are not one fill`() {
        assertTrue(
            Matching.domainForFill("https://alice.github.io", "https://eve.github.io")
                is Matching.FillDomain.Conflict,
        )
        val known = Matching.domainForFill(null, "https://www.example.com/login")
        assertTrue(known is Matching.FillDomain.Known)
        assertEquals("https://www.example.com/login", (known as Matching.FillDomain.Known).domain)
        assertTrue(Matching.domainForFill(null, null) is Matching.FillDomain.None)
    }

    @Test
    fun `an ip address is its own site and matches only itself`() {
        assertEquals("192.168.1.10", Matching.registrableDomain("http://192.168.1.10:8080/"))
        assertTrue(Matching.urlMatches("http://192.168.1.10:8080/", "192.168.1.10"))
        assertFalse(Matching.urlMatches("http://192.168.1.10/", "192.168.1.11"))
    }
}
