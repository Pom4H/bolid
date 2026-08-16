import { DEFAULT_FIRMWARE } from "./firmware";

export type RadioSnapshot = {
  connected: number;
  cccd: number;
  notify: number;
  rx: number;
  tx: number;
  inflight: number;
  inflight_since_ms: number;
  att_sent: number;
  snv_dirty: number;
  stack_bytes: number;
  notify_pace_ms: number;
  indicate_timeout_ms: number;
};

export type GpioSnapshot = {
  iso1: number;
  iso2: number;
  isoT: number;
  kz1: number;
  kz2: number;
  kzT: number;
  ledR: number;
  ledG: number;
  ledB: number;
};

export type BoardSnapshot = {
  now_ms: number;
  connected: number;
  auth: number;
  encrypted: number;
  mode: number;
  power: number;
  reserve_low: number;
  real_short: number;
  identify: number;
  led: number;
  line_mv: number;
  port2_mv: number;
  port_t_mv: number;
  reserve_mv: number;
  events: number;
  name: string;
  device_id: number;
  firmware: string;
  gpio: GpioSnapshot;
  radio: RadioSnapshot;
};

export type DeviceKind = "sim" | "ble";

export type RosterDevice = {
  id: string;
  kind: DeviceKind;
  name: string;
  deviceId: number | null;
  firmware: string | null;
  rssi: number;
  advStatus: number;
  focused: boolean;
  linked: boolean;
  advertising: boolean;
};

export type TickSample = {
  now_ms: number;
  osal: string;
  led: number;
  inflight: number;
};

export type SimLogKind = "cmd" | "out" | "tx" | "journal" | "mode" | "led" | "state" | "snap" | "err";

export type SimLogLine = {
  t: number;
  kind: SimLogKind;
  line: string;
};

export type ServerToClient =
  | { type: "ready" }
  | { type: "snapshot"; id: string; board: BoardSnapshot; osal: string }
  | { type: "tx"; id: string; hex: string }
  | { type: "disconnect"; id?: string }
  | { type: "journal"; id: string; seq: number; eventType: number; param: number }
  | { type: "log"; id: string; kind: SimLogKind; line: string }
  | { type: "error"; message: string }
  | { type: "roster"; focused: string | null; linked: string | null; devices: RosterDevice[] }
  | { type: "discovered"; address: string; name: string; deviceId: number | null; firmware: string | null; rssi: number; advStatus: number; kind: DeviceKind }
  | { type: "ble_connect"; address: string }
  | { type: "ble_disconnect"; address: string }
  | { type: "ble_write"; address: string; hex: string }
  | { type: "subscribed"; id: string };

export type ClientToServer =
  | { type: "connect"; address?: string }
  | { type: "disconnect" }
  | { type: "frame"; hex: string }
  | { type: "tick"; ms: number }
  | { type: "auto_tick"; on: boolean }
  | { type: "lab" }
  | { type: "line_power" }
  | { type: "real_short"; on: boolean }
  | { type: "reserve_low"; on: boolean }
  | { type: "snapshot" }
  | { type: "line"; text: string }
  | { type: "scan" }
  | { type: "spawn"; firmware?: string; name?: string; deviceId?: number }
  | { type: "kill"; id: string }
  | { type: "focus"; id: string }
  | { type: "ble_up"; address: string; name: string; deviceId: number | null; firmware: string | null; rssi: number; advStatus: number }
  | { type: "ble_down"; address: string }
  | { type: "ble_rx"; address: string; hex: string }
  | { type: "ble_closed"; address: string }
  | { type: "advertise"; id: string; on: boolean }
  | { type: "ble_scan"; on: boolean };

export const MODE_TITLES = [
  "Норма",
  "Обрыв +Т",
  "Обрыв магистрали",
  "КЗ +1",
  "КЗ +2",
  "КЗ +Т",
] as const;

export const MSG = {
  HELLO: 0x01,
  AUTH_CHALLENGE: 0x02,
  AUTH_PROOF: 0x03,
  AUTH_RESULT: 0x04,
  DEVICE_INFO_GET: 0x06,
  DEVICE_INFO_REPORT: 0x07,
  TIME_SYNC: 0x0b,
  STATE_GET: 0x10,
  STATE_REPORT: 0x11,
  MODE_SET: 0x12,
  COMMAND_RESULT: 0x13,
  IDENTIFY_START: 0x14,
  IDENTIFY_STOP: 0x15,
  KEEP_ALIVE: 0x30,
} as const;

export const FLAG_REQUEST = 1;
export const FLAG_RESPONSE = 2;
export const PROTOCOL_VERSION = 2;

