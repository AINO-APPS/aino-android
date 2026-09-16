#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const SELF = "scripts/check-source-provenance.mjs";
const POLICY = "docs/SOURCE_PROVENANCE.md";
const forbidden = [
  [/org\.thoughtcrime\.securesms/i, "Signal package identifier"],
  [/org\.signal\.ringrtc|\bringrtc\b/i, "Signal RingRTC identifier"],
  [/signalapp\/Signal-Android/i, "Signal repository reference outside the provenance policy"],
  [/SPDX-License-Identifier:\s*AGPL-3\.0/i, "copied AGPL source marker"],
];

const files = execFileSync("git", ["ls-files", "--cached", "--others", "--exclude-standard"], { encoding: "utf8" })
  .split("\n").map((line) => line.trim()).filter(Boolean)
  .filter((file) => file !== SELF && file !== POLICY);
const violations = [];

for (const file of files) {
  let lines;
  try { lines = readFileSync(file, "utf8").split(/\r?\n/); } catch { continue; }
  for (const [pattern, description] of forbidden) {
    lines.forEach((line, index) => {
      if (pattern.test(line)) violations.push(`${file}:${index + 1}: ${description}`);
    });
  }
}

if (violations.length) {
  console.error("Source-provenance guard FAILED:\n");
  violations.forEach((violation) => console.error(`  ${violation}`));
  process.exit(1);
}

console.log(`Source-provenance guard passed (${forbidden.length} rules).`);