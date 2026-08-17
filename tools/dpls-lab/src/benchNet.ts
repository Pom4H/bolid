import { tmpdir } from "node:os";
import { join } from "node:path";
import { gpioBit } from "./pins";

export type Rail = "GND" | "3V3" | "5V" | "12V";

export const RAIL_VOLTS: { readonly GND: 0; readonly "3V3": 3.3; readonly "5V": 5; readonly "12V": 12 } = {
  GND: 0,
  "3V3": 3.3,
  "5V": 5,
  "12V": 12,
};

export const SCENE_NAMES = ["bare", "line", "vcap", "reset"] as const;
export type SceneName = (typeof SCENE_NAMES)[number];

export const PAD_SILK = [
  "P0",
  "P2",
  "P3",
  "P7",
  "P11",
  "P14",
  "P15",
  "P16",
  "P17",
  "P18",
  "P20",
  "P23",
  "P24",
  "P31",
  "P32",
  "P33",
  "P34",
] as const;

const PAD_LIST = PAD_SILK.join(" ");

export type BenchNet = {
  ties: Map<string, Rail>;
};

export type BenchReply = {
  ok: boolean;
  line: string;
};

export type BenchArgv =
  | { ok: true; lines: string[] }
  | { ok: false; line: string };

export function benchSockPath(): string {
  return process.env.DPLS_BENCH_SOCK ?? join(tmpdir(), "pb03f-kit.sock");
}

export function emptyNet(): BenchNet {
  return { ties: new Map() };
}

export function isRail(value: string): value is Rail {
  return value === "GND" || value === "3V3" || value === "5V" || value === "12V";
}

export function isSceneName(value: string): value is SceneName {
  return value === "bare" || value === "line" || value === "vcap" || value === "reset";
}

export function isPad(label: string): boolean {
  return gpioBit(label) !== null;
}

/** Silk `P20` from `p20` / `P20`. */
export function canonicalPad(raw: string): string | null {
  const match = raw.trim().match(/^p(\d+)$/i);
  if (match === null) return null;
  const label = `P${match[1]}`;
  return isPad(label) ? label : null;
}

/** Rail name from `12v` / `3.3` / `gnd`. */
export function canonicalRail(raw: string): Rail | null {
  const value = raw.trim().toLowerCase().replace(",", ".");
  if (value === "gnd" || value === "0" || value === "0v") return "GND";
  if (value === "3v3" || value === "3.3") return "3V3";
  if (value === "5v" || value === "5") return "5V";
  if (value === "12v" || value === "12") return "12V";
  return null;
}

export function railVolts(rail: Rail): number {
  return RAIL_VOLTS[rail];
}

export function padVolts(net: BenchNet, label: string): number | null {
  const rail = net.ties.get(label);
  if (rail === undefined) return null;
  return railVolts(rail);
}

export function netSummary(net: BenchNet): string {
  if (net.ties.size === 0) return "bare";
  return [...net.ties.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([pad, rail]) => `${pad}=${rail}`)
    .join(" ");
}

export function extPorta(net: BenchNet, ddr: number): number {
  let bits = 0;
  for (const [pad, rail] of net.ties) {
    const bit = gpioBit(pad);
    if (bit === null) continue;
    if (((ddr >> bit) & 1) === 1) continue;
    if (railVolts(rail) >= 1.5) bits |= 1 << bit;
  }
  return bits;
}

function applyLine(net: BenchNet): void {
  net.ties.clear();
  net.ties.set("P20", "12V");
  net.ties.set("P15", "12V");
  net.ties.set("P24", "12V");
}

/** Each SCENE replaces the whole net (snapshot), not a patch. */
export function applyScene(net: BenchNet, name: SceneName): void {
  if (name === "bare") {
    net.ties.clear();
    return;
  }
  applyLine(net);
  if (name === "line") return;
  if (name === "vcap") {
    net.ties.set("P23", "GND");
    return;
  }
  net.ties.set("P34", "3V3");
}

function padError(raw: string | undefined): BenchReply {
  if (raw === undefined) return { ok: false, line: `ERR pad — шелк Pxx (${PAD_LIST})` };
  return { ok: false, line: `ERR pad '${raw}' — шелк Pxx (${PAD_LIST})` };
}