function parseOptionalId(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value === "string") return value;
  return undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export function parseRadioSnapshot(value: unknown): RadioSnapshot | null {
  if (!isRecord(value)) return null;
  const connected = num(value.connected);
  const cccd = num(value.cccd);
  const notify = num(value.notify);
  const rx = num(value.rx);
  const tx = num(value.tx);
  const inflight = num(value.inflight);
  const inflightSince = num(value.inflight_since_ms);
  const attSent = num(value.att_sent);
  const snvDirty = num(value.snv_dirty);
  const stackBytes = num(value.stack_bytes);
  const notifyPace = num(value.notify_pace_ms);
  const indicateTimeout = num(value.indicate_timeout_ms);
  if (
    connected === null ||
    cccd === null ||
    notify === null ||
    rx === null ||
    tx === null ||
    inflight === null ||
    inflightSince === null ||
    attSent === null ||
    snvDirty === null ||
    stackBytes === null ||
    notifyPace === null ||
    indicateTimeout === null
  ) {
    return null;
  }
  return {
    connected,
    cccd,
    notify,
    rx,
    tx,
    inflight,
    inflight_since_ms: inflightSince,
    att_sent: attSent,
    snv_dirty: snvDirty,
    stack_bytes: stackBytes,
    notify_pace_ms: notifyPace,
    indicate_timeout_ms: indicateTimeout,
  };
}

export function parseGpioSnapshot(value: unknown): GpioSnapshot | null {
  if (!isRecord(value)) return null;
  const iso1 = num(value.iso1);
  const iso2 = num(value.iso2);
  const isoT = num(value.isoT);
  const kz1 = num(value.kz1);
  const kz2 = num(value.kz2);
  const kzT = num(value.kzT);
  const ledR = num(value.ledR);
  const ledG = num(value.ledG);
  const ledB = num(value.ledB);
  if (
    iso1 === null ||
    iso2 === null ||
    isoT === null ||
    kz1 === null ||
    kz2 === null ||
    kzT === null ||
    ledR === null ||
    ledG === null ||
    ledB === null
  ) {
    return null;
  }
  return { iso1, iso2, isoT, kz1, kz2, kzT, ledR, ledG, ledB };
}

export function parseBoardSnapshot(value: unknown): BoardSnapshot | null {
  if (!isRecord(value) || typeof value.name !== "string") return null;
  const radio = parseRadioSnapshot(value.radio);
  const gpio = parseGpioSnapshot(value.gpio);
  const now = num(value.now_ms);
  const connected = num(value.connected);
  const auth = num(value.auth);
  const encrypted = num(value.encrypted);
  const mode = num(value.mode);
  const power = num(value.power);
  const reserveLow = num(value.reserve_low);
  const realShort = num(value.real_short);
  const identify = num(value.identify);
  const led = num(value.led);
  const lineMv = num(value.line_mv);
  const port2Mv = num(value.port2_mv);
  const portTMv = num(value.port_t_mv);
  const reserveMv = num(value.reserve_mv);
  const events = num(value.events);
  const deviceId = num(value.device_id) ?? 0x1234;
  const firmware = typeof value.fw === "string" && value.fw.length > 0 ? value.fw : DEFAULT_FIRMWARE;
  if (
    radio === null ||
    now === null ||
    connected === null ||
    auth === null ||
    encrypted === null ||
    mode === null ||
    power === null ||
    reserveLow === null ||
    realShort === null ||
    identify === null ||
    led === null ||
    lineMv === null ||
    port2Mv === null ||
    portTMv === null ||
    reserveMv === null ||
    events === null ||
    gpio === null
  ) {
    return null;
  }
  return {
    now_ms: now,
    connected,
    auth,
    encrypted,
    mode,
    power,
    reserve_low: reserveLow,
    real_short: realShort,
    identify,
    led,
    line_mv: lineMv,
    port2_mv: port2Mv,
    port_t_mv: portTMv,
    reserve_mv: reserveMv,
    events,
    name: value.name,
    device_id: deviceId,
    firmware,
    gpio,
    radio,
  };
}

