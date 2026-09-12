# Second factors in Keyweb

Three separate questions get run together here. They have different answers.

## 1. Is it a problem to keep the password and its second factor in one app?

Less than most people assume, and the honest answer has a shape worth stating
rather than a verdict.

The objection is that two factors stored together are one factor. That is true
in exactly one scenario — **vault compromise** — and in that scenario the second
factor was never going to help, because whoever opened the vault already has
every password in it.

What 2FA actually defends against in practice is **remote credential
compromise**: a reused password, a breached database, a phishing page, a
keylogger on someone else's machine. None of those yield the TOTP secret, which
never leaves this vault and is never typed into a site. An attacker holding the
password still cannot produce a code.

The comparison that matters is not "separate app" versus "same app". It is
**"second factor" versus "no second factor"**, because for the person this app
is built for, the realistic alternative is SMS or nothing. A second factor they
actually use beats a better one they abandon.

Two things follow from taking the objection seriously rather than dismissing it:

- **Never fill both in one silent action.** Autofill may offer the password;
  producing the code stays a separate, deliberate tap. An attacker who gets one
  keystroke of assent should not get a whole sign-in.
- **TOTP is phishable.** A code typed into a convincing fake works for thirty
  seconds. This is a property of TOTP, not of storing it here. Where a site
  offers passkeys, they are strictly better, and Keyweb should say so.

## 2. Can Keyweb be a U2F / security key?

Partly. The distinction is which side of the device the relying party is on.

| | Feasible? | Why |
| --- | --- | --- |
| **Passkey provider on the same Android device** (websites in Chrome, other apps) | **Yes** | Android 14+ exposes `CredentialProviderService`. This is the API 1Password, Bitwarden and Dashlane use. |
| **Phone as a security key for a *different* computer** (USB/NFC/BLE token) | **No** | There is no public API to expose FIDO HID or the hybrid/caBLE transport. Google Play Services implements phone-as-authenticator itself and does not admit third parties. |
| **Passkey provider in the web app** | **No** | A web page can *consume* WebAuthn, never provide it. |

So "use the secure chip as the physical device" is achievable for
**same-device** sign-ins, and not achievable for signing in on a laptop using
the phone. That second one is Play Services' job and stays Play Services' job.

### The tradeoff that has to be decided before building it

A passkey's private key can live in one of two places, and this is a real fork,
not an implementation detail:

- **In the AndroidKeyStore (TEE/StrongBox).** Strongest possible: the key cannot
  be extracted even from a rooted phone. It also **cannot be backed up**. Lose
  the phone and every passkey on it is gone — which contradicts the guarantee
  the rest of this app is built around.
- **In the vault, as ordinary sealed data.** Syncs, backs up, restores onto a
  replacement phone like everything else. The key is protected by the vault key
  rather than by hardware, so a compromised unlocked device exposes it.

Every shipping third-party passkey provider chooses the second, for the reason
above. Keyweb's whole no-data-loss argument points the same way. Worth being
explicit that this means **the secure chip protects the vault, not each
individual passkey** — the chip is still doing work, just one level up.

## 3. Why the TOTP secret is an ordinary field

`otp` sits alongside `password` in the item's fields, sealed by the same
envelope, merged by the same per-field register. No separate store, no special
casing in the CRDT.

Stored as a full `otpauth://` URI even when the site supplied a bare base32
secret, so the digit count, period and hash algorithm travel with it. A site
using 8 digits on a 60-second period is uncommon but real, and a secret stored
without those would produce confident, wrong codes.

### Implementation notes

Both platforms are checked against the **RFC 6238 Appendix B vectors** for
SHA-1, SHA-256 and SHA-512 — not against each other. Two implementations that
agree but are both wrong is the worse failure: every code rejected everywhere,
with nothing to indicate why.

The vectors include `t = 20000000000`, which is past the point where a naive
32-bit counter shift wraps. In JavaScript, bit operations are 32-bit, so the
high word of the counter must be computed by division rather than shifting. That
bug would not surface until long after release.

A mistyped secret is rejected at entry by attempting to decode it. Accepting it
silently is the cruel outcome: the app shows six confident digits and the site
refuses every one, with no way for anyone to work out which end is wrong.
