#!/usr/bin/env node
/**
 * Repository independence guardrail.
 *
 * `aino-android` must never regain a dependency on the retired legacy
 * repository, the retired Expo release channel, or the placeholder endpoints the
 * skeleton originally shipped with. Each of those has already caused a real
 * defect elsewhere in this migration, so they are enforced mechanically rather
 * than by review.
 *
 * Runs over tracked files only, so build outputs and local scratch files cannot
 * trip it.
 */
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const RULES = [
  {
    id: "legacy-repository",
    pattern: /vvronline\/WorkPulse/i,
    reason:
      "The legacy repository is retired. Releases and update feeds must resolve to AINO-APPS/aino-android.",
  },
  {
    id: "legacy-product-name",
    pattern: /workpulse/i,
    reason:
      "The product is AINO. `workpulse` identifiers were renamed in A-002; reintroducing one would fork storage keys and BuildConfig fields.",
  },
  {
    id: "placeholder-endpoint",
    pattern: /example\.invalid/i,
    reason:
      "BuildConfig must point at a real backend. The non-routable placeholder produced builds that silently could not reach the API.",
  },
  {
    id: "expo-release-channel",
    // Matches the retired Expo channel prefix, e.g. `mobile/latest.json` or
    // `s3://bucket/mobile/releases/...`, without matching ordinary words.
    pattern: /["'/]mobile\/(latest\.json|releases\/)/i,
    reason:
      "The `mobile/` R2 prefix belongs to the retired Expo channel (DECISIONS.md #13). The native channel is `android/`.",
  },
];

const SELF = "scripts/check-independence.mjs";

function trackedFiles() {
  return execFileSync("git", ["ls-files"], { encoding: "utf8" })
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((file) => file !== SELF);
}

const violations = [];

for (const file of trackedFiles()) {
  let contents;
  try {
    contents = readFileSync(file, "utf8");
  } catch {
    continue; // Binary or unreadable: nothing to match.
  }

  const lines = contents.split(/\r?\n/);
  for (const rule of RULES) {
    lines.forEach((line, index) => {
      if (rule.pattern.test(line)) {
        violations.push({
          file,
          line: index + 1,
          rule,
          text: line.trim().slice(0, 160),
        });
      }
    });
  }
}

if (violations.length === 0) {
  console.log(`Independence guardrail passed (${RULES.length} rules).`);
  process.exit(0);
}

console.error(`Independence guardrail FAILED with ${violations.length} violation(s):\n`);
const byRule = new Map();
for (const violation of violations) {
  const list = byRule.get(violation.rule.id) ?? [];
  list.push(violation);
  byRule.set(violation.rule.id, list);
}

for (const [ruleId, list] of byRule) {
  console.error(`[${ruleId}] ${list[0].rule.reason}`);
  for (const violation of list) {
    console.error(`  ${violation.file}:${violation.line}: ${violation.text}`);
  }
  console.error("");
}

process.exit(1);
