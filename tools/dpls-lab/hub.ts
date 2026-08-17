import { spawn, type Subprocess } from "bun";
import { NativeBle } from "./native-ble";
import { DEFAULT_FIRMWARE } from "./src/firmware";
import {
  logKindForLine,
  parseBoardSnapshot,
  type BoardSnapshot,
  type ClientToServer,
  type RosterDevice,
  type ServerToClient,
} from "./src/types";

export type HubClient = { send(data: string): void };

type SimStdin = {
  write(data: string | Uint8Array): number | Promise<number>;
  flush?: () => number | Promise<number>;
};

type SimInstance = {
  id: string;
  kind: "sim";
  deviceId: number;
  name: string;
  firmware: string;
  proc: Subprocess;
  stdin: SimStdin;
  osal: string;
  snapshotAfterDone: boolean;
  board: BoardSnapshot | null;
};

type BleInstance = {
  id: string;
  kind: "ble";
  deviceId: number | null;
  name: string;
  firmware: string | null;
  rssi: number;
  advStatus: number;
};

const decoder = new TextDecoder();
const FW_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function advStatusFromBoard(board: BoardSnapshot): number {
  let status = 0;
  if (board.real_short === 1) status |= 0x01;
  if (board.power === 1) status |= 0x02;
  if (board.reserve_low === 1) status |= 0x04;
  return status;
}

export class DeviceHub {
  readonly clients = new Set<HubClient>();
  autoTick = true;
  focused: string | null = null;
  linked: string | null = null;
  private readonly sims = new Map<string, SimInstance>();
  private readonly bles = new Map<string, BleInstance>();
  private nextSerial = 1;
  private nextDeviceId = 0x1234;
  private readonly binary: string;
  private readonly radio: NativeBle;
  private airSimId: string | null = null;
  private airSubscribed = false;
  private centralReady = false;

  constructor(binary: string, labRoot: string) {
    this.binary = binary;
    this.radio = new NativeBle(labRoot);
  }

  spawnSim(opts: { firmware?: string; name?: string; deviceId?: number }): string {
    const firmware = normalizeFw(opts.firmware) ?? DEFAULT_FIRMWARE;
    const deviceId = opts.deviceId ?? this.nextDeviceId++;
    if (deviceId >= this.nextDeviceId) this.nextDeviceId = deviceId + 1;
    const id = `sim:${this.nextSerial++}`;
    const args = [this.binary, "--id", `0x${deviceId.toString(16)}`, "--fw", firmware];
    if (opts.name !== undefined && opts.name.trim() !== "") {
      args.push("--name", opts.name.trim().slice(0, 31));
    }
    const proc = spawn(args, { stdin: "pipe", stdout: "pipe", stderr: "pipe" });
    if (proc.stdin === undefined || proc.stdout === undefined) {
      throw new Error("simulator pipes missing");
    }
    const sim: SimInstance = {
      id,
      kind: "sim",
      deviceId,
      name: opts.name?.trim() || defaultName(deviceId),
      firmware,
      proc,
      stdin: proc.stdin,
      osal: "boot",
      snapshotAfterDone: false,
      board: null,
    };
    this.sims.set(id, sim);
    if (this.focused === null) this.focused = id;
    void this.readStdout(sim, proc.stdout);
    this.broadcastRoster();
    return id;
  }

  kill(id: string): void {
    const sim = this.sims.get(id);
    if (sim === undefined) return;
    if (this.linked === id) {
      this.linked = null;
      this.emit({ type: "disconnect", id });
    }
    if (this.airSimId === id) this.stopAdvertise();
    this.write(sim, "QUIT");
    sim.proc.kill();
    this.sims.delete(id);
    if (this.focused === id) this.focused = this.sims.keys().next().value ?? this.bles.keys().next().value ?? null;
    this.broadcastRoster();
  }

  focus(id: string): void {
    if (!this.sims.has(id) && !this.bles.has(id)) return;
    this.focused = id;
    const sim = this.sims.get(id);
    if (sim?.board !== null && sim !== undefined) {
      this.emit({ type: "snapshot", id, board: sim.board, osal: sim.osal });
    }
    this.broadcastRoster();
  }

