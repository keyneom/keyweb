# Sharing a keyring with someone else

Design, written before any code, because the first decision changes the vault
format and is expensive to reverse. Modelled on easy-bc, which has shipped this
and whose mistakes are therefore already paid for.

## Status

**Built, on both platforms.** The sentence three screens already showed —

> A keyring is a group of passwords you can share as one. You share a keyring —
> never a single password.

— is now true. What follows is the design as written before any code, kept
because the reasoning is still the reasoning; a *What actually shipped* section
at the end records where the build diverged from it and why.

## What easy-bc does

Roughly 9,800 lines across 49 modules in the web tree alone, plus the Android
side. Worth reading rather than reinventing; `docs/join-flow.md` and
`docs/sync-kit-hybrid-identity.md` in that repo are the two that matter.

The shape:

- **The shared unit is a dataset**, which is one Drive file. Sharing means
  giving another Google account access to that file and giving their sharing
  identity the key to open it.
- **Identity is a keypair per person**, ECDH/ECDSA P-256, kept in Drive
  `appdata` wrapped by a passkey so the same person's second device can unwrap
  it. Keyweb already provisions exactly this — `docs/google-setup.md` explains
  why `drive.appdata` is requested — and already stores one.
- **The exchange is link-carried and needs no server.** The owner produces a
  signed invitation (`sk-inv`) naming the files; the joiner grants themselves
  file access through the Picker, registers, and produces a signed key response
  (`sk-kr`); the owner accepts it and the joiner can read. Two links, passed by
  whatever messaging people already use.
- **A control dataset** records membership separately from the data, so who has
  access is itself signed and syncable rather than inferred from Drive ACLs.
- **All interactive authentication happens before the browser hand-off**, once,
  behind a single-flight gate — because a background sync competing for the
  passkey prompt mid-flow is the bug that ate a week.

## Where Keyweb differs, and the decision

easy-bc shares a *whole profile*. Keyweb wants to share *one keyring out of a
vault*, and the vault today is a single encrypted document holding every
keyring and item, published as one Drive file under one key.

So a shared keyring cannot simply be "the vault, shared".

### Option A — a shared keyring becomes its own dataset

Its items live in their own document, with their own key, in its own
user-visible Drive file. The main vault keeps the keyring's name and a pointer.
Sharing is then exactly what sync-kit already does, and a recipient receives
the bytes of that keyring and nothing else.

### Option B — per-keyring keys inside the one vault file

Keep one file; encrypt each keyring's items under a separate key; hand over the
key for the shared one.

**Option B does not actually work.** Drive sharing is per *file*. A recipient
holding a keyring key still needs access to the file, and granting that gives
them every other keyring's ciphertext, every item id, every keyring name, and
the shape of the whole vault. They could not read the other passwords, but they
would hold them, for as long as they kept the file — and a future weakness in
the cipher becomes a breach of everything rather than of one folder.
`docs/google-setup.md` already assumes otherwise: *"Shared keyrings live in
normal, user-visible Drive under this scope because that is the only place
Google permits sharing a file with another person."*

**Option A is the design.** It is more work — items move between documents when
a keyring is shared, and the CRDT has to span documents — but it is the only
one where sharing a keyring shares a keyring.

## What that means concretely

1. **The vault document stops being the only document.** A keyring is either
   *local* (its items live in the vault, as today) or *shared* (its items live
   in their own dataset). Nothing changes for a vault with no shared keyrings,
   which must remain true through the whole change.
2. **Moving a keyring to shared is a migration**, one per keyring, and has to
   be atomic in the same way the bulk delete now is: items copied into the new
   document and removed from the vault in one write, or not at all.
3. **Sync becomes plural.** Today one remote is published on a timer. Each
   shared keyring is another remote with its own revision and its own conflicts.
4. **The status line has to say more.** "Saved here and backed up" is currently
   about one file. With shared keyrings it is about several, and one of them
   failing is a different sentence from all of them failing.
5. **Roles.** easy-bc has readers and writers. Keyweb should start with both
   too — a shared keyring nobody can add to is half a feature — but revocation
   and ownership transfer can wait.

## Staging

Each stage is shippable and leaves the app working. All four are done.

1. **Dataset-backed keyrings, alone.** A keyring can live in its own document
   and sync, with no sharing at all. This is the vault-format work and the
   riskiest part; doing it by itself means the format change is proven before
   anyone's data depends on a second person.
2. **Identity.** Adopt sync-kit's sharing identity store — the appdata keypair
   Keyweb already has scope for — and show the person's own sharing identity
   exists.
3. **Invite and join.** The two links, the Picker grant hand-off (reusing
   `GrantBrowser`, which already solves the Android browser problem), and the
   accept step.
4. **Membership and roles**, then revocation. Reader and writer, who can see
   it, and taking access away — which re-encrypts for everyone else and drops
   the Drive permission, and says plainly that it cannot unsee what was already
   seen.

## What actually shipped

Four places where building it changed the design.

