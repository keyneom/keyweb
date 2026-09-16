package app.keyweb.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Working out which box is the password.
 *
 * Every case here is a form shape that exists in the wild. The ones that cost
 * people time are not the ordinary login screens — they are the sign-up form
 * with two password boxes, the page with a search bar above the login, and the
 * app that declares its fields properly and gets overridden by a guess.
 */
class FieldFinderTest {

    private fun field(
        id: String,
        hints: List<String> = emptyList(),
        idEntry: String? = null,
        html: Map<String, String> = emptyMap(),
        isPassword: Boolean = false,
        fillable: Boolean = true,
    ) = FormField(id, hints, idEntry, html, isPassword, fillable)

    @Test
    fun `an app's own hints are believed first`() {
        val found = FieldFinder.find(
            listOf(
                field("u", hints = listOf("username")),
                field("p", hints = listOf("password")),
            ),
        )
        assertEquals("u", found.username?.id)
        assertEquals("p", found.password?.id)
    }

    @Test
    fun `a declared hint for something else is a denial, not a maybe`() {
        // A one-time-code box is often obscured. Treating it as the password
        // would fill a password into the 2FA field.
        val found = FieldFinder.find(
            listOf(field("otp", hints = listOf("smsOTPCode"), isPassword = true)),
        )
        assertNull(found.password)
    }

    @Test
    fun `an html login form is read from its types`() {
        val found = FieldFinder.find(
            listOf(
                field("u", html = mapOf("type" to "email", "name" to "email")),
                field("p", html = mapOf("type" to "password", "name" to "password")),
            ),
        )
        assertEquals("u", found.username?.id)
        assertEquals("p", found.password?.id)
    }

    @Test
    fun `falls back to field names when nothing is declared`() {
        val found = FieldFinder.find(
            listOf(field("u", idEntry = "login_user"), field("p", idEntry = "login_password")),
        )
        assertEquals("u", found.username?.id)
        assertEquals("p", found.password?.id)
    }

    @Test
    fun `a search box is not a username`() {
        // It contains "user" often enough to matter, and filling it would be
        // both wrong and visible.
        val found = FieldFinder.find(
            listOf(
                field("s", idEntry = "user_search_query"),
                field("p", html = mapOf("type" to "password")),
            ),
        )
        assertEquals("p", found.password?.id)
        assertNull(found.username)
    }

    @Test
    fun `the username is the one before the password, not after it`() {
        // A sign-up form: email, password, confirm password. Looking after the
        // password finds the confirm box and calls it a username.
        val found = FieldFinder.find(
            listOf(
                field("email", html = mapOf("type" to "email", "name" to "email")),
                field("pw1", html = mapOf("type" to "password", "name" to "password")),
                field("pw2", html = mapOf("type" to "password", "name" to "confirm_password")),
            ),
        )
        assertEquals("email", found.username?.id)
        // The first password box is the one to fill, not the confirmation.
        assertEquals("pw1", found.password?.id)
    }

    @Test
    fun `a forgotten-password link is not a password field`() {
        val found = FieldFinder.find(
            listOf(field("link", idEntry = "forgot_password_button")),
        )
        assertNull(found.password)
    }

    @Test
    fun `fields that cannot be filled are ignored`() {
        val found = FieldFinder.find(
            listOf(
                field("hidden_pw", html = mapOf("type" to "password"), fillable = false),
                field("real_pw", html = mapOf("type" to "password")),
            ),
        )
        assertEquals("real_pw", found.password?.id)
    }

    @Test
    fun `a password-only screen is still worth filling`() {
        // Two-step sign-in asks for the password on its own page.
        val found = FieldFinder.find(listOf(field("p", hints = listOf("password"))))
        assertEquals("p", found.password?.id)
        assertNull(found.username)
        assertTrue(!found.isEmpty)
    }

    @Test
    fun `a username-only screen is worth filling too`() {
        val found = FieldFinder.find(listOf(field("u", hints = listOf("username"))))
        assertEquals("u", found.username?.id)
        assertNull(found.password)
        assertTrue(!found.isEmpty)
    }

    @Test
    fun `a form with nothing to fill is left alone`() {
        val found = FieldFinder.find(
            listOf(
                field("s", idEntry = "search_box"),
                field("b", html = mapOf("type" to "submit")),
            ),
        )
        assertTrue(found.isEmpty)
    }
}
