import { describe, expect, it } from "vitest";
import { SharedKeyringRemote, type SharingController } from "../src/vault/sharing/controller";
import { emptyVault, RemoteUnavailableError } from "@keyweb/vault-core";

/**
 * A keyring shared from one of your own devices, opened on another.
 *
 * The pin that says whose signatures to trust on a shared file is per device
 * and is established by adopting the dataset. The device that *created* the
 * keyring has one; every other device you own has never seen that dataset
 * before, and meets it as a binding in the vault with nothing in its registry.
 *
 * sync-kit says so with `state` and "no pinned owner key", which is a
 * different code from the `not-found` this already handled — so the ordinary
 * state of a second device came out of the sync as a backup problem with a
 * sentence about verified invitations, on a keyring the person had shared
 * themselves and could not open.
 */
class FakeSyncKitError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function controller(loadFails: Error): {
  controller: SharingController;
  adopted: string[];
} {
  const adopted: string[] = [];
  const fake = {
    async loadDataset() {
      throw loadFails;
    },
    async adoptDataset(datasetId: string) {
      adopted.push(datasetId);
      return { value: emptyVault(), revisionId: "rev-1" };
    },
  };
  return { controller: fake as unknown as SharingController, adopted };
}

describe("a shared keyring this device has never pinned", () => {
  it("adopts it, rather than reporting a backup problem", async () => {
    const { controller: fake, adopted } = controller(
      new FakeSyncKitError(
        "state",
        "Dataset keyweb-a51b7775 has no pinned owner key. Open it from a verified invitation first.",
      ),
    );

    const remote = new SharedKeyringRemote(fake, "keyweb-a51b7775");
    const revision = await remote.read();

    expect(adopted).toEqual(["keyweb-a51b7775"]);
    expect(revision).not.toBeNull();
  });

  it("still adopts one whose file has not been seen either", async () => {
    const { controller: fake, adopted } = controller(
      new FakeSyncKitError("not-found", "Dataset keyweb-b2 was not found."),
    );

    const remote = new SharedKeyringRemote(fake, "keyweb-b2");
    expect(await remote.read()).not.toBeNull();
    expect(adopted).toEqual(["keyweb-b2"]);
  });

  /**
   * And the other `state` errors still surface. Adopting past every one of
   * them would turn "this file is wrong" into "read it anyway", which is the
   * opposite of what the pin is for.
   */
  it("does not adopt past a state error that means something else", async () => {
    const { controller: fake, adopted } = controller(
      new FakeSyncKitError("state", "Dataset keyweb-c3 has a forked revision history."),
    );

    const remote = new SharedKeyringRemote(fake, "keyweb-c3");
    await expect(remote.read()).rejects.toThrow(RemoteUnavailableError);
    expect(adopted).toEqual([]);
  });
});
