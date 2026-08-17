import { spawn, type Subprocess } from "bun";
import { existsSync, unlinkSync } from "node:fs";
import { createServer, type Server } from "node:net";
import { join, resolve } from "node:path";
import process from "node:process";
import { NativeBle, type LineStdin } from "./native-ble";
import { DEFAULT_FIRMWARE } from "./src/firmware";
import { renderBoardView, type BleSighting, type BleView, type BoardViewModel } from "./src/boardView";
import {
  applyBenchScript,
  benchSockPath,
  emptyNet,
  extPorta,
  isSceneName,
  type BenchNet,
  type SceneName,
} from "./src/benchNet";
import { parseBoardSnapshot, type BoardSnapshot } from "./src/types";
import { driveFromHex, type HexGpio } from "./src/pins";

const ROOT = resolve(import.meta.dir, "../..");
const LAB = import.meta.dir;
const decoder = new TextDecoder();
const HEX_FIRMWARE = "1.4.0";

function zmuPath(): string {
  const fromEnv = process.env.DPLS_ZMU;
  if (fromEnv !== undefined && fromEnv.length > 0 && existsSync(fromEnv)) {
    return fromEnv;
  }
  const release = join(ROOT, "third_party/phy6252-emu/target/release/phy6252");
  const debug = join(ROOT, "third_party/phy6252-emu/target/debug/phy6252");
  if (existsSync(release)) return release;
  if (existsSync(debug)) return debug;
  return "";
}

function hexPath(): string {
  return (
    process.env.DPLS_HEX ??
    join(ROOT, "third_party/phy6252-emu/firmware/kit-demo.hex")
  );
}

function parseGpioLine(line: string): HexGpio | null {
  if (!line.startsWith("GPIO ")) return null;
  const parts = line.slice(5).trim().split(/\s+/);
  if (parts.length < 2) return null;
  const dr = Number.parseInt(parts[0], 16);
  const ddr = Number.parseInt(parts[1], 16);
  if (!Number.isFinite(dr) || !Number.isFinite(ddr)) return null;
  return { dr, ddr };
}