export function parseRosterDevice(value: unknown): RosterDevice | null {
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.name !== "string") return null;
  if (value.kind !== "sim" && value.kind !== "ble") return null;
  const deviceId = value.deviceId === null ? null : num(value.deviceId);
  if (value.deviceId !== null && deviceId === null) return null;
  const rssi = num(value.rssi);
  const advStatus = num(value.advStatus);
  if (rssi === null || advStatus === null) return null;
  if (typeof value.focused !== "boolean" || typeof value.linked !== "boolean") return null;
  const advertising = typeof value.advertising === "boolean" ? value.advertising : false;
  const firmware = value.firmware === null ? null : typeof value.firmware === "string" ? value.firmware : null;
  if (value.firmware !== null && value.firmware !== undefined && firmware === null) return null;
  return {
    id: value.id,
    kind: value.kind,
    name: value.name,
    deviceId,
    firmware,
    rssi,
    advStatus,
    focused: value.focused,
    linked: value.linked,
    advertising,
  };
}

export function parseServerToClient(value: unknown): ServerToClient | null {
  if (!isRecord(value) || typeof value.type !== "string") return null;
  if (value.type === "ready") return { type: "ready" };
  if (value.type === "disconnect") {
    return typeof value.id === "string" ? { type: "disconnect", id: value.id } : { type: "disconnect" };
  }
  if (value.type === "tx" && typeof value.hex === "string" && typeof value.id === "string") {
    return { type: "tx", id: value.id, hex: value.hex };
  }
  if (value.type === "error" && typeof value.message === "string") {
    return { type: "error", message: value.message };
  }
  if (value.type === "snapshot" && typeof value.osal === "string" && typeof value.id === "string") {
    const board = parseBoardSnapshot(value.board);
    if (board === null) return null;
    return { type: "snapshot", id: value.id, board, osal: value.osal };
  }
  if (value.type === "journal" && typeof value.id === "string") {
    const seq = num(value.seq);
    const eventType = num(value.eventType);
    const param = num(value.param);
    if (seq === null || eventType === null || param === null) return null;
    return { type: "journal", id: value.id, seq, eventType, param };
  }
  if (value.type === "log" && typeof value.line === "string" && typeof value.kind === "string" && typeof value.id === "string") {
    const kind = parseLogKind(value.kind);
    if (kind === null) return null;
    return { type: "log", id: value.id, kind, line: value.line };
  }
  if (value.type === "roster" && Array.isArray(value.devices)) {
    const focused = parseOptionalId(value.focused);
    const linked = parseOptionalId(value.linked);
    if (focused === undefined || linked === undefined) return null;
    const devices: RosterDevice[] = [];
    for (const item of value.devices) {
      const parsed = parseRosterDevice(item);
      if (parsed === null) return null;
      devices.push(parsed);
    }
    return { type: "roster", focused, linked, devices };
  }
  if (value.type === "discovered" && typeof value.address === "string" && typeof value.name === "string") {
    if (value.kind !== "sim" && value.kind !== "ble") return null;
    const deviceId = value.deviceId === null ? null : num(value.deviceId);
    if (value.deviceId !== null && deviceId === null) return null;
    const rssi = num(value.rssi);
    const advStatus = num(value.advStatus);
    if (rssi === null || advStatus === null) return null;
    const firmware = value.firmware === null || value.firmware === undefined
      ? null
      : typeof value.firmware === "string"
        ? value.firmware
        : null;
    if (value.firmware !== null && value.firmware !== undefined && firmware === null) return null;
    return {
      type: "discovered",
      address: value.address,
      name: value.name,
      deviceId,
      firmware,
      rssi,
      advStatus,
      kind: value.kind,
    };
  }
  if (value.type === "ble_connect" && typeof value.address === "string") return { type: "ble_connect", address: value.address };
  if (value.type === "ble_disconnect" && typeof value.address === "string") {
    return { type: "ble_disconnect", address: value.address };
  }
  if (value.type === "ble_write" && typeof value.address === "string" && typeof value.hex === "string") {
    return { type: "ble_write", address: value.address, hex: value.hex };
  }
  if (value.type === "subscribed" && typeof value.id === "string") return { type: "subscribed", id: value.id };
  return null;
}

export function parseLogKind(value: string): SimLogKind | null {
  if (
    value === "cmd" ||
    value === "out" ||
    value === "tx" ||
    value === "journal" ||
    value === "mode" ||
    value === "led" ||
    value === "state" ||
    value === "snap" ||
    value === "err"
  ) {
    return value;
  }
  return null;
}

export function logKindForLine(line: string): SimLogKind {
  if (line.startsWith("TX ")) return "tx";
  if (line.startsWith("JOURNAL ")) return "journal";
  if (line.startsWith("MODE ")) return "mode";
  if (line.startsWith("LED ")) return "led";
  if (line.startsWith("STATE ")) return "state";
  if (line.startsWith("SNAPSHOT ")) return "snap";
  if (line.startsWith("ERROR ")) return "err";
  return "out";
}
