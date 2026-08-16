import { BoardSvg } from "./BoardSvg";
import type { BoardSnapshot } from "./types";

type Props = {
  board: BoardSnapshot | null;
  emptyHint?: string;
  onLab: () => void;
  onLine: () => void;
  onShort: (on: boolean) => void;
  onReserve: (on: boolean) => void;
};

function VoltChip({ label, mv, warn }: { label: string; mv: number; warn?: boolean }) {
  return (
    <div className={`vchip${warn ? " warn" : ""}`}>
      <span className="vchip-k">{label}</span>
      <span className="vchip-v">{(mv / 1000).toFixed(2)} В</span>
    </div>
  );
}

export function BoardPane(props: Props) {
  const board = props.board;
  if (board === null) {
    return (
      <section className="pane board">
        <p className="eyebrow">Железо</p>
        <p className="muted">{props.emptyHint ?? "ждём SNAPSHOT…"}</p>
      </section>
    );
  }
  const onLine = board.power === 0;
  return (
    <section className="pane board">
      <p className="eyebrow">Железо</p>
      <div className="board-canvas">
        <BoardSvg board={board} />
      </div>
      <div className="board-dock">
        <div className="volts-row">
          <VoltChip label="+1" mv={board.line_mv} warn={board.real_short === 1} />
          <VoltChip label="+2" mv={board.port2_mv} />
          <VoltChip label="+Т" mv={board.port_t_mv} />
          <VoltChip label="резерв" mv={board.reserve_mv} warn={board.reserve_low === 1} />
        </div>
        <div className="stand">
          <div className="stand-radio" role="radiogroup" aria-label="Питание">
            <button
              type="button"
              role="radio"
              aria-checked={onLine}
              className={onLine ? "on" : ""}
              onClick={props.onLine}
            >
              Линия 12 В
            </button>
            <button
              type="button"
              role="radio"
              aria-checked={!onLine}
              className={onLine ? "" : "on"}
              onClick={props.onLab}
            >
              LAB USB
            </button>
          </div>
          <div className="stand-faults">
            <button
              type="button"
              aria-pressed={board.real_short === 1}
              className={board.real_short ? "on danger" : ""}
              onClick={() => props.onShort(board.real_short !== 1)}
            >
              КЗ линии
            </button>
            <button
              type="button"
              aria-pressed={board.reserve_low === 1}
              className={board.reserve_low ? "on" : ""}
              onClick={() => props.onReserve(board.reserve_low !== 1)}
            >
              Низкий резерв
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}