**The sharing identity is wrapped by the recovery code, not the passkey.** The
plan assumed sync-kit's passkey-wrapped identity carried over. It does not:
Keyweb on Android is locked by the Android Keystore and a fingerprint, and
opens the Drive backup through the *recovery* envelope precisely because there
is no WebAuthn PRF on that side. An identity only the browser could unwrap
would have left the phone making its own — one person appearing as two
participants, unable to open the keyrings they shared themselves. The recovery
secret is the one secret both platforms genuinely hold, and deriving from it
adds no exposure, since anyone holding it can already restore the whole vault.

A cross-platform fixture pins this: the browser writes an identity record, a
Kotlin test unwraps that exact file, and the key ids have to match.

The cost is narrow: a browser that has the passkey but was never given the
printed code can read and back up the vault but cannot share until the code is
entered. That device already knows it is in that state and already says so.

**Moving a password between documents needed a new operation.** A CRDT cannot
forget — leaving an item out of a document's next state does not remove it, it
comes back on the next merge. So a move is a *purge* in the document it leaves
and a full copy in the one it arrives in, stamped later so the live copy wins
wherever the two meet. `item.purge` blanks the fields and drops the history
rather than only tombstoning, because "I took that one out of the shared
folder" has to be true of the file the other person reads, not just of the
screen.

**Renames follow the keyring; deletes do not.** The name is part of what was
shared, so it lives in the shared document. A delete stays in the vault: for a
keyring somebody else shared, "delete" means *leave*, and a tombstone published
into the shared document would delete it out from under everyone else.

**The phone hands the file grant to a browser, and hands over the file list
rather than the join link.** `drive.file` is per-file and Google issues it only
through the Picker, which runs only in a browser. Opening the *join link* over
there would join the keyring into whatever vault that browser has — the wrong
vault, and often no vault at all. So the phone keeps the vault and the join,
and the browser does the one thing only it can.

## The open questions, answered

- **Does a shared keyring keep working offline?** Yes. The identity is cached
  on the device in its wrapped form — unreadable without the recovery secret,
  which is the same reason it is safe to leave in Drive — and read before Drive
  rather than after. A device that has joined needs no network to open what it
  joined. A device that has *never* fetched the identity does need one, and
  says "couldn't reach" rather than "no identity", because the second answer
  would make it mint a duplicate.
- **What does the recipient see it as?** A keyring in their own vault. Removing
  it means *leave*: the keyring is tombstoned locally and the binding cleared,
  and the shared document is not touched. The screen says so — "the person who
  shared it keeps their copy" — because the word on the button is the same word
  that deletes things elsewhere.
- **TOTP in a shared keyring.** Still open, still out of scope.
  `docs/two-factor.md` says never to fill a password and a code in one action;
  a shared second factor is a further question.

## Roles

Four exist in the protocol and three can be granted:

| Role | May read | May write | May invite and revoke | May hand it over |
| --- | --- | --- | --- | --- |
| viewer | yes | | | |
| writer | yes | yes | | |
| admin | yes | yes | yes | |
| owner | yes | yes | yes | yes |

`admin` was left unoffered for a while on the grounds that "a second person who
can invite more people is a bigger decision than a checkbox". That was true and
the conclusion was wrong: leaving it out meant a shared keyring had exactly one
person who could ever add anybody, so a couple sharing a household had a
household only one of them could add to, and losing that account meant nobody
could add anyone again. A bigger decision earns a sentence explaining it, not
concealment.

`owner` is not granted, it moves. Exactly one person holds it.

## Ownership transfer

Only somebody already on the keyring can be made the owner — the protocol's
rule rather than a simplification, since the new owner must already hold a key
on the dataset for there to be anything to re-sign the head with.

It rides on one link, not two. The recipient accepts *and* finalises without
anything coming back, so the person handing it over is finished once they have
sent it. Nothing changes until the link is opened, which means there is no
moment where the keyring belongs to nobody, and an owner who changes their mind
before sending has changed nothing. Accepting and finalising are a single call
because they are a single decision for the person: a transfer accepted but
never published is a keyring with two people believing different things about
who owns it. sync-kit recognises datasets it has already transferred by
transfer id, so retrying after an interruption is safe rather than a second
transfer.

The outgoing owner stays on as an admin. Handing over a household keyring
almost never means "and remove me from it" — and if it does, the new owner can
now do that, which is the point of there being a new owner.

## Still to build

- **Account binding.** sync-kit can carry a Google ID token plus a passkey
  assertion in a key response, so the inviter's screen could say "wrap this
  keyring to mika@gmail.com" instead of naming a key id nobody checks. The web
  has both halves. Android has no passkey — the sharing identity is derived
  from the recovery code precisely because it does not — so the binding cannot
  be produced there, and requiring it would lock phones out of sharing
  altogether. It needs the App Link work below first, since an Android passkey
  needs the same `assetlinks.json`.
- **Link verification on Android.** The app claims its own web address, but
  Android will not honour that until an `assetlinks.json` naming it is served
  from the root of `keyneom.github.io` — a file in another repository. Until
  then a pasted link is the path that works.
