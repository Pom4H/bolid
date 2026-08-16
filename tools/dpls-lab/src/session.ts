import {
  parseServerToClient,
  type BoardSnapshot,
  type ClientToServer,
  type RosterDevice,
  type SimLogLine,
} from "./types";

type Handlers = {
  onBoard: (id: string, board: BoardSnapshot, osal: string) => void;
  onLog: (id: string, entry: SimLogLine) => void;
  onRoster: (devices: RosterDevice[], focused: string | null, linked: string | null) => void;
  onBleConnect: (address: string) => void;
  onBleDisconnect: (address: string) => void;
  onBleWrite: (address: string, hex: string) => void;
  onError: (message: string) => void;
};

/** Board/firmware lab socket. Phone UI is Compose wasm on /phone/, same hub. */
export class LabSocket {
  private socket: WebSocket | null = null;
  private queued: ClientToServer[] = [];
  private readonly handlers: Handlers;

  constructor(handlers: Handlers) {
    this.handlers = handlers;
  }

  connect(): void {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    this.socket = new WebSocket(`${proto}://${location.host}/ws`);
    this.socket.addEventListener("open", () => {
      for (const message of this.queued) this.socket?.send(JSON.stringify(message));
      this.queued = [];
    });
    this.socket.addEventListener("message", (event) => {
      if (typeof event.data !== "string") return;
      try {
        const message = parseServerToClient(JSON.parse(event.data));
        if (message === null) return;
        if (message.type === "snapshot") this.handlers.onBoard(message.id, message.board, message.osal);
        if (message.type === "log") {
          this.handlers.onLog(message.id, { t: Date.now(), kind: message.kind, line: message.line });
        }
        if (message.type === "roster") this.handlers.onRoster(message.devices, message.focused, message.linked);
        if (message.type === "ble_connect") this.handlers.onBleConnect(message.address);
        if (message.type === "ble_disconnect") this.handlers.onBleDisconnect(message.address);
        if (message.type === "ble_write") this.handlers.onBleWrite(message.address, message.hex);
        if (message.type === "error") this.handlers.onError(message.message);
      } catch {
        return;
      }
    });
  }

  setLab(kind: "lab" | "line_power"): void {
    this.send({ type: kind });
  }

  setFault(kind: "real_short" | "reserve_low", on: boolean): void {
    this.send({ type: kind, on });
  }

  setAutoTick(on: boolean): void {
    this.send({ type: "auto_tick", on });
  }

  sendLine(text: string): void {
    this.send({ type: "line", text });
  }

  spawn(firmware: string, name?: string): void {
    this.send({ type: "spawn", firmware, name });
  }

  kill(id: string): void {
    this.send({ type: "kill", id });
  }

  focus(id: string): void {
    this.send({ type: "focus", id });
  }

  advertise(id: string, on: boolean): void {
    this.send({ type: "advertise", id, on });
  }

  bleScan(on: boolean): void {
    this.send({ type: "ble_scan", on });
  }

  bleUp(payload: {
    address: string;
    name: string;
    deviceId: number | null;
    firmware: string | null;
    rssi: number;
    advStatus: number;
  }): void {
    this.send({ type: "ble_up", ...payload });
  }

  bleRx(address: string, hex: string): void {
    this.send({ type: "ble_rx", address, hex });
  }

  bleClosed(address: string): void {
    this.send({ type: "ble_closed", address });
  }

  private send(message: ClientToServer): void {
    if (this.socket === null || this.socket.readyState !== WebSocket.OPEN) {
      this.queued.push(message);
      return;
    }
    this.socket.send(JSON.stringify(message));
  }
}
