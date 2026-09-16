#!/usr/bin/env node
/**
 * Realtime parity guard (A-100).
 *
 * The platform declares `WSType = string` (server/realtime/types.ts), so there
 * is no server-side enum to import. The authoritative realtime surface is
 * therefore every emitter call site:
 *
 *   sendToUser(tenantId, userId, "<type>", data)   — per-user delivery
 *   broadcast(tenantId, "<type>", data)            — tenant-wide delivery
 *
 * This script re-derives that set from a local `aino-platform` checkout and
 * diffs it against the Kotlin `RealtimeEventRegistry`. Any event the server can
 * emit that Android does not route fails the build. That is what stops the two
 * clients drifting apart as the platform evolves.
 *
 * Without a platform checkout it validates the committed snapshot instead of
 * silently passing, so CI on a runner that only clones the Android repository
 * still catches a registry that disagrees with the recorded surface.
 *
 * Usage:
 *   node scripts/check-realtime-parity.mjs [pathToAinoPlatform]
 *   AINO_PLATFORM_REPO=../aino-platform node scripts/check-realtime-parity.mjs
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const registryPath = path.join(
  repoRoot,
  "app/src/main/java/app/aino/mobile/core/realtime/RealtimeEventRegistry.kt",
);
const snapshotPath = path.join(repoRoot, "contracts/realtime-events.json");
const platformRepo = path.resolve(
  process.argv[2] || process.env.AINO_PLATFORM_REPO || "../aino-platform",
);

/** Recursively collect server TypeScript sources, excluding build output. */
function collectSources(directory, found = []) {
  let entries;
  try {
    entries = fs.readdirSync(directory, { withFileTypes: true });
  } catch {
    return found;
  }
  for (const entry of entries) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (/^(node_modules|dist|build|coverage|__tests__)$/.test(entry.name)) continue;
      collectSources(full, found);
    } else if (/\.ts$/.test(entry.name) && !/\.d\.ts$/.test(entry.name)) {
      found.push(full);
    }
  }
  return found;
}

/**
 * Extract emitted event types. Both quote styles are accepted because the
 * server uses both. Template literals are reported separately: a computed
 * event type cannot be verified statically and must be resolved by hand
 * rather than silently ignored.
 */
