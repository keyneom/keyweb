# Feedback for sync-kit: the Android Picker grant handoff

> A second piece of feedback, on Android passkeys, is in
> `sync-kit-feedback-passkeys.md`. Both have the same shape: a capability sync-kit
> ships, stated accurately and briefly enough that a consumer built the wrong
> thing around its apparent absence.

Written from implementing Keyweb against `@keyneom/sync-kit` 0.4.1, with
easy-bc's Android implementation as the reference for what actually works.

## What the README already says

> Browser-only Picker UI remains an adapter concern; Android may hand off to a
> web Picker grant page because SAF grants do not authorize Drive API access.

That is correct and it is the crux. It appears once, in a bullet list under
*Shared encrypted backups*, alongside points about mixed-codec invitations.

## Why one sentence was not enough

The sentence states the conclusion. It does not state the reasoning that makes
the conclusion memorable, or the three implementation details that decide
whether a handoff works at all.

**The reasoning.** `drive.file` grants are scoped to the **Cloud project**, not
to an individual OAuth client — which is exactly what Picker's `setAppId`
expresses, since it takes the project *number* rather than a client id. So a
grant made in a browser under the web client is visible to the Android client in
the same project, and to any other device signed into the same Google account.
That is the property that makes the handoff worth the trouble: access follows
the account and the project, not the device. SAF, by contrast, grants a URI
permission to one app install on one device, which authorizes nothing at the
Drive API and crosses to nothing.

Stated that way, "use SAF" stops being tempting. Stated as a bare conclusion, it
reads like a platform quirk to be worked around.

**Three details that are not optional.** From easy-bc's `GrantBrowser`:

1. **It must be a full browser tab, not a Custom Tab.** Google Identity
   Services' popup token flow breaks inside a Custom Tab: the popup replaces the
   page and the token never reaches the opener.
2. **The browser package must be resolved and forced.** An app that owns the
   App Link for its own web origin will have an unaddressed `ACTION_VIEW` intent
   routed straight back into itself. Resolve the default browser with a
   *neutral* URL, because resolving your own URL returns your own app.
3. **There must be a fallback when no browser resolves** — copying the link for
   the user to open manually.

Each of these is a silent failure. None is discoverable from the format or the
API surface; all three cost a debugging session to find.

## Suggestions, in increasing order of effort

### 1. Documentation

Promote this from a bullet to a short section — `docs/native-consumers.md` looks
like the right home. Include the project-scoping reasoning, the three details
above, and an explicit "do not use SAF for this, and here is why" so the wrong
answer is closed off by name rather than merely not recommended.

### 2. A documented grant-page contract

Consumers are each inventing the same URL shape: a web page that runs
`pickFiles`, and some way for the Android side to learn what was granted. A
specified contract — query parameters in, and a defined way to signal completion
— would mean one implementation instead of one per consumer.

Worth noting: a return channel may not be needed at all. With `drive.file`, the
Android side can call `files.list` and see exactly the files it has been granted,
so the grant *is* the shared state. Saying so in the docs would spare consumers
from building a fileId hand-back they do not need. (Keyweb relies on this.)

### 3. An Android helper

`launchGrantInBrowser(activity, url)` is ~20 lines and identical for every
consumer, and all three pitfalls live inside it. If the Android library already
ships auth and store adapters, this belongs beside them.

## Smaller notes

- `GoogleDriveFolderPicker.pickFiles` is the method most consumers want, but the
  class name says *Folder*. It reads as though file picking is a secondary
  feature of a folder picker. `GoogleDrivePicker` would be clearer.
- The published npm package contains only `README.md`; `docs/native-consumers.md`
  and `docs/android-library.md` are referenced but not shipped, so a consumer
  reading from `node_modules` cannot reach them.
- The README's own statement that live OAuth and Picker validation remain a
  consumer release gate is worth keeping prominent. It is accurate, and it sets
  the right expectation that nothing here is proven until it has run against
  real Google.
