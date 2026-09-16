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
     * `example.com`. Taking the last two labels is the usual approximation,
     * with the common multi-part public suffixes listed because `co.uk` would
     * otherwise reduce `bbc.co.uk` to `co.uk` and make every British site the
     * same site.
     *
     * This is not the Public Suffix List. It is the short head of it, which
     * covers what people actually have accounts on; the failure mode for a
     * suffix not listed is being too strict (offering nothing), never too
     * loose.
     */
    fun registrableDomain(raw: String?): String? {
        val host = hostOf(raw) ?: return null
        // An address is not a domain and has no registrable part. Reducing it
        // to its last two labels would make 10.0.0.5 and 192.0.0.5 both "0.5"
        // — two unrelated machines treated as the same site.
        if (isAddressLiteral(host)) return host

        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return host.takeIf { it.isNotEmpty() }

        val lastTwo = labels.takeLast(2).joinToString(".")
        if (lastTwo in MULTI_PART_SUFFIXES && labels.size >= 3) {
            return labels.takeLast(3).joinToString(".")
        }
        return lastTwo
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

    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "com.br", "com.mx", "com.ar", "com.co",
        "co.in", "net.in", "org.in",
        "co.za", "org.za",
        "com.sg", "com.hk", "com.tw", "com.cn", "com.tr",
    )
}
