import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { BoardPane } from "./BoardPane";
import { FirmwarePane } from "./FirmwarePane";
import { DEFAULT_FIRMWARE } from "./firmware";
import { PhonePane } from "./PhonePane";
import { RosterBar } from "./RosterBar";
import { LabSocket } from "./session";
import { MODE_TITLES, type BoardSnapshot, type RosterDevice, type SimLogLine, type TickSample } from "./types";

const QUICK_LINES = ["SNAPSHOT", "TICK 80", "CONNECT", "DISCONNECT"] as const;

function modeLabel(board: BoardSnapshot): string {
  return MODE_TITLES[board.mode] ?? String(board.mode);
}

function formatSimTime(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const rem = seconds % 60;
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60);
    return `${hours}ч ${String(minutes % 60).padStart(2, "0")}м`;
  }
  return `${minutes}:${String(rem).padStart(2, "0")}`;
}

function StatusBar({
  board,
  focused,
  autoTick,
  onAutoTick,
}: {
  board: BoardSnapshot | null;
  focused: RosterDevice | null;
  autoTick: boolean;
  onAutoTick: (on: boolean) => void;
}) {
  const title = focused?.name ?? board?.name ?? "Test-DPLS lab";
  const fw = focused?.firmware ?? board?.firmware;
  return (
    <header className="status">
      <strong className="status-name">{title}</strong>
      {fw !== undefined && fw !== null ? <span className="pill">FW {fw}</span> : null}
      <span className={`pill${board !== null && board.mode !== 0 ? " warn" : ""}`}>
        {board === null ? (focused?.kind === "ble" ? "реальное" : "режим…") : modeLabel(board)}
      </span>
      <span className={`pill${board?.radio.connected || focused?.linked ? " ok" : ""}`}>
        Link {board?.radio.connected || focused?.linked ? "up" : "down"}
      </span>
      <span className={`pill${board?.auth ? " ok" : ""}`}>Auth {board?.auth ? "session" : "нет"}</span>
      <label className="toggle">
        <input type="checkbox" checked={autoTick} onChange={(e) => onAutoTick(e.target.checked)} />
        TICK {autoTick ? "on" : "off"}
      </label>
      <span className="status-time">
        {board === null ? "—" : `${formatSimTime(board.now_ms)} · ${board.now_ms} ms`}
      </span>
    </header>
  );
}

function CommandBar({ onSend }: { onSend: (text: string) => void }) {
  const [text, setText] = useState("");
  const [history, setHistory] = useState<string[]>([]);
  const [histIdx, setHistIdx] = useState(-1);

  function submit(line: string) {
    const trimmed = line.trim();
    if (trimmed === "") return;
    onSend(trimmed);
    setHistory((prev) => [...prev.slice(-49), trimmed]);
    setText("");
    setHistIdx(-1);
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    submit(text);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
    if (history.length === 0) return;
    event.preventDefault();
    if (event.key === "ArrowUp") {
      const next = histIdx < 0 ? history.length - 1 : Math.max(0, histIdx - 1);
      setHistIdx(next);
      const line = history[next];
      if (line !== undefined) setText(line);
      return;
    }
    if (histIdx < 0) return;
    const next = histIdx + 1;
    if (next >= history.length) {
      setHistIdx(-1);
      setText("");
      return;
    }
    setHistIdx(next);
    const line = history[next];
    if (line !== undefined) setText(line);
  }

  return (
    <form className="cmdbar" onSubmit={onSubmit}>
      <span className="cmd-prompt">&gt;</span>
      <input
        className="cmd-input"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="SNAPSHOT · TICK 80 · CONNECT · FRAME AABB…"
        spellCheck={false}
        autoCapitalize="off"
        autoComplete="off"
        aria-label="команда симулятора"
      />
      <button type="submit" className="primary">
        Отправить
      </button>
      {QUICK_LINES.map((line) => (
        <button key={line} type="button" className="ghost" onClick={() => submit(line)}>
          {line}
        </button>
      ))}
    </form>
  );
}