  handle(raw: string): void {
    const message = parseClient(raw);
    if (message === null) return;
    switch (message.type) {
      case "spawn":
        this.spawnSim({ firmware: message.firmware, name: message.name, deviceId: message.deviceId });
        break;
      case "kill":
        this.kill(message.id);
        break;
      case "focus":
        this.focus(message.id);
        break;
      case "scan":
        this.broadcastRoster();
        this.broadcastDiscovered();
        this.startBleScan();
        break;
      case "advertise":
        if (message.on) this.startAdvertise(message.id);
        else if (this.airSimId === message.id) this.stopAdvertise();
        break;
      case "ble_scan":
        if (message.on) this.startBleScan();
        else this.radio.writeCentral("STOP");
        break;
      case "connect": {
        const address = message.address ?? this.focused;
        if (address === null) break;
        this.connectPhone(address);
        break;
      }
      case "disconnect":
        this.disconnectPhone();
        break;
      case "frame":
        this.frameToLinked(message.hex);
        break;
      case "tick":
        this.commandFocused(`TICK ${message.ms}`, `TICK ${message.ms} ms`);
        break;
      case "auto_tick":
        this.autoTick = message.on;
        break;
      case "lab":
        this.commandFocused("LAB", "lab USB/reserve", true);
        break;
      case "line_power":
        this.commandFocused("POWER LINE", "line power", true);
        break;
      case "real_short":
        this.commandFocused(`REAL_SHORT ${message.on ? 1 : 0}`, "real short", true);
        break;
      case "reserve_low":
        this.commandFocused(`RESERVE_LOW ${message.on ? 1 : 0}`, "reserve low", true);
        break;
      case "snapshot":
        this.commandFocused("SNAPSHOT", this.focusedSim()?.osal ?? "snapshot");
        break;
      case "line": {
        const text = message.text.replace(/\r/g, "").split("\n")[0]?.trim() ?? "";
        if (text.length === 0 || text.length > 2048) break;
        this.commandFocused(text, text, true);
        break;
      }
      case "ble_up":
        this.bleUp(message);
        break;
      case "ble_down":
        this.bles.delete(bleId(message.address));
        if (this.focused === bleId(message.address)) this.focused = this.sims.keys().next().value ?? null;
        if (this.linked === bleId(message.address)) {
          this.linked = null;
          this.emit({ type: "disconnect", id: bleId(message.address) });
        }
        this.broadcastRoster();
        break;
      case "ble_rx":
        if (this.linked === bleId(message.address)) {
          this.emit({ type: "tx", id: bleId(message.address), hex: message.hex });
        }
        break;
      case "ble_closed":
        if (this.linked === bleId(message.address)) {
          this.linked = null;
          this.emit({ type: "disconnect", id: bleId(message.address) });
        }
        this.broadcastRoster();
        break;
    }
  }

  tickAll(): void {
    if (!this.autoTick) return;
    if (this.clients.size === 0 && !this.airSubscribed) return;
    for (const sim of this.sims.values()) {
      this.command(sim, "TICK 80", "OSAL TICK 80 ms + TX pace");
    }
  }

  onClientJoin(): void {
    this.broadcastRoster();
    this.broadcastDiscovered();
    const sim = this.focusedSim();
    if (sim !== undefined) this.command(sim, "SNAPSHOT", "client join");
  }

  onLastClientLeft(): void {
    this.disconnectPhone();
  }

