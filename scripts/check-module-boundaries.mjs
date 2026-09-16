#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const FEATURE_ROOT = "app/src/main/java/app/aino/mobile/feature/";
const files = execFileSync("git", ["ls-files", "--cached", "--others", "--exclude-standard", `${FEATURE_ROOT}*.kt`, `${FEATURE_ROOT}**/*.kt`], {
  encoding: "utf8",
}).split("\n").map((line) => line.trim()).filter(Boolean);

const violations = [];
for (const file of files) {
  const owner = file.slice(FEATURE_ROOT.length).split("/")[0];
  for (const [index, line] of readFileSync(file, "utf8").split(/\r?\n/).entries()) {
    const match = /^import app\.aino\.mobile\.feature\.([^.]+)/.exec(line.trim());
    if (match && match[1] !== owner) {
      violations.push(`${file}:${index + 1}: feature '${owner}' imports feature '${match[1]}'`);
    }
  }
}

if (violations.length) {
  console.error("Module-boundary guard FAILED. Features may depend on core, never on sibling features:\n");
  violations.forEach((violation) => console.error(`  ${violation}`));
  process.exit(1);
}

console.log(`Module-boundary guard passed (${files.length} feature source files).`);