function extractServerEvents(files) {
  const events = new Set();
  const dynamic = [];
  const patterns = [
    /sendToUser\(\s*[^,]+,\s*[^,]+,\s*(["'])([a-z_]+)\1/g,
    /(?<![.\w])broadcast\(\s*[^,]+,\s*(["'])([a-z_]+)\1/g,
  ];
  const dynamicEmit = /(?:sendToUser|broadcast)\([^)]*?,\s*`[^`]*\$\{/g;

  for (const file of files) {
    const source = fs.readFileSync(file, "utf8");
    const relative = path.relative(platformRepo, file).replace(/\\/g, "/");
    for (const pattern of patterns) {
      for (const match of source.matchAll(pattern)) events.add(match[2]);
    }
    for (const _unused of source.matchAll(dynamicEmit)) dynamic.push(relative);
  }
  return { events, dynamic: [...new Set(dynamic)] };
}

/** Read the event identifiers the Kotlin registry claims to route. */
function extractAndroidEvents() {
  if (!fs.existsSync(registryPath)) {
    throw new Error(
      `RealtimeEventRegistry.kt not found at ${registryPath}.\n` +
        "A-100 requires the registry to exist before this guard can pass.",
    );
  }
  const source = fs.readFileSync(registryPath, "utf8");

  // Scope extraction to the RealtimeEvent enum body. The same file also
  // declares ChatSystemMessageType, whose entries look syntactically identical
  // but describe in-band `chat_message` metadata subtypes rather than
  // WebSocket event types. Parsing the whole file would conflate the two
  // contracts and report false "dead listener" failures.
  const start = source.indexOf("enum class RealtimeEvent(");
  if (start === -1) {
    throw new Error(`Could not locate 'enum class RealtimeEvent(' in ${registryPath}.`);
  }
  // The enum's entry list ends at the first line containing only `;`.
  const terminator = source.slice(start).search(/^\s*;\s*$/m);
  if (terminator === -1) {
    throw new Error(
      `Could not find the ';' terminating the RealtimeEvent entry list in ${registryPath}.`,
    );
  }
  const body = source.slice(start, start + terminator);

  const routed = new Set();
  // Matches enum entries of the form: Identifier("chat_message", ...) including
  // multi-line entries where the literal sits on the following line.
  for (const match of body.matchAll(/^\s{4}[A-Za-z0-9_]+\(\s*\n?\s*"([a-z_]+)"/gm)) {
    routed.add(match[1]);
  }
  return routed;
}

function fail(message) {
  console.error(`\nrealtime-parity: FAIL\n${message}\n`);
  process.exit(1);
}

const androidEvents = extractAndroidEvents();
const snapshot = fs.existsSync(snapshotPath)
  ? JSON.parse(fs.readFileSync(snapshotPath, "utf8"))
  : null;
const hasPlatform = fs.existsSync(path.join(platformRepo, "server"));

let serverEvents;
let dynamic = [];
let sourceLabel;

if (hasPlatform) {
  const sources = collectSources(path.join(platformRepo, "server"));
  const extracted = extractServerEvents(sources);
  serverEvents = extracted.events;
  dynamic = extracted.dynamic;
  sourceLabel = `${sources.length} server sources in ${platformRepo}`;

  // Keep the committed snapshot honest so offline runs stay meaningful.
  const sorted = [...serverEvents].sort();
  if (!snapshot || JSON.stringify(snapshot.events) !== JSON.stringify(sorted)) {
    const recorded = {
      schemaVersion: 1,
      note:
        "Derived from sendToUser/broadcast call sites. WSType is `string`, so " +
        "call sites are the authoritative realtime surface.",
      generatedFrom: { repository: "AINO-APPS/aino-platform", path: "server/**/*.ts" },
      totals: { events: sorted.length },
      events: sorted,
    };
    fs.mkdirSync(path.dirname(snapshotPath), { recursive: true });
    fs.writeFileSync(snapshotPath, `${JSON.stringify(recorded, null, 2)}\n`);
    console.log(`realtime-parity: refreshed ${path.relative(repoRoot, snapshotPath)}`);
  }
} else if (snapshot) {
  serverEvents = new Set(snapshot.events);
  sourceLabel = `committed snapshot (${path.relative(repoRoot, snapshotPath)})`;
  console.log(
    `realtime-parity: ${platformRepo} not found; validating against the committed snapshot.`,
  );
} else {
  fail(
    `No platform checkout at ${platformRepo} and no snapshot at ${snapshotPath}.\n` +
      "Pass the platform path or commit contracts/realtime-events.json.",
  );
}

const missing = [...serverEvents].filter((type) => !androidEvents.has(type)).sort();
const unknown = [...androidEvents].filter((type) => !serverEvents.has(type)).sort();

console.log(`realtime-parity: source   ${sourceLabel}`);
console.log(`realtime-parity: server   ${serverEvents.size} event types`);
console.log(`realtime-parity: android  ${androidEvents.size} routed types`);

if (dynamic.length) {
  console.log(
    `realtime-parity: note — computed event types in ${dynamic.length} file(s): ` +
      dynamic.join(", "),
  );
}

const problems = [];
if (missing.length) {
  problems.push(
    `${missing.length} server event(s) are NOT routed by Android:\n` +
      missing.map((type) => `  - ${type}`).join("\n") +
      "\n\nAdd each to RealtimeEventRegistry. Per the Stage 11 mandate no event" +
      "\nmay be silently dropped.",
  );
}
if (unknown.length) {
  problems.push(
    `${unknown.length} Android listener(s) reference events the server never emits:\n` +
      unknown.map((type) => `  - ${type}`).join("\n") +
      "\n\nThese are dead listeners. Remove them or correct the identifier.",
  );
}
if (problems.length) fail(problems.join("\n\n"));

console.log("realtime-parity: OK — every server event is routed, no dead listeners.\n");