  startAdvertise(id: string): void {
    const sim = this.sims.get(id);
    if (sim === undefined) {
      this.emit({ type: "error", message: "нет симулятора для BLE-сервера" });
      return;
    }
    if (!this.radio.available()) {
      this.emit({ type: "error", message: "нет native/dpls-ble — соберите tools/dpls-lab/native" });
      return;
    }
    if (this.linked?.startsWith("ble:")) this.disconnectPhone();
    this.radio.stopCentral();
    this.centralReady = false;
    const status = sim.board === null ? 0 : advStatusFromBoard(sim.board);
    this.airSimId = id;
    this.airSubscribed = false;
    const ok = this.radio.startPeripheral(
      { name: sim.board?.name ?? sim.name, deviceId: sim.deviceId, firmware: sim.firmware, status },
      (line) => this.onAirLine(id, line),
    );
    if (!ok) {
      this.airSimId = null;
      this.emit({ type: "error", message: "не удалось запустить BLE-сервер" });
      return;
    }
    this.emit({ type: "log", id, kind: "out", line: `BLE server ${sim.name} fw ${sim.firmware}` });
    this.broadcastRoster();
  }

  stopAdvertise(): void {
    if (this.airSimId === null) return;
    const id = this.airSimId;
    if (this.airSubscribed) {
      const sim = this.sims.get(id);
      if (sim !== undefined && this.linked !== id) this.command(sim, "DISCONNECT", "BLE server drop");
    }
    this.radio.stopPeripheral();
    this.airSimId = null;
    this.airSubscribed = false;
    this.broadcastRoster();
  }

  startBleScan(): void {
    if (this.airSimId !== null) this.stopAdvertise();
    if (!this.ensureCentral()) {
      this.emit({ type: "error", message: "нет native/dpls-ble — соберите tools/dpls-lab/native" });
      return;
    }
    this.radio.writeCentral("SCAN");
  }

  private ensureCentral(): boolean {
    if (!this.radio.available()) return false;
    return this.radio.startCentral((line) => this.onCentralLine(line));
  }

  private onAirLine(simId: string, line: string): void {
    const sim = this.sims.get(simId);
    if (line === "READY") {
      this.emit({ type: "log", id: simId, kind: "out", line: "BLE server advertising" });
      return;
    }
    if (line === "CONNECTED") {
      if (this.linked === simId) this.disconnectPhone();
      if (sim !== undefined) this.command(sim, "CONNECT", "BLE central connect");
      return;
    }
    if (line === "SUBSCRIBED") {
      this.airSubscribed = true;
      if (sim !== undefined) this.command(sim, "CCCD 3", "BLE CCCD notify");
      this.broadcastRoster();
      return;
    }
    if (line === "DISCONNECTED") {
      this.airSubscribed = false;
      if (sim !== undefined && this.linked !== simId) this.command(sim, "DISCONNECT", "BLE central drop");
      this.broadcastRoster();
      return;
    }
    if (line.startsWith("RX ") && sim !== undefined) {
      this.command(sim, `FRAME ${line.slice(3).trim()}`, "BLE RX");
      return;
    }
    if (line.startsWith("ERROR ")) {
      this.emit({ type: "error", message: line.slice(6) });
      this.emit({ type: "log", id: simId, kind: "err", line });
    }
  }

  private onCentralLine(line: string): void {
    if (line === "READY") {
      this.centralReady = true;
      this.radio.writeCentral("SCAN");
      return;
    }
    if (line.startsWith("ADV ")) {
      try {
        const parsed: unknown = JSON.parse(line.slice(4));
        if (!isRecord(parsed) || typeof parsed.id !== "string" || typeof parsed.name !== "string") return;
        const rssi = typeof parsed.rssi === "number" ? parsed.rssi : -80;
        const deviceId = typeof parsed.deviceId === "number" ? parsed.deviceId : null;
        const firmware = typeof parsed.firmware === "string" ? parsed.firmware : null;
        const status = typeof parsed.status === "number" ? parsed.status : 0;
        this.bleUp({
          type: "ble_up",
          address: parsed.id,
          name: parsed.name,
          deviceId,
          firmware,
          rssi,
          advStatus: status,
        });
      } catch {
        return;
      }
      return;
    }
    if (line.startsWith("CONNECTED ")) {
      const id = bleId(line.slice(10).trim());
      this.linked = id;
      this.broadcastRoster();
      return;
    }
    if (line === "SUBSCRIBED") {
      if (this.linked !== null) this.emit({ type: "subscribed", id: this.linked });
      return;
    }
    if (line.startsWith("TX ") && this.linked !== null) {
      this.emit({ type: "tx", id: this.linked, hex: line.slice(3).trim() });
      return;
    }
    if (line === "DISCONNECTED") {
      if (this.linked?.startsWith("ble:")) {
        const id = this.linked;
        this.linked = null;
        this.emit({ type: "disconnect", id });
        this.broadcastRoster();
      }
      return;
    }
    if (line.startsWith("ERROR ")) {
      this.emit({ type: "error", message: line.slice(6) });
      if (this.linked?.startsWith("ble:")) {
        const id = this.linked;
        if (line.includes("unknown-peripheral") || line.includes("no-service") || line.includes("connect-failed")) {
          this.linked = null;
          this.emit({ type: "disconnect", id });
          this.broadcastRoster();
        }
      }
    }
  }

