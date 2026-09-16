import {
  buildSharingJoinLinkV1,
  buildSharingResponseLinkV1,
  parseSharingJoinLinkV1,
  parseSharingResponseLinkV1,
  type SharingDatasetFileV1,
  type SharingInvitationV1,
  type SharingPublicKeyResponseV1,
} from "@keyneom/sync-kit/sharing";

/**
 * The two links a share travels on.
 *
 * There is no Keyweb server, so there is nowhere to put a pending invitation.
 * The exchange therefore rides in the links themselves: the owner sends one,
 * the recipient sends one back, and the handshake is finished. People pass them
 * over whatever they already use to talk to each other.
 *
 * Neither link is a secret worth much on its own. The invitation carries the
 * owner's *public* key and a signature; the response carries the recipient's
 * *public* key. Someone who intercepts both still cannot read a keyring,
 * because the content key is only ever wrapped to a public key whose private
 * half never leaves its owner's device. What they could do is substitute their
 * own key for the recipient's — which is what the read-aloud fingerprint on
 * the accept screen is for.
 *
 * `sk-*` parameter names come from sync-kit rather than Keyweb, deliberately:
 * they are the protocol's, and inventing local spellings would mean two
 * parsers to keep in agreement.
 */

/** Extra parameters Keyweb adds for the screen the link lands on. */
const OWNER_PARAM = "owner";
const LABEL_PARAM = "kw-name";

export type KeywebJoinLink = {
  invitation: SharingInvitationV1;
  files: SharingDatasetFileV1[];
  /** Who sent it, for the preview. Not trusted for anything cryptographic. */
  ownerEmail: string | null;
  /** What the keyring is called, so the preview can name it before joining. */
  label: string | null;
};

export type KeywebResponseLink = { response: SharingPublicKeyResponseV1 };

/** Where links land: this page, with no query of its own. */
export function landingUrl(): string {
  if (typeof window === "undefined") return "https://keyneom.github.io/keyweb/";
  return `${window.location.origin}${window.location.pathname}`;
}

export function buildJoinLink(input: {
  invitation: SharingInvitationV1;
  files: SharingDatasetFileV1[];
  ownerEmail: string | null;
  label: string;
  landing?: string;
}): string {
  const base = buildSharingJoinLinkV1({
    landingUrl: input.landing ?? landingUrl(),
    invitation: input.invitation,
    files: input.files,
  });
  const extra = new URLSearchParams();
  // The name is a label only. It is never read as the keyring's real name:
  // that comes from the shared document, which is signed. A link claiming to
  // be "Household" must not be able to rename anything by saying so.
  extra.set(LABEL_PARAM, input.label);
  if (input.ownerEmail) extra.set(OWNER_PARAM, input.ownerEmail);
  return `${base}&${extra.toString()}`;
}

export function buildResponseLink(input: {
  response: SharingPublicKeyResponseV1;
  landing?: string;
}): string {
  return buildSharingResponseLinkV1({
    landingUrl: input.landing ?? landingUrl(),
    response: input.response,
  });
}

export function parseJoinLink(search: string | URLSearchParams): KeywebJoinLink | null {
  const params =
    typeof search === "string"
      ? new URLSearchParams(search.startsWith("?") ? search.slice(1) : search)
      : search;
  const parsed = parseSharingJoinLinkV1(params);
  if (!parsed) return null;
  return {
    invitation: parsed.invitation,
    files: parsed.files,
    ownerEmail: params.get(OWNER_PARAM),
    label: params.get(LABEL_PARAM),
  };
}

export function parseResponseLink(
  search: string | URLSearchParams,
): KeywebResponseLink | null {
  const params =
    typeof search === "string"
      ? new URLSearchParams(search.startsWith("?") ? search.slice(1) : search)
      : search;
  const parsed = parseSharingResponseLinkV1(params);
  return parsed ? { response: parsed.response } : null;
}

/**
 * Every parameter a share link uses, so the address bar can be cleared.
 *
 * Left in place, a reload would re-run the flow — and worse, the link would
 * sit in the browser history of a shared computer long after it was used.
 */
export const SHARE_LINK_PARAMS = [
  "sk-inv",
  "sk-files",
  "sk-resp",
  "sk-kr",
  "sync-kit-join",
  "sync-kit-exchange",
  "sync-kit-folder",
  OWNER_PARAM,
  LABEL_PARAM,
] as const;

export function stripShareLinkParams(url: URL): URL {
  const next = new URL(url.toString());
  for (const key of SHARE_LINK_PARAMS) next.searchParams.delete(key);
  return next;
}
