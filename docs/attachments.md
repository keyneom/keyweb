# Files attached to a password

Written 2026-09-16, after the KeePass import was found to be dropping them
silently. Everything else that import used to lose now comes across; this is
the part that needs a format decision first, and this document is that
decision not being made in a hurry.

## What is at stake

People keep things in a password manager that are not passwords: a scan of a
passport, a recovery-code sheet, a `.key` file, a photo of a safe combination.
KeePass stores them as entry attachments, and Keyweb has nowhere to put them.

The failure that matters is not "Keyweb lacks a feature". It is that somebody
imports, sees a sensible-looking count, deletes the `.kdbx`, and loses the
scan. Until a format exists, the import names every file it cannot carry and
says to keep the original — that turns silent loss into a decision, which is
the part that could not wait.

## Why not just put them in a field

`ItemField` values are strings and the vault is one encrypted document. A 5 MB
scan becomes ~6.7 MB of base64 inside a state that is:

- re-encrypted in full on every single edit, anywhere in the vault;
- re-uploaded in full on every sync;
- held in memory decrypted while the app is open;
- merged field-by-field by a CRDT that would carry both copies of a conflict.

This is precisely the cost class that made deleting a 200-password keyring take
most of a minute, fixed by making bulk edits one write. Inlining attachments
would reintroduce it permanently and make it proportional to how much somebody
has stored rather than to what they just did.

It is also wrong for sharing. A shared keyring's document is fetched whole; a
recipient would download every attachment to read one password.

## The shape that fits

Stage one of sharing already built what this needs: **storage is plural**. A
document is addressed by id, synced independently, and may be absent locally
without breaking the vault — `knownDocuments` exists precisely because the
vault's idea of what exists and what is on disk legitimately differ.

So an attachment should be its own document:

- the item holds a reference — id, filename, size, content hash — in an
  ordinary field, so it merges, syncs and shares like everything else and costs
  a few dozen bytes;
- the bytes live in a document of their own, encrypted with the same envelope
  and fetched **on demand**, not on every sync;
- an attachment on a shared keyring lives in a document bound to that keyring's
  dataset, so it is shared with the keyring and revoked with it;
- deleting an item purges its attachment documents the way `leaveKeyring`
  purges a dataset — removed means the bytes go.

Three things have to be decided before this is written:

1. **A size ceiling, and what happens at it.** Drive will hold a 200 MB file;
   a phone opening a vault should not. A ceiling that refuses with a clear
   sentence is better than one that silently truncates or one that does not
   exist.
2. **Eviction.** "Fetched on demand" means a device accumulates attachment
   documents it may never need again. There must be a rule for dropping the
   local copy while keeping the reference, and it must never drop the *only*
   copy — which means knowing the remote has it.
3. **What the CRDT does with two attachments of the same name.** Content
   hashes make that answerable without a merge rule for bytes: same hash, same
   file, keep one.

## Until then

The import names the files it cannot carry, on screen, before the button that
copies everything else in — and says to keep the original file. Both platforms
do this, and `fixtures/keepass-rich.kdbx` has an entry with an attachment so
neither can quietly stop.