function simulatorPath(): string {
  return (
    process.env.DPLS_SIMULATOR ??
    (existsSync(join(ROOT, "firmware/build/dpls_simulator"))
      ? join(ROOT, "firmware/build/dpls_simulator")
      : join(ROOT, "Firmware/build/dpls_simulator"))
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function advStatus(board: BoardSnapshot): number {
  let status = 0;
  if (board.real_short === 1) status |= 0x01;
  if (board.power === 1) status |= 0x02;
  if (board.reserve_low === 1) status |= 0x04;
  return status;
}

class OneBoard {
  board: BoardSnapshot | null = null;
  simError: string | null = null;
  hexGpio: HexGpio | null = null;
  hexStatus: string | null = null;
  bench: BenchNet = emptyNet();
  ble: BleView = {
    available: false,
    scanning: false,
    error: null,
    devices: [],
    selected: 0,
    linkedId: null,
    lastTx: null,
    air: false,
    airName: null,
    phoneOn: false,
  };
  airLog = false;
  hexReady = false;
  dirty = true;
  private lastAirGpio: string | null = null;
  private lastAdc: string | null = null;

  private sim: { proc: Subprocess; stdin: LineStdin } | null = null;
  private hex: { proc: Subprocess; stdin: LineStdin } | null = null;
  private hexQueue: string[] = [];
  private sock: Server | null = null;
  private readonly radio: NativeBle;
  private tick: ReturnType<typeof setInterval> | null = null;
  private closing = false;
  private airSubscribed = false;
  private wantAir = false;

  constructor() {
    this.radio = new NativeBle(LAB);
    this.ble.available = this.radio.available();
  }

  model(): BoardViewModel {
    return {
      board: this.board,
      ble: this.ble,
      simError: this.simError,
      hexGpio: this.hexGpio,
      hexStatus: this.hexStatus,
      bench: this.bench,
    };
  }

  async start(): Promise<void> {
    const binary = simulatorPath();
    if (!existsSync(binary)) {
      throw new Error(`нет dpls_simulator (${binary})`);
    }
    const proc = spawn([binary, "--id", "0x1234", "--fw", DEFAULT_FIRMWARE], {
      stdin: "pipe",
      stdout: "pipe",
      stderr: "pipe",
    });
    if (proc.stdin === undefined || proc.stdout === undefined) {
      throw new Error("simulator pipes missing");
    }
    this.sim = { proc, stdin: proc.stdin };
    void this.readSim(proc.stdout);
    if (proc.stderr !== undefined) void this.readErr(proc.stderr);
    void proc.exited.then((code: number) => {
      if (this.closing) return;
      this.simError = `симулятор вышел (${code})`;
      this.dirty = true;
    });
    this.cmd("SNAPSHOT");
    this.tick = setInterval(() => {
      this.cmd("SNAPSHOT");
      this.hexCmd("TICK 80");
    }, 80);
    this.startHex();
    this.listenBench();
  }

  private startHex(): void {
    const binary = zmuPath();
    const image = hexPath();
    if (binary.length === 0) {
      throw new Error("нет phy6252 (export DPLS_ZMU=/path/to/phy6252, or cargo build --release --manifest-path third_party/phy6252-emu/Cargo.toml)");
    }
    if (!existsSync(image)) {
      throw new Error(`нет hex (${image})`);
    }
    const proc = spawn([binary, "--raw", image], {
      stdin: "pipe",
      stdout: "pipe",
      stderr: "pipe",
    });
    if (proc.stdin === undefined) {
      throw new Error("phy6252 stdin missing");
    }
    this.hex = { proc, stdin: proc.stdin };
    if (proc.stdout !== undefined) void this.readHex(proc.stdout);
    if (proc.stderr !== undefined) void this.readHexErr(proc.stderr);
    void proc.exited.then((code: number) => {
      if (this.closing) return;
      this.hexStatus = `phy6252 вышел (${code})`;
      this.dirty = true;
    });
  }

  applyBench(raw: string): string {
    const reply = applyBenchScript(this.bench, raw);
    this.syncSimFromBench();
    this.pushChipInputs();
    this.dirty = true;
    return reply.line;
  }

  private syncSimFromBench(): void {
    if (this.bench.ties.get("P20") === "12V") this.cmd("POWER LINE");
    else this.cmd("LAB");
    this.cmd(`RESERVE_LOW ${this.bench.ties.get("P23") === "GND" ? 1 : 0}`);
  }

  private pushChipInputs(): void {
    if (this.hex === null) return;
    const bits = extPorta(this.bench, this.hexGpio?.ddr ?? 0);
    this.hex.stdin.write(`IN ${bits.toString(16).padStart(8, "0")}\n`);
    this.hex.stdin.flush?.();
  }

  private listenBench(): void {
    const path = benchSockPath();
    try {
      unlinkSync(path);
    } catch {
      /* leftover socket from a killed TUI */
    }
    const server = createServer((socket) => {
      let buffer = "";
      socket.on("data", (chunk: Buffer) => {
        buffer += chunk.toString("utf8");
        let nl = buffer.indexOf("\n");
        while (nl >= 0) {
          const line = buffer.slice(0, nl).replace(/\r$/, "");
          buffer = buffer.slice(nl + 1);
          socket.write(`${this.applyBench(line)}\n`);
          nl = buffer.indexOf("\n");
        }
      });
    });
    server.listen(path);
    this.sock = server;
  }

  cmd(line: string): void {
    if (this.sim === null) return;
    this.sim.stdin.write(`${line}\n`);
    this.sim.stdin.flush?.();
  }

  hexCmd(line: string): void {
    if (this.hex === null) return;
    if (!this.hexReady) {
      this.hexQueue.push(line);
      return;
    }
    this.hex.stdin.write(`${line}\n`);
    this.hex.stdin.flush?.();
  }

  private flushHexQueue(): void {
    const queued = this.hexQueue;
    this.hexQueue = [];
    for (const line of queued) this.hexCmd(line);
  }

  markAirLog(): void {
    this.airLog = true;
    if (this.lastAdc !== null) process.stdout.write(`hex ${this.lastAdc}\n`);
  }

  analogUsb(): void {
    this.applyBench("SCENE bare");
  }

  analog12v(): void {
    this.applyBench("SCENE line");
  }

  toggleVcap(): void {
    if (this.bench.ties.get("P23") === "GND") this.applyBench("OPEN P23");
    else this.applyBench("TIE P23 GND");
  }

  toggleShort(): void {
    this.cmd(`REAL_SHORT ${this.board?.real_short === 1 ? 0 : 1}`);
  }

  startAir(): void {
    if (!this.ble.available) {
      this.ble.error = "соберите tools/dpls-lab/native/dpls-ble";
      this.dirty = true;
      return;
    }
    this.wantAir = true;
    this.radio.stopCentral();
    this.ble.scanning = false;
    this.ble.linkedId = null;
    this.ble.error = null;
    const board = this.board;
    const deviceId = board?.device_id ?? 0x1234;
    const firmware = HEX_FIRMWARE;
    const name = board?.name ?? `Test-DPLS-${(deviceId & 0xffff).toString(16).toUpperCase().padStart(4, "0")}`;
    const status = board === null ? 0 : advStatus(board);
    const ok = this.radio.startPeripheral({ name, deviceId, firmware, status }, (line) => this.onAir(line));
    if (!ok) {
      this.ble.error = "не удалось включить air";
      this.wantAir = false;
      this.dirty = true;
      return;
    }
    this.ble.air = true;
    this.ble.airName = `DPLS${(deviceId & 0xffff).toString(16).toUpperCase().padStart(4, "0")}`;
    this.ble.phoneOn = false;
    this.airSubscribed = false;
    this.dirty = true;
  }

  stopAir(): void {
    this.wantAir = false;
    if (this.airSubscribed) this.hexCmd("DISCONNECT");
    this.radio.stopPeripheral();
    this.airSubscribed = false;
    this.ble.air = false;
    this.ble.phoneOn = false;
    this.ble.airName = null;
    this.dirty = true;
  }

  toggleAir(): void {
    if (this.ble.air) this.stopAir();
    else this.startAir();
  }

  toggleBleScan(): void {
    if (this.ble.air) this.stopAir();
    if (!this.ble.available) {
      this.ble.error = "соберите tools/dpls-lab/native/dpls-ble";
      this.dirty = true;
      return;
    }
    if (this.ble.scanning) {
      this.radio.writeCentral("STOP");
      this.ble.scanning = false;
      this.dirty = true;
      return;
    }
    this.ble.error = null;
    this.ble.linkedId = null;
    const ok = this.radio.startCentral((line) => this.onBle(line));
    if (!ok) {
      this.ble.error = "не удалось запустить central";
      this.dirty = true;
      return;
    }
    this.ble.scanning = true;
    this.radio.writeCentral("SCAN");
    this.dirty = true;
  }

  moveBle(delta: number): void {
    if (this.ble.devices.length === 0) return;
    const n = this.ble.devices.length;
    this.ble.selected = (this.ble.selected + delta + n) % n;
    this.dirty = true;
  }

  connectSelected(): void {
    const device = this.ble.devices[this.ble.selected];
    if (device === undefined) return;
    if (!this.ble.scanning && this.ble.available) this.toggleBleScan();
    this.radio.writeCentral(`CONNECT ${device.id}`);
  }

  disconnectBle(): void {
    this.radio.writeCentral("DISCONNECT");
    this.ble.linkedId = null;
    this.dirty = true;
  }

  close(): void {
    this.closing = true;
    if (this.tick !== null) clearInterval(this.tick);
    this.radio.stopCentral();
    this.radio.stopPeripheral();
    if (this.sim !== null) {
      this.cmd("QUIT");
      this.sim.proc.kill();
      this.sim = null;
    }
    if (this.hex !== null) {
      this.hex.proc.kill();
      this.hex = null;
    }
    this.hexReady = false;
    this.hexQueue = [];
    if (this.sock !== null) {
      this.sock.close();
      this.sock = null;
      try {
        unlinkSync(benchSockPath());
      } catch {
        /* already gone */
      }
    }
  }

  private async readSim(stdout: ReadableStream<Uint8Array>): Promise<void> {
    const reader = stdout.getReader();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl = buffer.indexOf("\n");
      while (nl >= 0) {
        const line = buffer.slice(0, nl).replace(/\r$/, "");
        buffer = buffer.slice(nl + 1);
        this.onSim(line);
        nl = buffer.indexOf("\n");
      }
    }
  }

  private async readErr(stderr: ReadableStream<Uint8Array>): Promise<void> {
    const reader = stderr.getReader();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl = buffer.indexOf("\n");
      while (nl >= 0) {
        const line = buffer.slice(0, nl).replace(/\r$/, "").trim();
        buffer = buffer.slice(nl + 1);
        if (line.length > 0) {
          this.simError = line;
          this.dirty = true;
        }
        nl = buffer.indexOf("\n");
      }
    }
  }

  private async readHex(stdout: ReadableStream<Uint8Array>): Promise<void> {
    const reader = stdout.getReader();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl = buffer.indexOf("\n");
      while (nl >= 0) {
        const line = buffer.slice(0, nl).replace(/\r$/, "");
        buffer = buffer.slice(nl + 1);
        this.onHex(line);
        nl = buffer.indexOf("\n");
      }
    }
  }

  private async readHexErr(stderr: ReadableStream<Uint8Array>): Promise<void> {
    const reader = stderr.getReader();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl = buffer.indexOf("\n");
      while (nl >= 0) {
        buffer = buffer.slice(nl + 1);
        nl = buffer.indexOf("\n");
      }
    }
  }

  private onHex(line: string): void {
    const gpio = parseGpioLine(line);
    if (gpio !== null) {
      this.hexGpio = gpio;
      this.hexStatus = null;
      this.pushChipInputs();
      this.dirty = true;
      if (this.airLog) {
        const drive = driveFromHex(gpio);
        const signature =
          `${gpio.dr.toString(16)}:${gpio.ddr.toString(16)}:` +
          `${drive.kz1 ? 1 : 0}${drive.kz2 ? 1 : 0}${drive.kzT ? 1 : 0}:` +
          `${drive.iso1 ? 1 : 0}${drive.iso2 ? 1 : 0}${drive.isoT ? 1 : 0}:` +
          `${drive.ledR ? 1 : 0}${drive.ledG ? 1 : 0}${drive.ledB ? 1 : 0}`;
        if (signature !== this.lastAirGpio) {
          this.lastAirGpio = signature;
          process.stdout.write(
            `hex gpio dr=${gpio.dr.toString(16).padStart(8, "0")} ddr=${gpio.ddr.toString(16).padStart(8, "0")} ` +
              `ledRGB=${drive.ledR ? 1 : 0}${drive.ledG ? 1 : 0}${drive.ledB ? 1 : 0} ` +
              `kz=${drive.kz1 ? 1 : 0}${drive.kz2 ? 1 : 0}${drive.kzT ? 1 : 0} ` +
              `iso=${drive.iso1 ? 1 : 0}${drive.iso2 ? 1 : 0}${drive.isoT ? 1 : 0}\n`,
          );
        }
      }
      return;
    }
    if (line === "READY" || line === "READY HEX") {
      this.hexReady = true;
      this.hexStatus = null;
      this.flushHexQueue();
      this.dirty = true;
      if (this.airLog) process.stdout.write("hex READY\n");
      return;
    }
    if (line.startsWith("HOOK ")) {
      this.hexStatus = line;
      this.dirty = true;
      if (this.airLog) process.stdout.write(`${line}\n`);
      return;
    }
    if (line.startsWith("FRAME ")) {
      const hex = line.slice(6).trim();
      this.ble.lastTx = hex;
      if (this.airSubscribed) this.radio.writePeripheral(`TX ${hex}`);
      this.dirty = true;
      if (this.airLog) process.stdout.write(`hex TX ${hex}\n`);
      return;
    }
    if (line.startsWith("ERR ")) {
      this.hexStatus = line.slice(4);
      this.dirty = true;
      if (this.airLog) process.stdout.write(`${line}\n`);
      return;
    }
    if (line.startsWith("STOP ")) {
      this.hexStatus = line.slice(5);
      this.dirty = true;
    }
  }

  private onSim(line: string): void {
    if (line === "READY DPLS2") {
      this.cmd("SNAPSHOT");
      return;
    }
    if (line.startsWith("LED ")) {
      if (this.airLog) process.stdout.write(`${line}\n`);
      return;
    }
    if (line.startsWith("TX ")) {
      return;
    }
    if (!line.startsWith("SNAPSHOT ")) return;
    try {
      const board = parseBoardSnapshot(JSON.parse(line.slice(9)) as unknown);
      if (board === null) return;
      this.board = board;
      this.pushHexAdc(board);
      this.dirty = true;
    } catch {
      return;
    }
  }

  private pushHexAdc(board: BoardSnapshot): void {
    const line =
      `ADC ${board.line_mv} ${board.port2_mv} ${board.port_t_mv} ${board.reserve_mv} ` +
      `${board.power} ${board.reserve_low}`;
    if (line === this.lastAdc) return;
    this.lastAdc = line;
    this.hexCmd(line);
    if (this.airLog) process.stdout.write(`hex ${line}\n`);
  }

  private onAir(line: string): void {
    if (this.airLog) process.stdout.write(`air ${line}\n`);
    if (line === "READY") {
      this.ble.error = null;
      this.ble.air = true;
      this.dirty = true;
      return;
    }
    if (line === "CONNECTED") {
      this.hexCmd("CONNECT");
      this.ble.phoneOn = true;
      this.dirty = true;
      return;
    }
    if (line === "SUBSCRIBED") {
      this.airSubscribed = true;
      this.hexCmd("CCCD 3");
      this.dirty = true;
      return;
    }
    if (line === "DISCONNECTED") {
      this.airSubscribed = false;
      this.ble.phoneOn = false;
      this.hexCmd("DISCONNECT");
      this.dirty = true;
      return;
    }
    if (line.startsWith("RX ")) {
      this.hexCmd(`WRITE ${line.slice(3).trim()}`);
      return;
    }
    if (line.startsWith("ERROR ")) {
      this.ble.error = line.slice(6);
      this.dirty = true;
    }
  }

  private onBle(line: string): void {
    if (line === "READY") {
      this.radio.writeCentral("SCAN");
      this.ble.scanning = true;
      this.ble.error = null;
      this.dirty = true;
      return;
    }
    if (line.startsWith("ADV ")) {
      try {
        const parsed: unknown = JSON.parse(line.slice(4));
        if (!isRecord(parsed) || typeof parsed.id !== "string" || typeof parsed.name !== "string") return;
        const rssi = typeof parsed.rssi === "number" ? parsed.rssi : -80;
        const firmware = typeof parsed.firmware === "string" ? parsed.firmware : null;
        const next: BleSighting = { id: parsed.id, name: parsed.name, rssi, firmware };
        const index = this.ble.devices.findIndex((item) => item.id === next.id);
        if (index >= 0) this.ble.devices[index] = next;
        else this.ble.devices.push(next);
        this.dirty = true;
      } catch {
        return;
      }
      return;
    }
    if (line.startsWith("CONNECTED ")) {
      this.ble.linkedId = line.slice(10).trim();
      this.ble.scanning = false;
      this.dirty = true;
      return;
    }
    if (line.startsWith("TX ")) {
      this.ble.lastTx = line.slice(3).trim();
      this.dirty = true;
      return;
    }
    if (line === "DISCONNECTED") {
      this.ble.linkedId = null;
      this.dirty = true;
      return;
    }
    if (line.startsWith("ERROR ")) {
      this.ble.error = line.slice(6);
      this.dirty = true;
    }
  }
}

