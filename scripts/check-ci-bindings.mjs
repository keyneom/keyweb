/**
 * Guards the lockfile entries CI cannot install without.
 *
 * `npm install` records only the optional native binaries for the machine that
 * ran it. This lockfile is written on macOS, and for a long time it carried
 * `@rolldown/binding-darwin-arm64` and nothing else — so `npm ci` on a Linux
 * runner installed no rolldown binary at all. Vitest died on every push with
 * `Cannot find module './rolldown-binding.wasi.cjs'`, which took the Pages
 * deploy down with it: the site served to users silently stopped updating for
 * weeks while everything looked fine on a developer's machine.
 *
 * Regenerating the lockfile against the registry, rather than against an
 * already-installed tree, records every platform's binary and fixes it. But
 * nothing makes that stick — the next person to run a routine `npm install`
 * that rewrites the lockfile from node_modules can prune them all again, and
 * the symptom appears only in CI, only at the point where it takes the deploy
 * down. So this asserts the Linux entries are still there, and fails fast and
 * loudly with the fix rather than failing later and cryptically.
 */
import { readFileSync } from "node:fs";

/**
 * The platform CI runs on, and the packages that ship a native binary per
 * platform. Both broke the same way; lightningcss simply had not been reached
 * yet, because rolldown failed first.
 */
const REQUIRED = ["@rolldown/binding-linux-x64-gnu", "lightningcss-linux-x64-gnu"];

const lock = JSON.parse(readFileSync(new URL("../package-lock.json", import.meta.url), "utf8"));

const missing = REQUIRED.filter((name) => !lock.packages?.[`node_modules/${name}`]);

if (missing.length > 0) {
  console.error(
    `package-lock.json is missing native binaries that Linux needs:\n` +
      missing.map((name) => `  - ${name}`).join("\n") +
      `\n\nnpm ci will install no binary for these and the build will fail here and in the\n` +
      `Pages deploy. This happens when the lockfile is regenerated from an installed\n` +
      `node_modules on macOS, which prunes every other platform's binaries.\n\n` +
      `To fix, regenerate against the registry instead of the local tree:\n` +
      `  rm -rf /tmp/lockgen && mkdir /tmp/lockgen\n` +
      `  cp package.json /tmp/lockgen/ && cp -R --parents packages/*/package.json /tmp/lockgen/\n` +
      `  (cd /tmp/lockgen && npm install --package-lock-only --prefer-online)\n` +
      `  cp /tmp/lockgen/package-lock.json .\n`,
  );
  process.exit(1);
}

console.log(`Linux native binaries present in the lockfile: ${REQUIRED.join(", ")}`);
