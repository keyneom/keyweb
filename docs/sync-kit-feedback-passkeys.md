# Feedback for sync-kit: Android passkeys, and the prerequisite nobody mentions

Written from implementing Keyweb against `@keyneom/sync-kit` and
`com.keyneom:sync-kit-android` 0.4.1, with easy-bc as the reference for what
actually works.

This one cost a user their cloud backup, so it is worth more than a bullet.

## What we concluded, and what it cost

Keyweb concluded that an Android app cannot derive the passkey PRF secret. Every
consequence below follows from that single wrong belief:

- The Drive backup is sealed **twice** — once under a passkey the browser holds,
  once under a printed recovery code the phone uses — because there appeared to
  be no key both platforms could reach.
- The phone can only rewrite the recovery envelope and the browser can only
  rewrite the passkey one, so **the two copies diverge**. After any phone edit
  the browser's copy is stale, and the browser shows old data rather than
  nothing.
- The recovery code stopped being a way back in and became **the phone's
  everyday key**. When a browser holding its own recovery code resealed that
  envelope, the phone was locked out of its own backup and crashed on every
  sync.
- The sharing identity was likewise derived from the recovery secret rather than
  a passkey, under a commit message that says so as though it were a constraint.

None of it was necessary. `AndroidPasskeyKeyProvider` — with `unlockPrf` — is in
the 0.4.1 AAR we were already depending on. easy-bc uses exactly that: **one
envelope, passkey-derived, read and written by its browser and its phone
alike.**

## What the docs say today

`docs/android-library.md` has the capability, as a row in a table:

| `AndroidPasskeyKeyProvider` | Credential Manager PRF |

That is accurate. It is also the whole of it, in the "Private snapshot
(shipped)" inventory. `docs/native-consumers.md` mentions "Android Credential
Manager for passkey PRF" in a list of platform pieces.

What neither says is the thing a consumer actually needs to know:

> **A phone and a browser can hold the same key.** You do not need a second
> envelope, a second key path, or a shared secret of your own.

That sentence would have prevented all of the above. The table row did not,
because a row in a component inventory reads as "this type exists", not as "this
closes the gap you are about to design around".

## The prerequisite that appears nowhere

Credential Manager will not use a passkey for a web RP ID unless that domain's
`/.well-known/assetlinks.json` names the app with
`delegate_permission/common.get_login_creds`.

Searching the whole sync-kit repository:

| Term | Occurrences |
| --- | --- |
| `assetlinks` | 0 |
| `get_login_creds` | 0 |
| "asset links" | 0 |

`docs/android-library.md` lists "RP ID, exact web/APK origin allowlist, Google
server/web OAuth client ID" as application-owned, which is right — but the asset
link is not an origin allowlist and is not in that list. It is a file on a web
server, in a different repository from the app, and it is a hard precondition
for `AndroidPasskeyKeyProvider` to work at all.

We got this wrong in a way worth recording, because it is the failure mode the
documentation should be written against. Keyweb published an asset link for App
Link verification with `handle_all_urls` only, and deliberately left
`get_login_creds` off, with this reasoning committed alongside it:

> Only handle_all_urls. get_login_creds would let the app receive credentials
> saved for this domain, which a password manager has no business asking for and
> does not need.

That reads as a careful security decision. It is the opposite: the same relation
is what lets the app hold the passkey **its own vault is sealed with**. easy-bc's
entry has had both relations all along, which is why easy-bc works — but nothing
in easy-bc's code says why, and nothing in sync-kit's docs says why either.

## Suggestions, in increasing order of effort

### 1. Say the conclusion, not just the capability

Somewhere early in `docs/android-library.md` and `docs/native-consumers.md`:

> **Android can derive the same passkey PRF secret as the browser.** One
> envelope serves both platforms. Do not design a second key path for Android.

Naming the wrong answer is what makes it stick — the same thing the Picker
feedback asked for about SAF. "Do not design a second key path" closes off the
mistake by name; "AndroidPasskeyKeyProvider — Credential Manager PRF" does not.

### 2. Document the asset link as a prerequisite

A short section with the file itself, because nobody guesses a JSON shape:

```json
[
  {
    "relation": [
      "delegate_permission/common.get_login_creds"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "com.example.app",
      "sha256_cert_fingerprints": ["AA:BB:…"]
    }
  }
]
```

Worth saying explicitly, because each one costs a debugging session:

- it is served from the **RP ID domain root**, which for a GitHub Pages project
  site is a different repository from the app;
- the fingerprint identifies the **signing certificate**, so a re-signed build is
  a different app and every passkey stops resolving;
- `handle_all_urls` and `get_login_creds` are independent. An app can have
  verified App Links and no passkey access, which is a state that looks fine
  from `pm get-app-links`.

### 3. Make the failure say what is wrong

If `AndroidPasskeyKeyProvider` can tell the difference between "the user
cancelled", "no credential exists" and "this app is not authorised for this RP
ID", the third deserves its own error naming the missing asset link. A consumer
who sees a generic Credential Manager failure on their first passkey attempt
will reasonably conclude the platform cannot do it — which is precisely the
conclusion that produced everything in the first section.

### 4. A worked example of the two-platform key

`docs/compatibility-v1.md` freezes the wire format. What is missing is the
smaller, more useful claim beside it: here is a browser sealing an envelope and
a phone opening the same one, with the RP ID, the asset link and the PRF input
lined up. easy-bc is that example already; it is just not written down anywhere
a new consumer would look.

## What we would keep

Not everything about Keyweb's arrangement was a mistake. easy-bc has **no**
recovery path, so a lost passkey there is lost data, and for a password manager
that is not acceptable. The recovery code should stay — as a way back in, which
is what it was for. It should never have been one platform's primary key, and it
only became one because the passkey looked unavailable.
