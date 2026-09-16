# Files attached to a password

Built. This document records why the first design was wrong, because the
reasoning that produced it is the kind that repeats.

## The wrong turn

The first pass refused to store attachments at all, on the grounds that the
vault is one encrypted document and inlining a 5 MB scan would mean
re-encrypting and re-uploading it on every edit. A blob store with on-demand
fetching, eviction and a size ceiling was sketched as a prerequisite.

The objection that undid it: **KeeWeb rewrites and re-uploads its entire
`.kdbx` on every save, attachments included, and people use it happily.** The
storage model being argued against is the one the thing we import from already
uses successfully.

So it was measured, on the real envelope path, with incompressible bytes:

| attached | seal | open | on the wire |
| --- | --- | --- | --- |
| 0 MB | 6 ms | 1 ms | 0.0 MB |
| 1 MB | 48 ms | 11 ms | 1.4 MB |
| 5 MB | 224 ms | 37 ms | 6.7 MB |
| 10 MB | 413 ms | 72 ms | 13.5 MB |
| 25 MB | 1090 ms | 224 ms | 33.7 MB |

413 ms to save a vault holding 10 MB of scans. That is not a reason to lose
somebody's passport.

The error was not the analysis; each cost listed was real. It was the weighting.
A long stretch of work had just been spent fixing write amplification — the
bulk delete, the import — and that pattern was matched onto a case where the
requirement was *not losing data*, which outranks it. The measurement was cheap
and should have come first.

## What was built

A file is an ordinary **item** on the same keyring as the password it belongs
to, with `kind` set to `blob`, its bytes base64 in a `secret:data` field, and
its name, type and size alongside. The password gains one field per file,
`file:<id>`, holding the name to show.

Everything follows from it being an item:

- encrypted by the same envelope, merged by the same CRDT, tombstoned by the
  same delete, scrubbed by the same purge;
- carried into a shared keyring's document by the same extraction, so **sharing
  a keyring shares its files for nothing** — the part a separate blob store
  would have had to re-earn and would have got wrong somewhere;
- moved out of a shared keyring by the same relocation, so taking a password
  back takes its scan with it.

One field per file rather than a list in one field, so attaching and removing
are per-field writes and two devices doing both at once merge rather than
overwrite.

**Ids are content hashes.** The same document attached to three passwords costs
one copy; two devices attaching the same file converge instead of duplicating;
a re-import finds the copy it made last time. Both platforms compute the id
identically, pinned by a frozen fixture — otherwise importing the same file in
a browser and on a phone would put two copies in one vault.

**Deleting takes the bytes.** One rule shared by deleting a password, several,
or a whole keyring, and a file still referenced by a password that is not being
deleted survives — which content-addressing makes answerable.

**10 MB per file** is the ceiling, because the vault is sealed and uploaded
whole and the largest thing in it is paid for on every later change. Past it,
the import names the file and its size so its owner keeps the original.

**SVG is not rendered.** It is a document that can carry script and fetch
remote content, and displaying one from a vault would run somebody else's
markup inside the page holding every password. It downloads like anything else.

## What is left

**Upload granularity.** Editing a password in a vault with 10 MB attached
re-uploads 13.5 MB, because a document is published whole. This is a real cost
and a pre-existing property — it is simply proportional to attachments now
rather than negligible. The fix is publishing a delta or splitting large blobs
into their own documents, both of which are optimisations that can be measured
against a vault people actually have. It is not a correctness problem and was
never a reason to refuse the feature.

**Thumbnails decode the whole image.** A 10 MB photo is decoded to draw a 42 px
preview. Fine for a handful, wasteful for a keyring full of scans.
