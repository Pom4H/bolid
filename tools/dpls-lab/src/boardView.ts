import { driveFromBoard, driveFromHex, livePins, type HexGpio, type LivePin } from "./pins";
import type { BenchNet } from "./benchNet";
import { netSummary } from "./benchNet";
import type { BoardSnapshot } from "./types";

const RESET = "\x1b[0m";
const DIM = "\x1b[2m";
const BOLD = "\x1b[1m";
const INV = "\x1b[7m";
const GREEN = "#34c759";
const RED = "#d45252";
const ORANGE = "#ff8a3d";
const CYAN = "#00ccff";
const GOLD = "#c4a574";

export type BleSighting = {
  id: string;
  name: string;
  rssi: number;
  firmware: string | null;
};

export type BleView = {
  available: boolean;
  scanning: boolean;
  error: string | null;
  devices: BleSighting[];
  selected: number;
  linkedId: string | null;
  lastTx: string | null;
  air: boolean;
  airName: string | null;
  phoneOn: boolean;
};

export type BoardViewModel = {
  board: BoardSnapshot | null;
  ble: BleView;
  simError: string | null;
  hexGpio: HexGpio | null;
  hexStatus: string | null;
  bench: BenchNet;
};

function paint(hex: string, text: string): string {
  const n = hex.replace("#", "");
  if (n.length !== 6) return text;
  const r = Number.parseInt(n.slice(0, 2), 16);
  const g = Number.parseInt(n.slice(2, 4), 16);
  const b = Number.parseInt(n.slice(4, 6), 16);
  return `\x1b[38;2;${r};${g};${b}m${text}${RESET}`;
}