  private connectPhone(address: string): void {
    if (this.linked !== null && this.linked !== address) this.disconnectPhone();
    this.linked = address;
    const sim = this.sims.get(address);
    if (sim !== undefined) {
      if (this.airSimId === address && this.airSubscribed) {
        this.emit({ type: "log", id: address, kind: "out", line: "эфир: отключаем BLE-сервер, подключается лаба" });
        this.stopAdvertise();
      }
      this.command(sim, "CONNECT", "GAP connect");
      this.command(sim, "CCCD 3", "CCCD 0x03 notify");
      this.emit({ type: "subscribed", id: address });
      this.broadcastRoster();
      return;
    }
    if (address.startsWith("ble:")) {
      this.stopAdvertise();
      this.ensureCentral();
      this.radio.writeCentral(`CONNECT ${address.slice("ble:".length)}`);
      this.broadcastRoster();
    }
  }

  private disconnectPhone(): void {
    const linked = this.linked;
    if (linked === null) return;
    this.linked = null;
    const sim = this.sims.get(linked);
    if (sim !== undefined) this.command(sim, "DISCONNECT", "GAP disconnect");
    if (linked.startsWith("ble:")) {
      this.radio.writeCentral("DISCONNECT");
      this.emit({ type: "ble_disconnect", address: linked.slice("ble:".length) });
    }
    this.broadcastRoster();
  }

  private frameToLinked(hex: string): void {
    const linked = this.linked;
    if (linked === null) return;
    const sim = this.sims.get(linked);
    if (sim !== undefined) {
      this.command(sim, `FRAME ${hex}`, "OSAL RX then TX");
      return;
    }
    if (linked.startsWith("ble:")) {
      this.radio.writeCentral(`WRITE ${hex}`);
    }
  }

  private commandFocused(line: string, osal: string, echo = false): void {
    const sim = this.focusedSim();
    if (sim === undefined) return;
    this.command(sim, line, osal, echo);
  }

  private focusedSim(): SimInstance | undefined {
    if (this.focused === null) return undefined;
    return this.sims.get(this.focused);
  }

  private command(sim: SimInstance, line: string, osal: string, echo = false): void {
    sim.osal = osal;
    sim.snapshotAfterDone = !line.startsWith("SNAPSHOT");
    if (echo) this.emit({ type: "log", id: sim.id, kind: "cmd", line: `> ${line}` });
    this.write(sim, line);
  }

  private write(sim: SimInstance, line: string): void {
    sim.stdin.write(`${line}\n`);
    sim.stdin.flush?.();
  }

  private bleUp(message: Extract<ClientToServer, { type: "ble_up" }>): void {
    const id = bleId(message.address);
    this.bles.set(id, {
      id,
      kind: "ble",
      deviceId: message.deviceId,
      name: message.name,
      firmware: message.firmware,
      rssi: message.rssi,
      advStatus: message.advStatus,
    });
    this.broadcastRoster();
    this.emitDiscovered({
      id,
      kind: "ble",
      name: message.name,
      deviceId: message.deviceId,
      firmware: message.firmware,
      rssi: message.rssi,
      advStatus: message.advStatus,
      focused: this.focused === id,
      linked: this.linked === id,
      advertising: false,
    });
  }

