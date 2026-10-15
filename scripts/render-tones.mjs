#!/usr/bin/env node
// Renders the web/desktop synthesised tones (aino-platform client/src/utils/sounds.ts):
// RINGTONES, OUTGOING_TONES, MESSAGE_TONES, MENTION_TONES and REACTION_TONES,
// to 44.1 kHz mono 16-bit WAVs in app/src/main/res/raw, so Android plays the same
// sounds the user picks on desktop. Usage: node scripts/render-tones.mjs
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const RATE = 44100;
const OUT = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "res", "raw");

/** Minimal offline stand-in for the Web Audio nodes the synths use. */
class Param {
    constructor(value) { this.events = [{ type: "set", value, time: 0 }]; }
    setValueAtTime(value, time) { this.events.push({ type: "set", value, time }); }
    exponentialRampToValueAtTime(value, time) { this.events.push({ type: "exp", value, time }); }
    at(t) {
        let prev = this.events[0];
        for (const e of this.events) {
            if (e.time <= t) { prev = e; continue; }
            if (e.type === "exp") {
                const from = Math.max(prev.value, 1e-6);
                const to = Math.max(e.value, 1e-6);
                const k = (t - prev.time) / (e.time - prev.time);
                return from * Math.pow(to / from, k);
            }
            break;
        }
        return prev.value;
    }
}

class Ctx {
    constructor() { this.currentTime = 0; this.destination = {}; this.voices = []; }
    createGain() { return { gain: new Param(1), connect() {} }; }
    createOscillator() {
        const voice = { type: "sine", frequency: new Param(440), start: 0, stop: Infinity, gains: [] };
        this.voices.push(voice);
        return {
            set type(value) { voice.type = value; },
            frequency: voice.frequency,
            connect(gain) { voice.gains.push(gain.gain); },
            start(t) { voice.start = t; },
            stop(t) { voice.stop = t; },
        };
    }
}

const wave = {
    sine: (p) => Math.sin(2 * Math.PI * p),
    square: (p) => (p % 1 < 0.5 ? 1 : -1),
    sawtooth: (p) => 2 * (p % 1) - 1,
    triangle: (p) => 1 - 4 * Math.abs((p % 1) - 0.5),
};

function render(synth, loopGapMs) {
    const ctx = new Ctx();
    const durationMs = synth(ctx, 1);
    const tail = Math.max(...ctx.voices.map((v) => v.stop));
    const seconds = loopGapMs == null ? tail + 0.02 : (durationMs + loopGapMs) / 1000;
    const samples = new Float32Array(Math.ceil(seconds * RATE));
    for (const v of ctx.voices) {
        let phase = 0;
        for (let i = Math.floor(v.start * RATE); i < samples.length && i / RATE < v.stop; i++) {
            const t = i / RATE;
            phase += v.frequency.at(t) / RATE;
            const gain = v.gains.reduce((g, p) => g * p.at(t), 1);
            samples[i] += wave[v.type](phase) * gain;
        }
    }
    const peak = samples.reduce((m, s) => Math.max(m, Math.abs(s)), 0) || 1;
    const scale = 0.89 / peak;
    const data = Buffer.alloc(samples.length * 2);
    samples.forEach((s, i) => data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, s * scale)) * 32767), i * 2));
    const header = Buffer.alloc(44);
    header.write("RIFF", 0); header.writeUInt32LE(36 + data.length, 4); header.write("WAVE", 8);
    header.write("fmt ", 12); header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
    header.writeUInt32LE(RATE, 24); header.writeUInt32LE(RATE * 2, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
    header.write("data", 36); header.writeUInt32LE(data.length, 40);
    return Buffer.concat([header, data]);
}

/* ─── Ports of sounds.ts (keep in sync) ─────────────────────────────── */

function tone(ctx, freq, startOffset, duration, volume, type = "sine") {
    const t0 = ctx.currentTime + startOffset;
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(Math.max(0.0001, volume), t0 + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    const osc = ctx.createOscillator();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, t0);
    osc.connect(gain);
    osc.start(t0);
    osc.stop(t0 + duration + 0.05);
}

function pluck(ctx, freq, startOffset, duration, volume) {
    const t0 = ctx.currentTime + startOffset;
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(volume, t0);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    const osc = ctx.createOscillator();
    osc.type = "triangle";
    osc.frequency.setValueAtTime(freq, t0);
    osc.connect(gain);
    osc.start(t0);
    osc.stop(t0 + duration + 0.05);
    const osc2 = ctx.createOscillator();
    osc2.type = "sine";
    osc2.frequency.setValueAtTime(freq * 2, t0);
    const g2 = ctx.createGain();
    g2.gain.setValueAtTime(volume * 0.4, t0);
    g2.gain.exponentialRampToValueAtTime(0.0001, t0 + duration * 0.6);
    osc2.connect(g2);
    osc2.start(t0);
    osc2.stop(t0 + duration + 0.05);
}