function visibleLen(text: string): string {
  return text.replace(/\x1b\[[0-9;]*m/g, "");
}

function vis(text: string): number {
  return visibleLen(text).length;
}

function padVisible(text: string, width: number, align: "left" | "right" = "left"): string {
  const n = vis(text);
  if (n >= width) return text;
  const pad = " ".repeat(width - n);
  return align === "right" ? `${pad}${text}` : `${text}${pad}`;
}

function spread(left: string, right: string, width: number): string {
  const gap = width - vis(left) - vis(right);
  if (gap <= 0) return `${left} ${right}`;
  return `${left}${" ".repeat(gap)}${right}`;
}

function lamp(on: boolean, color: string): string {
  return on ? paint(color, "●") : `${DIM}○${RESET}`;
}

function adcMux(pin: string): string | null {
  switch (pin) {
    case "P20":
      return "CH3P";
    case "P15":
      return "CH3N";
    case "P24":
      return "CH2N";
    case "P23":
      return "CH1P";
    default:
      return null;
  }
}

function hole(pin: LivePin): string {
  if (pin.kind === "adc") return lamp((pin.volts ?? 0) > 0.05, GREEN);
  if (pin.kind === "gpio") return lamp(pin.high === true, CYAN);
  if (pin.kind === "reset") return lamp(pin.high === true, ORANGE);
  if (pin.label === "5V") return paint(RED, "●");
  if (pin.label === "3V3") return paint(ORANGE, "●");
  if (pin.kind === "gnd") return paint("#3d6ea8", "●");
  return `${DIM}·${RESET}`;
}

function caption(pin: LivePin): string {
  const mux = adcMux(pin.label);
  if (pin.kind === "adc" && pin.volts !== null) {
    return `${pin.label} ${pin.volts.toFixed(2)} ${mux ?? ""}`.trim();
  }
  if (pin.kind === "gpio" || pin.kind === "reset") {
    const v = pin.high === true ? "3.3" : pin.high === false ? "0.0" : "   ";
    const net = pin.role.startsWith("ISO") || pin.role.startsWith("KZ") || pin.role.startsWith("LED")
      ? ` ${DIM}${pin.role.replace(" LED", "")}${RESET}`
      : "";
    return `${pin.label} ${v}${net}`;
  }
  if (pin.kind === "uart" || pin.kind === "swd") return `${pin.label} ${DIM}${pin.role}${RESET}`;
  return pin.label;
}

function clock(ms: number): string {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function centerStrip(drive: ReturnType<typeof driveFromBoard>, row: number): string {
  const rgb = `${lamp(drive.ledR, RED)}${lamp(drive.ledG, GREEN)}${lamp(drive.ledB, CYAN)}`;
  switch (row) {
    case 0:
      return paint("#7d9a72", "≈≈≈ ANT");
    case 1:
      return `${paint(GOLD, "┌──────────┐")}`;
    case 2:
      return `${paint(GOLD, "│ PB-03F   │")} ${rgb}`;
    case 3:
      return paint(GOLD, "│ PHY6252  │");
    case 4:
      return `${DIM}│ BLE 5.2  │${RESET}`;
    case 5:
      return `${DIM}│ 2ATPO    │${RESET}`;
    case 6:
      return paint(GOLD, "└──────────┘");
    case 7:
      return `${BOLD}AI-Thinker${RESET}`;
    case 8:
      return "Kit A148";
    case 10:
      return `${DIM}64K SRAM${RESET}`;
    case 11:
      return `${DIM}0x1FFF0000${RESET}`;
    case 13:
      return `${DIM}RST     Restore${RESET}`;
    case 14:
      return `${DIM}[ Micro-USB ]${RESET}`;
    default:
      return "";
  }
}

function renderKit(board: BoardSnapshot, hex: HexGpio | null, net: BenchNet): string[] {
  const pins = livePins(board, hex, net);
  const drive = hex !== null ? driveFromHex(hex) : driveFromBoard(board);
  const lines: string[] = [];
  const j1 = pins.j1
    .map((pin) => `${hole(pin)}${DIM}${pin.label}${RESET}`)
    .join(" ");
  const gutter = " ".repeat(18);
  lines.push(`${gutter}J1  ${j1}`);
  lines.push(`${gutter}${paint(GOLD, `┌${"─".repeat(24)}┐`)}`);
  for (let i = 0; i < 15; i += 1) {
    const left = pins.j3[i];
    const right = pins.j2[i];
    if (left === undefined || right === undefined) break;
    const mid = padVisible(` ${centerStrip(drive, i)} `, 24);
    lines.push(
      `${padVisible(caption(left), 16, "right")} ${hole(left)}${paint(GOLD, "┤")}${mid}${paint(GOLD, "├")}${hole(right)} ${caption(right)}`,
    );
  }
  lines.push(`${gutter}${paint(GOLD, `└${"─".repeat(24)}┘`)}`);
  return lines;
}

function analogLabel(net: BenchNet): string {
  const line = net.ties.get("P20");
  if (line === "12V") return paint(GREEN, "ADC 12V");
  return `${DIM}USB analog${RESET}`;
}

export function renderBoardView(model: BoardViewModel, columns: number): string {
  const width = Math.max(72, Math.min(100, columns));
  const lines: string[] = [];
  const board = model.board;
  lines.push(
    spread(
      `${BOLD}AI-Thinker PB-03F-Kit${RESET}  ${DIM}A148${RESET}`,
      `${paint(CYAN, "PHY6252")}  ${DIM}BUMBee M0${RESET}`,
      width,
    ),
  );
  if (board === null) {
    lines.push(model.simError !== null ? paint(RED, model.simError) : `${DIM}host sim…${RESET}`);
  } else {
    const flags = [
      analogLabel(model.bench),
      model.hexGpio !== null ? paint(CYAN, "hex GPIO") : `${DIM}hex…${RESET}`,
      model.bench.ties.get("P23") === "GND" ? paint(ORANGE, "VCAP lo") : `${DIM}VCAP${RESET}`,
      board.real_short === 1 ? paint(RED, "short-in") : `${DIM}short-in${RESET}`,
      board.radio.connected === 1 ? paint(GREEN, "ATT") : `${DIM}ATT${RESET}`,
      `${DIM}adv ${board.name}${RESET}`,
      `${DIM}${clock(board.now_ms)}${RESET}`,
    ];
    lines.push(flags.join("  "));
    lines.push(`  ${DIM}bench${RESET}  ${netSummary(model.bench)}`);
    if (model.simError !== null) lines.push(paint(RED, model.simError));
    if (model.hexStatus !== null) lines.push(`${DIM}${model.hexStatus}${RESET}`);
    for (const line of renderKit(board, model.hexGpio, model.bench)) lines.push(line);
    const r = board.radio;
    lines.push(
      `  ${DIM}radio${RESET}  CCCD 0x${r.cccd.toString(16).padStart(2, "0")}  RX ${r.rx}/6  TX ${r.tx}/4` +
        `  ${r.inflight === 1 ? paint(ORANGE, "inflight") : `${DIM}idle${RESET}`}` +
        `  SNV ${r.snv_dirty === 1 ? paint(ORANGE, "dirty") : "ok"}  stack ${r.stack_bytes}`,
    );
  }

  lines.push("");
  for (const line of renderBle(model.ble, width).split("\n")) lines.push(line);
  lines.push("");
  lines.push(
    `${DIM}u bare   a line   v P23   s short-in   p air   b scan PHY   ⏎ GATT   d disc   q${RESET}`,
  );
  return lines.join("\n");
}

function renderBle(ble: BleView, width: number): string {
  if (!ble.available) {
    return `  ${DIM}radio${RESET}  нет native/dpls-ble`;
  }
  if (ble.error !== null) {
    return `  radio  ${paint(RED, ble.error)}`;
  }
  if (ble.air) {
    const name = ble.airName ?? "DPLS1234";
    if (ble.phoneOn) {
      const lines = [`  air  GATT ${paint(GREEN, "phone")}  ${name}`];
      if (ble.lastTx !== null) {
        lines.push(`  ${DIM}TX ${ble.lastTx.slice(0, Math.max(12, width - 6))}${RESET}`);
      }
      return lines.join("\n");
    }
    return `  air  advertising ${paint(CYAN, name)}  ${DIM}телефон видит kit${RESET}`;
  }
  if (ble.linkedId !== null) {
    const linked = ble.devices.find((item) => item.id === ble.linkedId);
    const name = linked?.name ?? ble.linkedId;
    const lines = [`  host  GATT ${paint(GREEN, name)}  ${DIM}PHY6252${RESET}`];
    if (ble.lastTx !== null) {
      lines.push(`  ${DIM}TX ${ble.lastTx.slice(0, Math.max(12, width - 6))}${RESET}`);
    }
    return lines.join("\n");
  }
  if (!ble.scanning && ble.devices.length === 0) {
    return `  ${DIM}host  CoreBluetooth off${RESET}   ${DIM}b — скан PB-03F в эфире${RESET}`;
  }
  const lines = [`  host  ${ble.scanning ? "scan…" : "stop"}  ${ble.devices.length} PHY`];
  ble.devices.slice(0, 4).forEach((item, index) => {
    const mark = index === ble.selected ? "▸" : " ";
    const fw = item.firmware ?? "";
    const row = `${mark} ${item.name}  ${item.rssi} dBm  ${fw}`.trimEnd();
    lines.push(index === ble.selected ? `  ${INV}${padVisible(row, width - 2)}${RESET}` : `  ${row}`);
  });
  return lines.join("\n");
}
