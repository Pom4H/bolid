import { useEffect, useRef } from "react";
import type { BoardSnapshot, SimLogLine, TickSample } from "./types";

type Props = {
  board: BoardSnapshot | null;
  osal: string;
  ticks: TickSample[];
  logs: SimLogLine[];
};

function SlotRow({ count, depth, inflight }: { count: number; depth: number; inflight: boolean }) {
  return (
    <div className="slots">
      {Array.from({ length: depth }, (_, index) => {
        const filled = index < count;
        const flying = inflight && index === 0 && filled;
        return <span key={index} className={`slot${filled ? " filled" : ""}${flying ? " fly" : ""}`} />;
      })}
    </div>
  );
}

function SimLog({ logs }: { logs: SimLogLine[] }) {
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    const node = ref.current;
    if (node !== null) node.scrollTop = node.scrollHeight;
  }, [logs]);
  return (
    <pre className="fw-log" ref={ref}>
      {logs.length === 0
        ? "stdout симулятора — стенд, TX и команды появятся здесь."
        : logs.map((entry, index) => (
            <span key={index} className={`log-${entry.kind}`}>
              {entry.line}
              {"\n"}
            </span>
          ))}
    </pre>
  );
}

export function FirmwarePane(props: Props) {
  const board = props.board;
  if (board === null) {
    return (
      <section className="pane firmware">
        <p className="eyebrow">Прошивка</p>
        <p className="muted">ждём SNAPSHOT…</p>
        <SimLog logs={props.logs} />
      </section>
    );
  }
  const radio = board.radio;
  const pace = radio.notify ? radio.notify_pace_ms : radio.indicate_timeout_ms;
  const age = radio.inflight ? board.now_ms - radio.inflight_since_ms : 0;
  const pacePct = Math.min(100, Math.round((age / pace) * 100));
  return (
    <section className="pane firmware">
      <div className="fw-head">
        <p className="eyebrow">Прошивка</p>
        <span className="muted fw-osal">{board.firmware} · {props.osal}</span>
      </div>
      <div className="radio-strip" title="ATT">
        <span>CCCD 0x{radio.cccd.toString(16)}</span>
        <span>RX {radio.rx}/6</span>
        <span>TX {radio.tx}/4</span>
        <span className={radio.inflight ? "warn" : "muted"}>inflight {radio.inflight}</span>
        <span className="muted">sent {radio.att_sent}</span>
      </div>
      <div className="att-mini">
        <SlotRow count={radio.rx} depth={6} inflight={false} />
        <SlotRow count={radio.tx} depth={4} inflight={radio.inflight === 1} />
        <div className="pace">
          <div className="pace-bar" style={{ width: `${radio.inflight ? pacePct : 0}%` }} />
        </div>
      </div>
      <div className="ticks" title="каждый столбец — TICK 80 ms">
        {props.ticks.slice(-48).map((sample, index) => (
          <span
            key={`${sample.now_ms}-${index}`}
            className={`tick${sample.inflight ? " busy" : ""}${sample.led ? " lit" : ""}`}
          />
        ))}
      </div>
      <SimLog logs={props.logs} />
    </section>
  );
}