function railError(raw: string | undefined): BenchReply {
  if (raw === undefined) return { ok: false, line: "ERR rail (gnd|3v3|5v|12v)" };
  if (canonicalPad(raw) !== null) {
    return { ok: false, line: `ERR rail '${raw}' — TIE только к GND|3V3|5V|12V, не pad-to-pad` };
  }
  return { ok: false, line: `ERR rail '${raw}' (gnd|3v3|5v|12v)` };
}

export function applyBenchLine(net: BenchNet, raw: string): BenchReply {
  const line = raw.trim().replace(/\s+/g, " ");
  if (line.length === 0) return { ok: true, line: `OK ${netSummary(net)}` };
  const parts = line.split(" ");
  const rawVerb = parts[0] ?? "";
  const verb = rawVerb.toUpperCase();
  if (verb === "NET" || verb === "SHOW") {
    return { ok: true, line: `OK ${netSummary(net)}` };
  }
  if (verb === "CLEAR") {
    net.ties.clear();
    return { ok: true, line: `OK ${netSummary(net)}` };
  }
  if (verb === "OPEN") {
    const pad = canonicalPad(parts[1] ?? "");
    if (pad === null) return padError(parts[1]);
    net.ties.delete(pad);
    return { ok: true, line: `OK ${netSummary(net)}` };
  }
  if (verb === "TIE") {
    const pad = canonicalPad(parts[1] ?? "");
    const rail = canonicalRail(parts[2] ?? "");
    if (pad === null) return padError(parts[1]);
    if (rail === null) return railError(parts[2]);
    net.ties.set(pad, rail);
    return { ok: true, line: `OK ${netSummary(net)}` };
  }
  if (verb === "SCENE") {
    const name = (parts[1] ?? "").toLowerCase();
    if (!isSceneName(name)) return { ok: false, line: "ERR scene (bare|line|vcap|reset)" };
    applyScene(net, name);
    return { ok: true, line: `OK ${netSummary(net)}` };
  }
  return { ok: false, line: `ERR verb '${rawVerb}' (TIE|OPEN|SCENE|CLEAR|NET)` };
}

export function applyBenchScript(net: BenchNet, raw: string): BenchReply {
  let last: BenchReply = { ok: true, line: `OK ${netSummary(net)}` };
  for (const part of raw.split(/[;\n]/)) {
    if (part.trim().length === 0) continue;
    last = applyBenchLine(net, part);
    if (!last.ok) return last;
  }
  return last;
}

/** `TIE P20 12V TIE P23 GND` or a single `NET`. */
export function parseBenchArgv(args: string[]): BenchArgv {
  if (args.length === 0) return { ok: true, lines: [] };
  const joined = args.join(" ");
  if (joined.includes(";")) {
    const lines = joined
      .split(";")
      .map((part) => part.trim())
      .filter((part) => part.length > 0);
    return { ok: true, lines };
  }
  const lines: string[] = [];
  let index = 0;
  while (index < args.length) {
    const rawVerb = args[index] ?? "";
    const verb = rawVerb.toUpperCase();
    if (verb === "NET" || verb === "SHOW" || verb === "CLEAR") {
      lines.push(verb);
      index += 1;
      continue;
    }
    if (verb === "OPEN") {
      const pad = args[index + 1];
      if (pad === undefined) return { ok: false, line: `ERR OPEN — шелк Pxx (${PAD_LIST})` };
      lines.push(`OPEN ${pad}`);
      index += 2;
      continue;
    }
    if (verb === "TIE") {
      const pad = args[index + 1];
      const rail = args[index + 2];
      if (pad === undefined || rail === undefined) {
        return { ok: false, line: "ERR TIE <Pxx> <gnd|3v3|5v|12v>" };
      }
      lines.push(`TIE ${pad} ${rail}`);
      index += 3;
      continue;
    }
    if (verb === "SCENE") {
      const name = args[index + 1];
      if (name === undefined) return { ok: false, line: "ERR SCENE bare|line|vcap|reset" };
      lines.push(`SCENE ${name}`);
      index += 2;
      continue;
    }
    return { ok: false, line: `ERR verb '${rawVerb}' (TIE|OPEN|SCENE|CLEAR|NET)` };
  }
  return { ok: true, lines };
}