export function App() {
  const [board, setBoard] = useState<BoardSnapshot | null>(null);
  const [osal, setOsal] = useState("boot");
  const [ticks, setTicks] = useState<TickSample[]>([]);
  const [autoTick, setAutoTick] = useState(true);
  const [logs, setLogs] = useState<SimLogLine[]>([]);
  const [roster, setRoster] = useState<RosterDevice[]>([]);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [firmware, setFirmware] = useState(DEFAULT_FIRMWARE);
  const [bleError, setBleError] = useState<string | null>(null);
  const [scanningBoards, setScanningBoards] = useState(false);
  const focusedIdRef = useRef<string | null>(null);
  const sessionRef = useRef<LabSocket | null>(null);
  const [session] = useState(() => {
    return new LabSocket({
      onBoard: (id, snapshot, turn) => {
        if (focusedIdRef.current === null) focusedIdRef.current = id;
        setFocusedId((current) => current ?? id);
        setBoard((prev) => {
          const focused = focusedIdRef.current;
          if (focused !== null && focused !== id) return prev;
          return snapshot;
        });
        setOsal(turn);
        setTicks((prev) => {
          const sample: TickSample = { now_ms: snapshot.now_ms, osal: turn, led: snapshot.led, inflight: snapshot.radio.inflight };
          const last = prev[prev.length - 1];
          if (last !== undefined && last.now_ms === sample.now_ms) return prev;
          return [...prev.slice(-47), sample];
        });
      },
      onLog: (id, entry) => {
        if (focusedIdRef.current !== null && focusedIdRef.current !== id) return;
        setLogs((prev) => [...prev.slice(-199), entry]);
      },
      onRoster: (devices, focused) => {
        setRoster(devices);
        setFocusedId(focused);
        focusedIdRef.current = focused;
        const row = devices.find((item) => item.id === focused);
        if (devices.some((item) => item.kind === "ble")) setScanningBoards(false);
        if (row?.kind === "ble") {
          setBoard(null);
          setTicks([]);
        }
      },
      onBleConnect: () => undefined,
      onBleDisconnect: () => undefined,
      onBleWrite: () => undefined,
      onError: (message) => {
        setScanningBoards(false);
        setBleError(message);
      },
    });
  });
  sessionRef.current = session;
  focusedIdRef.current = focusedId;

  useEffect(() => {
    session.connect();
  }, [session]);

  const focused = roster.find((item) => item.id === focusedId) ?? roster[0] ?? null;
  const boardForPane = focused?.kind === "ble" ? null : board;

  return (
    <main className="lab">
      <StatusBar
        board={boardForPane}
        focused={focused}
        autoTick={autoTick}
        onAutoTick={(on) => {
          setAutoTick(on);
          session.setAutoTick(on);
        }}
      />
      <RosterBar
        devices={roster}
        firmware={firmware}
        onFirmware={setFirmware}
        onSpawn={() => session.spawn(firmware)}
        onKill={(id) => session.kill(id)}
        onFocus={(id) => {
          setLogs([]);
          setTicks([]);
          setBoard(null);
          focusedIdRef.current = id;
          session.focus(id);
        }}
        onAdvertise={() => {
          const id = focusedIdRef.current;
          if (id === null || !id.startsWith("sim:")) {
            setBleError("выберите симулятор");
            return;
          }
          setBleError(null);
          const on = !roster.find((item) => item.id === id)?.advertising;
          session.advertise(id, on);
        }}
        onScanBoards={() => {
          setBleError(null);
          setScanningBoards(true);
          session.bleScan(true);
        }}
        scanningBoards={scanningBoards}
        bleError={bleError}
      />
      <BoardPane
        board={boardForPane}
        emptyHint={focused?.kind === "ble" ? "Реальная плата на Bluetooth ноутбука — GPIO сима нет, смотрите телефон." : undefined}
        onLab={() => session.setLab("lab")}
        onLine={() => session.setLab("line_power")}
        onShort={(on) => session.setFault("real_short", on)}
        onReserve={(on) => session.setFault("reserve_low", on)}
      />
      <FirmwarePane board={boardForPane} osal={osal} ticks={ticks} logs={logs} />
      <PhonePane devices={roster} />
      <CommandBar onSend={(text) => session.sendLine(text)} />
    </main>
  );
}
