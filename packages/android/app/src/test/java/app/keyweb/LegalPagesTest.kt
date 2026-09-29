package app.keyweb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalPagesTest {
    @Test
    fun pagesAreTheHostedPolicyAndTerms() {
        assertTrue(LegalPages.isPage(LegalPages.PRIVACY))
        assertTrue(LegalPages.isPage(LegalPages.TERMS))
        assertTrue(LegalPages.isPage(LegalPages.PRIVACY + "?from=app"))
    }

    @Test
    fun aShareLinkIsNotALegalPage() {
        assertFalse(LegalPages.isPage("https://keyneom.github.io/keyweb/?sk-invite=1"))
        assertFalse(LegalPages.isPage("https://keyneom.github.io/keyweb/"))
        assertFalse(LegalPages.isPage("https://example.com/privacy.html"))
    }
}
