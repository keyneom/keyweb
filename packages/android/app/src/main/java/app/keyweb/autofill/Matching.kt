package app.keyweb.autofill

/**
 * Deciding which saved passwords belong to the thing asking.
 *
 * This is the part of autofill that has to be right. Offering the wrong
 * password is not a cosmetic bug: a page at `evil-example.com` that is offered
 * the `example.com` password has been handed it, and the user tapped once
 * believing the phone knew what it was doing. So matching is deliberately
 * strict, and everything here is pure so it can be tested without a device.
 */
object Matching {

    /**
     * The registrable part of a host, as a person would recognise it.
     *
     * "Same site" cannot mean "same host" — `accounts.example.com` and
     * `www.example.com` are one login to a person — and it cannot mean "ends
     * with the same string", which is how `notexample.com` matches
     * `example.com`.
     *
     * This is not the Public Suffix List. It is a compact list of ordinary
     * suffixes and the hosting suffixes people actually save logins on
     * (`github.io`, `netlify.app`, …). A listed suffix plus one label is the
     * site. A host that *is* the suffix has no site. A suffix that is not
     * listed matches the full host only, so an unknown multi-tenant host can
     * never collapse two tenants into one site.
     */
    fun registrableDomain(raw: String?): String? {
        val host = hostOf(raw) ?: return null
        // An address is not a domain and has no registrable part. Reducing it
        // to its last two labels would make 10.0.0.5 and 192.0.0.5 both "0.5"
        // — two unrelated machines treated as the same site.
        if (isAddressLiteral(host)) return host

        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty()) return null
        val suffixLen = (labels.size downTo 1).firstOrNull { length ->
            labels.takeLast(length).joinToString(".") in PUBLIC_SUFFIXES
        } ?: return host
        // The suffix itself (`github.io`, `co.uk`) is not a site.
        if (labels.size <= suffixLen) return null
        return labels.takeLast(suffixLen + 1).joinToString(".")
    }

    /**
     * Which domain a fill is for.
     *
     * Taken from the fields being filled, not from some other view in the
     * same window. Two fields that name different sites are not filled at
     * all. No domain means a native app, and the package-name guess may run.
     */
    fun domainForFill(usernameDomain: String?, passwordDomain: String?): FillDomain {
        val username = usernameDomain?.let { registrableDomain(it) }
        val password = passwordDomain?.let { registrableDomain(it) }
        if (username != null && password != null && username != password) return FillDomain.Conflict
        val domain = passwordDomain ?: usernameDomain
        return if (domain.isNullOrBlank()) FillDomain.None else FillDomain.Known(domain)
    }

    sealed class FillDomain {
        /** A page domain the fields themselves carried. */
        data class Known(val domain: String) : FillDomain()
        /** Native fields. A package-name guess is allowed. */
        data object None : FillDomain()
        /** Username and password disagree about the site. Offer nothing. */
        data object Conflict : FillDomain()
    }

    /** An IPv4 dotted quad, or anything containing a colon, which is IPv6. */
    private fun isAddressLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toInt() <= 255
        }
    }

    /** Host from a URL, a bare host, or null when there is nothing usable. */
    private fun hostOf(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return null

        val withoutScheme = trimmed.substringAfter("://", trimmed)
        val withoutCredentials = withoutScheme.substringAfterLast('@')
        val hostAndPort = withoutCredentials
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        // IPv6 literals are bracketed; a port is the last colon otherwise.
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']').removePrefix("[")
        } else {
            hostAndPort.substringBefore(':')
        }
        return host.trim('.').takeIf { it.isNotEmpty() }
    }

    /**
     * Does a saved entry's URL belong to [domain]?
     *
     * Compared on the registrable domain at both ends, so a saved
     * `https://www.example.com/login` matches a request from
     * `accounts.example.com` and does not match `example.com.evil.net`.
     */
    fun urlMatches(savedUrl: String?, domain: String?): Boolean {
        val want = registrableDomain(domain) ?: return false
        val have = registrableDomain(savedUrl) ?: return false
        return want == have
    }

    /**
     * A last resort for native apps, which have a package name and no URL.
     *
     * `com.example.android` reversed is `android.example.com`, whose
     * registrable domain is `example.com` — which is usually the right answer
     * and occasionally is not. It is offered only when nothing matched by URL,
     * and never treated as proof: the user still chooses from a list.
     *
     * Android's real answer to this is Digital Asset Links, which asks the
     * domain whether it vouches for the app's signature. That is the correct
     * mechanism and it needs a network call per lookup, so it is not here yet.
     */
    fun domainGuessedFromPackage(packageName: String?): String? {
        val parts = packageName?.trim()?.lowercase()?.split('.')?.filter { it.isNotEmpty() }
        if (parts == null || parts.size < 2) return null
        return registrableDomain(parts.reversed().joinToString("."))
    }

    /**
     * Public suffixes, longest match wins.
     *
     * Ordinary TLDs are here so `www.example.com` still matches `example.com`.
     * Hosting suffixes are here so `alice.github.io` does not match
     * `eve.github.io`. Anything absent matches the full host only.
     */
    private val PUBLIC_SUFFIXES = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int",
        "io", "app", "dev", "ai", "co", "me", "info", "biz", "xyz", "online", "site",
        "us", "uk", "ca", "de", "fr", "nl", "se", "no", "fi", "es", "it", "pl",
        "br", "mx", "ar", "co", "in", "jp", "au", "nz", "za", "sg", "hk", "tw", "cn", "tr", "eu",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "com.br", "com.mx", "com.ar", "com.co",
        "co.in", "net.in", "org.in",
        "co.za", "org.za",
        "com.sg", "com.hk", "com.tw", "com.cn", "com.tr",
        "github.io", "githubusercontent.com", "gitlab.io",
        "netlify.app", "vercel.app", "pages.dev", "workers.dev",
        "appspot.com", "web.app", "firebaseapp.com",
        "herokuapp.com", "azurewebsites.net", "onrender.com", "fly.dev",
        "railway.app", "repl.co", "glitch.me",
        "blogspot.com", "wordpress.com", "myshopify.com", "wixsite.com",
        "notion.site", "webflow.io", "squarespace.com",
        "cloudfront.net", "amazonaws.com",
    )
}
