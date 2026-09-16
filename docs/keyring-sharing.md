# Sharing a keyring with someone else

Design, written before any code, because the first decision changes the vault
format and is expensive to reverse. Modelled on easy-bc, which has shipped this
and whose mistakes are therefore already paid for.

## What the app currently promises

Three screens already tell people this works:

> A keyring is a group of passwords you can share as one. You share a keyring —
> never a single password.

Nothing behind it exists. `packages/android/README.md` is honest — "keyring
sharing" sits under *Not in this build* — but the copy is not, and anyone
reading it goes looking for a button. That is the first thing to fix whatever
else is decided, and it is fixed by building this rather than by softening the
sentence.

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

Each stage is shippable and leaves the app working:

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
4. **Membership and roles**, then revocation.

## Open questions

- **Does a shared keyring keep working offline?** It must. The vault is
  local-first and says so; a shared keyring that is unreadable on a train would
  break that promise for the passwords most likely to be needed.
- **What does the recipient see it as?** A keyring in their own vault, or a
  separate thing? Their own, probably — but then deleting it must mean
  "leave", not "delete everyone's".
- **TOTP in a shared keyring.** `docs/two-factor.md` already says never to fill
  a password and a code in one action. A shared second factor is a further
  question and is out of scope for now.
