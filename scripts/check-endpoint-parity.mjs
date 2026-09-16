#!/usr/bin/env node
/** A-099: operation-level Android/platform HTTP parity ratchet. */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const platform = path.resolve(args.find(value => !value.startsWith("--")) || process.env.AINO_PLATFORM_REPO || "../aino-platform");
const update = args.includes("--update");
const inventoryPath = path.join(root, "contracts/http-route-inventory.json");
const coveragePath = path.join(root, "contracts/android-endpoint-coverage.json");
const matrixPath = path.join(root, "docs/PARITY_MATRIX.md");
const waiversPath = path.join(root, "docs/parity-waivers.json");
const sourceRoot = path.join(root, "app/src/main/java");

function files(directory, result = []) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) files(full, result);
    else if (entry.name.endsWith(".kt")) result.push(full);
  }
  return result;
}

function normalize(raw) {
  return (`/api/${raw}`)
    .replace(/\?.*$/, "")
    .replace(/\$\{[^}]+\}|\$[A-Za-z_][\w.]*/g, ":param")
    .replace(/\/+/g, "/")
    .replace(/\/$/, "") || "/";
}

function structural(value) {
  return value.replace(/:[^/]+/g, ":param");
}

function add(found, method, route, source) {
  if (!route || !/^[a-z][a-z0-9/_?${}.\-]+$/i.test(route)) return;
  // These two finite dynamic forms are declared through adjacent @api markers.
  // Do not also record their unresolved expression as an unmatched operation.
  if (/\$(action|suffix)\b/.test(route)) return;
  found.push({ method: method.toUpperCase(), path: normalize(route), source });
}

/** Return a balanced function call beginning at `(`, respecting strings. */
function balancedCall(source, open) {
  let depth = 0;
  let quote = null;
  let escaped = false;
  for (let index = open; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === quote) quote = null;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") quote = char;
    else if (char === "(") depth += 1;
    else if (char === ")" && --depth === 0) return source.slice(open, index + 1);
  }
  return null;
}