  private emit(message: ServerToClient): void {
    const text = JSON.stringify(message);
    for (const client of this.clients) {
      try {
        client.send(text);
      } catch {
        this.clients.delete(client);
      }
    }
  }

  private roster(): RosterDevice[] {
    const rows: RosterDevice[] = [];
    for (const sim of this.sims.values()) {
      rows.push({
        id: sim.id,
        kind: "sim",
        name: sim.board?.name ?? sim.name,
        deviceId: sim.deviceId,
        firmware: sim.board?.firmware ?? sim.firmware,
        rssi: -42,
        advStatus: sim.board === null ? 0 : advStatusFromBoard(sim.board),
        focused: this.focused === sim.id,
        linked: this.linked === sim.id,
        advertising: this.airSimId === sim.id,
      });
    }
    for (const ble of this.bles.values()) {
      rows.push({
        id: ble.id,
        kind: "ble",
        name: ble.name,
        deviceId: ble.deviceId,
        firmware: ble.firmware,
        rssi: ble.rssi,
        advStatus: ble.advStatus,
        focused: this.focused === ble.id,
        linked: this.linked === ble.id,
        advertising: false,
      });
    }
    return rows;
  }

  private broadcastRoster(): void {
    this.emit({ type: "roster", focused: this.focused, linked: this.linked, devices: this.roster() });
  }

  private broadcastDiscovered(): void {
    for (const row of this.roster()) this.emitDiscovered(row);
  }

  private emitDiscovered(row: RosterDevice): void {
    this.emit({
      type: "discovered",
      address: row.id,
      name: row.name,
      deviceId: row.deviceId,
      firmware: row.firmware,
      rssi: row.rssi,
      advStatus: row.advStatus,
      kind: row.kind,
    });
  }

  private async readStdout(sim: SimInstance, stdout: ReadableStream<Uint8Array>): Promise<void> {
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
        this.onSimLine(sim, line);
        nl = buffer.indexOf("\n");
      }
    }
    this.sims.delete(sim.id);
    if (this.focused === sim.id) this.focused = this.sims.keys().next().value ?? null;
    if (this.linked === sim.id) {
      this.linked = null;
      this.emit({ type: "disconnect", id: sim.id });
    }
    this.broadcastRoster();
  }

  private onSimLine(sim: SimInstance, line: string): void {
    if (line === "READY DPLS2") {
      this.emit({ type: "ready" });
      this.emit({ type: "log", id: sim.id, kind: "out", line });
      this.command(sim, "SNAPSHOT", "boot");
      return;
    }
    if (line === "DONE") {
      if (sim.snapshotAfterDone) {
        sim.snapshotAfterDone = false;
        this.write(sim, "SNAPSHOT");
      }
      return;
    }
    if (line.startsWith("SNAPSHOT ")) {
      try {
        const board = parseBoardSnapshot(JSON.parse(line.slice(9)));
        if (board === null) return;
        sim.board = board;
        sim.name = board.name;
        sim.firmware = board.firmware;
        sim.deviceId = board.device_id;
        this.emit({ type: "snapshot", id: sim.id, board, osal: sim.osal });
        this.emitDiscovered({
          id: sim.id,
          kind: "sim",
          name: board.name,
          deviceId: board.device_id,
          firmware: board.firmware,
          rssi: -42,
          advStatus: advStatusFromBoard(board),
          focused: this.focused === sim.id,
          linked: this.linked === sim.id,
          advertising: this.airSimId === sim.id,
        });
        this.broadcastRoster();
      } catch {
        return;
      }
      return;
    }
    if (line.startsWith("LED ")) return;
    this.emit({ type: "log", id: sim.id, kind: logKindForLine(line), line });
    if (line.startsWith("TX ")) {
      const hex = line.slice(3).trim();
      if (this.linked === sim.id) this.emit({ type: "tx", id: sim.id, hex });
      if (this.airSimId === sim.id && this.airSubscribed) this.radio.writePeripheral(`TX ${hex}`);
      return;
    }
    if (line === "DISCONNECT") {
      if (this.linked === sim.id) {
        this.linked = null;
        this.emit({ type: "disconnect", id: sim.id });
        this.broadcastRoster();
      }
      return;
    }
    if (line.startsWith("JOURNAL ")) {
      const seq = /seq=(\d+)/.exec(line);
      const eventType = /type=(\d+)/.exec(line);
      const param = /param=(\d+)/.exec(line);
      if (seq && eventType && param) {
        this.emit({
          type: "journal",
          id: sim.id,
          seq: Number(seq[1]),
          eventType: Number(eventType[1]),
          param: Number(param[1]),
        });
      }
    }
  }

  shutdown(): void {
    this.disconnectPhone();
    this.stopAdvertise();
    this.radio.stopCentral();
    for (const id of [...this.sims.keys()]) {
      this.kill(id);
    }
  }
}