const RINGTONES = {
    classic(ctx, vol) { tone(ctx, 440, 0, 0.4, vol * 0.6); tone(ctx, 440, 0.5, 0.4, vol * 0.6); return 2000; },
    calm(ctx, vol) { pluck(ctx, 392, 0, 1.2, vol * 0.5); pluck(ctx, 523, 0.15, 1.2, vol * 0.4); pluck(ctx, 659, 0.3, 1.4, vol * 0.4); return 2400; },
    dynamic(ctx, vol) { [523, 659, 784, 659].forEach((f, i) => tone(ctx, f, i * 0.18, 0.16, vol * 0.55, "square")); return 1800; },
    urgent(ctx, vol) { for (let i = 0; i < 3; i++) tone(ctx, 880, i * 0.18, 0.13, vol * 0.7, "sawtooth"); return 1600; },
    boop(ctx, vol) { tone(ctx, 330, 0, 0.25, vol * 0.65); tone(ctx, 247, 0.32, 0.3, vol * 0.65); return 2000; },
    marimba(ctx, vol) { [523, 659, 784, 1046].forEach((f, i) => pluck(ctx, f, i * 0.18, 0.45, vol * 0.5)); return 2200; },
    crystal(ctx, vol) { pluck(ctx, 659, 0, 0.9, vol * 0.48); pluck(ctx, 988, 0.1, 0.9, vol * 0.4); pluck(ctx, 1318, 0.2, 1, vol * 0.34); return 2200; },
    vapor(ctx, vol) { [392, 523, 659, 784].forEach((f, i) => tone(ctx, f, i * 0.22, 0.2, vol * 0.5, "triangle")); return 2000; },
};

const OUTGOING = {
    ringback(ctx, vol) { tone(ctx, 440, 0, 0.9, vol * 0.35); tone(ctx, 480, 0, 0.9, vol * 0.35); return 2000; },
    pulse(ctx, vol) { tone(ctx, 523, 0, 0.45, vol * 0.45); return 1500; },
    soft(ctx, vol) { pluck(ctx, 659, 0, 0.7, vol * 0.4); pluck(ctx, 523, 0.4, 0.7, vol * 0.4); return 2000; },
    echo(ctx, vol) { tone(ctx, 587, 0, 0.2, vol * 0.45, "triangle"); tone(ctx, 587, 0.24, 0.18, vol * 0.3, "triangle"); return 1500; },
    drift(ctx, vol) { pluck(ctx, 740, 0, 0.5, vol * 0.4); tone(ctx, 622, 0.3, 0.35, vol * 0.28); return 1700; },
};

function popTone(ctx, vol) {
    const t0 = ctx.currentTime;
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(vol, t0);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.12);
    const osc = ctx.createOscillator();
    osc.type = "sine";
    osc.frequency.setValueAtTime(180, t0);
    osc.frequency.exponentialRampToValueAtTime(80, t0 + 0.12);
    osc.connect(gain);
    osc.start(t0);
    osc.stop(t0 + 0.15);
    return 200;
}
function chimeTone(ctx, vol) {
    pluck(ctx, 784, 0, 0.4, vol * 0.55); pluck(ctx, 988, 0.08, 0.4, vol * 0.55); pluck(ctx, 1175, 0.16, 0.5, vol * 0.55);
    return 700;
}
function subtleTone(ctx, vol) { tone(ctx, 1046, 0, 0.15, vol * 0.55); return 200; }

const MESSAGE = {
    ding(ctx, vol) { tone(ctx, 880, 0, 0.12, vol); tone(ctx, 1175, 0.12, 0.22, vol); return 400; },
    pop: popTone,
    chime: chimeTone,
    knock(ctx, vol) {
        for (let i = 0; i < 2; i++) {
            const t0 = ctx.currentTime + i * 0.12;
            const gain = ctx.createGain();
            gain.gain.setValueAtTime(vol * 0.8, t0);
            gain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.06);
            const osc = ctx.createOscillator();
            osc.type = "square";
            osc.frequency.setValueAtTime(220, t0);
            osc.connect(gain);
            osc.start(t0);
            osc.stop(t0 + 0.08);
        }
        return 350;
    },
    subtle: subtleTone,
    glassy(ctx, vol) { pluck(ctx, 1046, 0, 0.25, vol * 0.55); pluck(ctx, 1318, 0.06, 0.22, vol * 0.42); return 320; },
    ripple(ctx, vol) {
        tone(ctx, 660, 0, 0.1, vol * 0.65, "triangle"); tone(ctx, 880, 0.08, 0.14, vol * 0.5, "triangle"); tone(ctx, 1175, 0.18, 0.12, vol * 0.4, "triangle");
        return 380;
    },
};

const MENTION = {
    mention(ctx, vol) { [784, 988, 1175].forEach((f, i) => tone(ctx, f, i * 0.1, 0.15, vol)); return 500; },
    chime: chimeTone,
    urgent(ctx, vol) { tone(ctx, 1175, 0, 0.12, vol, "sawtooth"); tone(ctx, 1480, 0.16, 0.16, vol, "sawtooth"); return 400; },
    spark(ctx, vol) { pluck(ctx, 1175, 0, 0.2, vol * 0.65); pluck(ctx, 1568, 0.08, 0.18, vol * 0.45); return 320; },
};

const REACTION = {
    subtle: subtleTone,
    pop: popTone,
    click(ctx, vol) { tone(ctx, 1200, 0, 0.05, vol * 0.6, "square"); return 120; },
};

mkdirSync(OUT, { recursive: true });
const write = (name, buffer) => { writeFileSync(join(OUT, `${name}.wav`), buffer); console.log(`${name}.wav ${buffer.length} bytes`); };
// Ringing tones loop on Android, so they keep the desktop's re-fire gap (duration + 100 ms).
for (const [id, synth] of Object.entries(RINGTONES)) write(`ringtone_${id}`, render(synth, 100));
for (const [id, synth] of Object.entries(OUTGOING)) write(`tone_outgoing_${id}`, render(synth, 100));
for (const [id, synth] of Object.entries(MESSAGE)) write(`tone_message_${id}`, render(synth));
for (const [id, synth] of Object.entries(MENTION)) write(`tone_mention_${id}`, render(synth));
for (const [id, synth] of Object.entries(REACTION)) write(`tone_reaction_${id}`, render(synth));