function enterScreen(): void {
  process.stdout.write("\x1b[?1049h\x1b[?25l");
}

function leaveScreen(): void {
  process.stdout.write("\x1b[?25h\x1b[?1049l");
}

function draw(app: OneBoard): void {
  const cols = process.stdout.columns ?? 80;
  const rows = process.stdout.rows ?? 24;
  const padded = renderBoardView(app.model(), cols).split("\n").slice(0, rows);
  while (padded.length < rows) padded.push("");
  process.stdout.write(`\x1b[H${padded.map((line) => `${line}\x1b[K`).join("\r\n")}`);
}

function handleKey(app: OneBoard, key: string): boolean {
  if (key === "\u0003" || key === "q") return false;
  if (key === "\x1b[A") app.moveBle(-1);
  else if (key === "\x1b[B") app.moveBle(1);
  else if (key === "\r" || key === "\n") app.connectSelected();
  else if (key === "d") {
    if (app.ble.air) app.stopAir();
    else app.disconnectBle();
  }
  else if (key === "p") app.toggleAir();
  else if (key === "b") app.toggleBleScan();
  else if (key === "u") app.analogUsb();
  else if (key === "a") app.analog12v();
  else if (key === "v") app.toggleVcap();
  else if (key === "s") app.toggleShort();
  return true;
}

function sceneArg(args: string[]): SceneName | null {
  const index = args.indexOf("--scene");
  if (index < 0) return null;
  const name = args[index + 1];
  if (name === undefined || !isSceneName(name)) return null;
  return name;
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.includes("-h") || args.includes("--help")) {
    process.stdout.write(
      [
        "PB-03F-Kit — PHY6252 bring-up (AI-Thinker A148)",
        "",
        "  bash tools/dpls_board.sh [--once | --air | --listen] [--scene bare|line|vcap|reset]",
        "  bash tools/dpls_bench.sh TIE P20 12V     # провода в уже запущенный TUI",
        "  bash tools/dpls_bench.sh --once scene vcap",
        "",
        "  Чип: Keil hex AP_GPIO. Глаза: этот CLI. Провода: dpls_bench (тот же протокол, что u/a/v).",
        "  SCENE — снимок всей сетки, не патч. vcap = line+P23, reset = line+P34.",
        "  p  air — Mac рекламирует kit; GATT идёт в Keil hex на zmu, не в host-sim.",
        "  b  scan живой PHY6252 (central; на Mac не вместе с air).",
        "  --listen — сокет без TTY (для скриптов).",
        "",
        "  u  SCENE bare (USB, ADC открыты)",
        "  a  SCENE line (12 V на P20/P15/P24)",
        "  v  TIE/OPEN P23 GND (не сбрасывает line)",
        "  s  short-in (флаг прошивки, не jumper)",
        "  ↑↓ Enter  GATT connect (central)",
        "  d  disconnect / air off",
        "  q  выход",
        "",
      ].join("\n"),
    );
    return;
  }

  const app = new OneBoard();
  await app.start();
  const boot = process.env.DPLS_BENCH_BOOT;
  const scene = sceneArg(args);
  if (boot !== undefined && boot.length > 0) app.applyBench(boot);
  else if (scene !== null) app.applyBench(`SCENE ${scene}`);
  else app.applyBench("SCENE line");

  if (args.includes("--once")) {
    const deadline = Date.now() + 4000;
    while (Date.now() < deadline && (app.board === null || app.hexGpio === null)) await Bun.sleep(50);
    process.stdout.write(`${renderBoardView(app.model(), 80)}\n`);
    app.close();
    if (app.board === null) process.exitCode = 1;
    return;
  }

  const air = args.includes("--air");
  const listen = args.includes("--listen");
  if (air) {
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline && (!app.hexReady || app.board === null)) await Bun.sleep(50);
    if (!app.hexReady) {
      process.stderr.write("phy6252 не дошёл до READY — air без образа нельзя\n");
      app.close();
      process.exitCode = 1;
      return;
    }
    app.markAirLog();
    app.startAir();
    process.stdout.write(`air ${app.ble.airName ?? "DPLS1234"} (phy6252 --raw)\n`);
  }
  if (listen) process.stdout.write(`bench sock ${benchSockPath()}\n`);

  if (!process.stdin.isTTY || !process.stdout.isTTY || listen) {
    if (!air && !listen) {
      process.stderr.write("нужен интерактивный TTY (или --once / --air / --listen)\n");
      app.close();
      process.exitCode = 1;
      return;
    }
    let running = true;
    const finish = () => {
      if (!running) return;
      running = false;
      app.close();
    };
    process.on("SIGINT", finish);
    process.on("SIGTERM", finish);
    while (running) await Bun.sleep(250);
    return;
  }

  enterScreen();
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.setEncoding("utf8");

  let running = true;
  const finish = () => {
    if (!running) return;
    running = false;
    process.stdin.setRawMode(false);
    leaveScreen();
    app.close();
  };
  process.on("SIGINT", finish);
  process.on("SIGTERM", finish);
  process.stdout.on("resize", () => {
    app.dirty = true;
  });

  let escape = "";
  process.stdin.on("data", (chunk: string) => {
    const text = escape + chunk;
    escape = "";
    let i = 0;
    while (i < text.length && running) {
      let key = text[i] ?? "";
      if (key === "\x1b") {
        const seq = text.slice(i, i + 3);
        if (seq.length < 3) {
          escape = text.slice(i);
          break;
        }
        key = seq;
        i += 2;
      }
      if (!handleKey(app, key)) finish();
      i += 1;
    }
  });

  const ui = setInterval(() => {
    if (!running) {
      clearInterval(ui);
      return;
    }
    if (!app.dirty) return;
    app.dirty = false;
    draw(app);
  }, 80);
  app.dirty = true;
  draw(app);

  await new Promise<void>((resolveDone) => {
    const wait = setInterval(() => {
      if (!running) {
        clearInterval(wait);
        resolveDone();
      }
    }, 50);
  });
}

await main();
