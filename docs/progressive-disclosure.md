# Keeping the app simple while it grows

Keyweb now does a lot: files, one-time codes, fields you name yourself,
earlier values, keyring sharing, import, backup. Almost all of it arrived on
the same two screens — the one that shows a password and the one that edits
it. Left alone, the app teaches a first-time user that a password is a
complicated object.

## The rule

**The common path is always visible. Everything else sits behind one
disclosure, labelled in the words the person would use.**

That is a per-screen expander, not a mode.

### Why not an "advanced mode" toggle

It was the obvious idea and it is the wrong one:

- **A mode is invisible state.** Somebody who turned it on last month meets a
  different app than the one they learned, and has no way to connect what
  changed to something they did.
- **It hides the feature from the people who need it.** The person who would
  benefit from a second-factor code is not the person who goes looking through
  settings for a switch called "advanced".
- **It doubles the design surface.** Every screen has two states to lay out,
  two states to test, and two states to describe in a bug report.
- **The word itself turns people away.** "Advanced" tells somebody that the
  thing they are looking for is not for them. No label in this app uses it.

Disclosure in place has none of that. It costs one tap, it is discoverable
exactly where it is relevant, the app is the same app every time it opens, and
the common path is as short as it was before the feature existed.

### What goes behind one

Rare, not difficult. A second-factor seed is easy to understand and most
logins do not have one. A security question is easy to understand and most
logins do not have one.

Never: something the person can already see. A disclosure that closes over a
field somebody has been reading is not simplification, it is the same
disappearance the KeePass import used to cause — so a section opens itself
when the item already has something in it.

Never: a destructive action. Delete stays in the open, where it can be
recognised and avoided. Hiding danger is not the same as reducing it.

### Writing the label

Say what is inside, in the words the person would use for it.

- Yes: "Anything else this login needs", "What this used to be"
- No: "Advanced", "Options", "More" on its own

A closed disclosure may carry one line of hint text saying what is inside, so
the decision to open it is informed rather than exploratory.

## Where it is

- `packages/web/src/ui/Disclosure.tsx` — a `<details>` element, so it opens
  without JavaScript, announces itself to a screen reader, and is found by the
  browser's own find-in-page.
- `Disclosure` in `packages/android/app/src/main/java/app/keyweb/ui/Components.kt`
  — state survives rotation via `rememberSaveable`.