function defaultName(deviceId: number): string {
  return `Test-DPLS-${(deviceId & 0xffff).toString(16).toUpperCase().padStart(4, "0")}`;
}

function bleId(address: string): string {
  return address.startsWith("ble:") ? address : `ble:${address}`;
}

function normalizeFw(value: string | undefined): string | null {
  if (value === undefined) return null;
  const match = FW_RE.exec(value.trim());
  if (match === null) return null;
  return `${Number(match[1])}.${Number(match[2])}.${Number(match[3])}`;
}

export function parseClient(raw: string): ClientToServer | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!isRecord(parsed) || typeof parsed.type !== "string") return null;
    const type = parsed.type;
    if (type === "disconnect" || type === "lab" || type === "line_power" || type === "snapshot" || type === "scan") {
      return { type };
    }
    if (type === "connect") {
      return typeof parsed.address === "string" ? { type, address: parsed.address } : { type };
    }
    if (type === "frame" && typeof parsed.hex === "string") return { type, hex: parsed.hex };
    if (type === "tick" && typeof parsed.ms === "number") return { type, ms: parsed.ms };
    if (type === "auto_tick" && typeof parsed.on === "boolean") return { type, on: parsed.on };
    if ((type === "real_short" || type === "reserve_low") && typeof parsed.on === "boolean") {
      return { type, on: parsed.on };
    }
    if (type === "line" && typeof parsed.text === "string") return { type, text: parsed.text };
    if (type === "spawn") {
      const firmware = typeof parsed.firmware === "string" ? parsed.firmware : undefined;
      const name = typeof parsed.name === "string" ? parsed.name : undefined;
      const deviceId = typeof parsed.deviceId === "number" ? parsed.deviceId : undefined;
      return { type, firmware, name, deviceId };
    }
    if (type === "kill" && typeof parsed.id === "string") return { type, id: parsed.id };
    if (type === "focus" && typeof parsed.id === "string") return { type, id: parsed.id };
    if (type === "advertise" && typeof parsed.id === "string" && typeof parsed.on === "boolean") {
      return { type, id: parsed.id, on: parsed.on };
    }
    if (type === "ble_scan" && typeof parsed.on === "boolean") return { type, on: parsed.on };
    if (type === "ble_up" && typeof parsed.address === "string" && typeof parsed.name === "string") {
      const deviceId = parsed.deviceId === null ? null : typeof parsed.deviceId === "number" ? parsed.deviceId : null;
      const firmware = parsed.firmware === null || parsed.firmware === undefined
        ? null
        : typeof parsed.firmware === "string"
          ? parsed.firmware
          : null;
      const rssi = typeof parsed.rssi === "number" ? parsed.rssi : -60;
      const advStatus = typeof parsed.advStatus === "number" ? parsed.advStatus : 0;
      return { type, address: parsed.address, name: parsed.name, deviceId, firmware, rssi, advStatus };
    }
    if ((type === "ble_down" || type === "ble_closed") && typeof parsed.address === "string") {
      return { type, address: parsed.address };
    }
    if (type === "ble_rx" && typeof parsed.address === "string" && typeof parsed.hex === "string") {
      return { type, address: parsed.address, hex: parsed.hex };
    }
    return null;
  } catch {
    return null;
  }
}