function scanAndroid() {
  const found = [];
  for (const file of files(sourceRoot)) {
    const source = fs.readFileSync(file, "utf8");
    const relative = path.relative(root, file).replace(/\\/g, "/");

    // Explicit proof marker for a dynamic route whose finite variants cannot
    // be recovered from a string template alone: `// @api POST chat/...`.
    for (const match of source.matchAll(/\/\/\s*@api\s+(GET|POST|PUT|PATCH|DELETE)\s+([^\s]+)/g)) {
      add(found, match[1], match[2], relative);
    }

    // Direct ApiRequest forms: positional and named. GET is the data-class default.
    for (const match of source.matchAll(/ApiRequest\(\s*"(GET|POST|PUT|PATCH|DELETE)"\s*,\s*"([^"\n]+)"/g)) {
      add(found, match[1], match[2], relative);
    }
    for (const match of source.matchAll(/ApiRequest\([\s\S]{0,180}?method\s*=\s*"(GET|POST|PUT|PATCH|DELETE)"[\s\S]{0,180}?path\s*=\s*"([^"\n]+)"/g)) {
      add(found, match[1], match[2], relative);
    }
    for (const match of source.matchAll(/ApiRequest\(\s*path\s*=\s*"([^"\n]+)"/g)) {
      add(found, "GET", match[1], relative);
    }

    // Repository wrappers with literal operation call sites.
    for (const match of source.matchAll(/jsonRequest\(\s*"(GET|POST|PUT|PATCH|DELETE)"\s*,\s*"([^"\n]+)"/g)) {
      add(found, match[1], match[2], relative);
    }
    for (const match of source.matchAll(/\bmutate(?:<[^>]+>)?\s*\(/g)) {
      const call = balancedCall(source, match.index + match[0].lastIndexOf("("));
      if (!call) continue;
      const route = call.match(/^\(\s*"([^"\n]+)"/)?.[1];
      if (!route) continue; // helper declaration, not a literal call site
      const explicit = [...call.matchAll(/"(GET|POST|PUT|PATCH|DELETE)"/g)].at(-1)?.[1];
      add(found, explicit || "POST", route, relative);
    }
  }
  const unique = new Map();
  for (const item of found) unique.set(`${item.method} ${structural(item.path)}`, item);
  return [...unique.values()].sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
}

if (update) {
  const platformInventory = path.join(platform, "contracts/http-route-inventory.json");
  if (!fs.existsSync(platformInventory)) throw new Error(`Platform inventory not found: ${platformInventory}`);
  fs.mkdirSync(path.dirname(inventoryPath), { recursive: true });
  fs.copyFileSync(platformInventory, inventoryPath);
}
if (!fs.existsSync(inventoryPath)) throw new Error(`Missing ${inventoryPath}; run with --update and a platform checkout.`);

const inventory = JSON.parse(fs.readFileSync(inventoryPath, "utf8"));
const waivers = fs.existsSync(waiversPath)
  ? JSON.parse(fs.readFileSync(waiversPath, "utf8")).waivers
  : [];
if (!Array.isArray(waivers) || waivers.some(item => !item.key || !item.owner || !item.reason)) {
  throw new Error("Every parity waiver must have non-empty key, owner and reason fields.");
}
const android = scanAndroid();
const platformKeys = new Map(inventory.endpoints.map(item => [`${item.method} ${structural(item.path)}`, item]));
const covered = android.filter(item => platformKeys.has(`${item.method} ${structural(item.path)}`));
const unmatched = android.filter(item => !platformKeys.has(`${item.method} ${structural(item.path)}`));
const coveredKeys = new Set(covered.map(item => `${item.method} ${structural(item.path)}`));
const waiverKeys = new Set(waivers.map(item => item.key));
const missing = inventory.endpoints.filter(item => {
  const key = `${item.method} ${structural(item.path)}`;
  return !coveredKeys.has(key) && !waiverKeys.has(key);
});

const snapshot = {
  schemaVersion: 1,
  generatedFrom: { platform: "contracts/http-route-inventory.json", android: "app/src/main/java/**/*.kt" },
  totals: { platform: inventory.endpoints.length, covered: covered.length, waived: waivers.length, missing: missing.length, unmatched: unmatched.length },
  covered,
  unmatched,
};

function owner(endpoint) {
  const area = endpoint.path.split("/")[2] || "root";
  const map = { chat: "A-103", tasks: "A-104", agile: "A-104", sprints: "A-104", projects: "A-104", "service-desk": "A-104", notifications: "A-105", calendar: "A-105", notes: "A-105", search: "A-105", org: "A-105", manager: "A-106", meetings: "A-107", admin: "A-108/A-110", compensation: "A-108/A-110", tenants: "A-108/A-110" };
  return map[area] || "existing/A-108";
}

const lines = [
  "# Android HTTP Parity Matrix", "",
  "> Generated by `scripts/check-endpoint-parity.mjs`; do not hand-edit.", "",
  `**Coverage: ${covered.length} / ${inventory.endpoints.length} operations (${(covered.length * 100 / inventory.endpoints.length).toFixed(1)}%)**`, "",
  "| Method | Path | Status | Owner |", "|---|---|---|---|",
  ...inventory.endpoints.map(endpoint => {
    const key = `${endpoint.method} ${structural(endpoint.path)}`;
    const status = coveredKeys.has(key) ? "implemented" : waiverKeys.has(key) ? "waived" : "pending";
    return `| ${endpoint.method} | \`${endpoint.path}\` | ${status} | ${owner(endpoint)} |`;
  }), "",
];

const serialized = `${JSON.stringify(snapshot, null, 2)}\n`;
const markdown = `${lines.join("\n")}\n`;
if (update) {
  fs.writeFileSync(coveragePath, serialized);
  fs.writeFileSync(matrixPath, markdown);
  console.log(`endpoint-parity: updated ${covered.length}/${inventory.endpoints.length} operations`);
} else {
  if (!fs.existsSync(coveragePath) || fs.readFileSync(coveragePath, "utf8") !== serialized) {
    console.error("endpoint-parity: FAIL — coverage snapshot is stale; run with --update and review the diff.");
    process.exit(1);
  }
  if (!fs.existsSync(matrixPath) || fs.readFileSync(matrixPath, "utf8") !== markdown) {
    console.error("endpoint-parity: FAIL — PARITY_MATRIX.md is stale; run with --update.");
    process.exit(1);
  }
  console.log(`endpoint-parity: OK — ${covered.length}/${inventory.endpoints.length} proven operations, ${waivers.length} waived, ${missing.length} pending, ${unmatched.length} unmatched.`);
}