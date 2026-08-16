import type { BoardSnapshot } from "./types";
import { driveFromBoard, livePins, pinFill, type LivePin } from "./pins";

type Props = { board: BoardSnapshot };

const BOARD = { x: 218, y: 24, w: 248, h: 492 };
const PITCH = 29.4;
const FIRST_Y = 62;
const LEFT_X = BOARD.x + 15;
const RIGHT_X = BOARD.x + BOARD.w - 15;

function pinY(index: number): number {
  return FIRST_Y + index * PITCH;
}

function holeQuiet(pin: LivePin): boolean {
  return (
    pin.kind === "nc" ||
    pin.kind === "swd" ||
    pin.kind === "uart" ||
    pin.kind === "reset" ||
    pin.role === "Warm LED" ||
    pin.role.startsWith("LED ")
  );
}

function pinCaption(pin: LivePin): string | null {
  if (pin.role.startsWith("ISO_") || pin.role.startsWith("KZ_")) return pin.role;
  switch (pin.role) {
    case "+Т ADC":
      return "+Т";
    case "резерв ADC":
      return "резерв";
    case "+1 ADC":
      return "+1";
    case "+2 ADC":
      return "+2";
    case "3.3 В":
      return "3V3";
    case "GND":
      return "GND";
    case "5 В USB":
      return "5V";
    default:
      return null;
  }
}

function Hole({ pin, x, y }: { pin: LivePin; x: number; y: number }) {
  const fill = pinFill(pin);
  const glow = pin.high === true || (pin.kind === "adc" && (pin.volts ?? 0) > 1);
  const quiet = holeQuiet(pin);
  return (
    <g opacity={quiet ? 0.28 : 1}>
      {glow && !quiet ? <circle cx={x} cy={y} r="9" fill={fill} opacity="0.28" /> : null}
      <circle cx={x} cy={y} r="6.4" fill="#d7b17a" stroke="#8d7044" strokeWidth="0.9" />
      <circle cx={x} cy={y} r="3.2" fill={fill} stroke="#0b0e10" strokeWidth="0.8" />
    </g>
  );
}

function SideLabel({
  pin,
  x,
  y,
  anchor,
}: {
  pin: LivePin;
  x: number;
  y: number;
  anchor: "end" | "start";
}) {
  const caption = pinCaption(pin);
  if (caption === null) return null;
  const hot = pin.high === true || (pin.kind === "adc" && (pin.volts ?? 0) > 1);
  return (
    <text
      x={x}
      y={y}
      textAnchor={anchor}
      fill={hot ? "#f4f6f7" : "#8e9396"}
      fontSize="10.5"
      fontFamily="IBM Plex Sans, Segoe UI, sans-serif"
    >
      {caption}
    </text>
  );
}

function Led5050({
  x,
  y,
  r,
  g,
  b,
  caption,
  identify,
}: {
  x: number;
  y: number;
  r: number;
  g: number;
  b: number;
  caption: string;
  identify: boolean;
}) {
  const on = r + g + b > 0.05;
  const fill = on
    ? `rgb(${Math.round(r * 255)},${Math.round(g * 255)},${Math.round(b * 255)})`
    : "#121212";
  return (
    <g transform={`translate(${x},${y})`}>
      <rect x="-11" y="-11" width="22" height="22" rx="2.5" fill="#0e0e0e" stroke="#6a6a6a" strokeWidth="0.8" />
      <rect x="-10.2" y="-10.2" width="4" height="4" fill="#7a6a48" />
      <rect x="6.2" y="-10.2" width="4" height="4" fill="#7a6a48" />
      <rect x="-10.2" y="6.2" width="4" height="4" fill="#7a6a48" />
      <rect x="6.2" y="6.2" width="4" height="4" fill="#7a6a48" />
      <circle
        className={identify && on ? "rgb-lens identify" : on ? "rgb-lens on" : "rgb-lens"}
        cx="0"
        cy="0"
        r="7.4"
        fill={fill}
        stroke={on ? "#e8e8e8" : "#5a5a5a"}
        strokeWidth="0.7"
        filter={on ? "url(#ledglow)" : undefined}
      />
      <circle cx="-2" cy="-2.2" r="2.1" fill="#ffffff" opacity={on ? "0.35" : "0.12"} />
      <text y="20" textAnchor="middle" fill="#8e9396" fontSize="7">{caption}</text>
    </g>
  );
}

export function BoardSvg({ board }: Props) {
  const pins = livePins(board);
  const drive = driveFromBoard(board);
  return (
    <svg className="kit-svg" viewBox="0 0 720 540" role="img" aria-label="PB-03F-Kit, вид сверху">
      <defs>
        <linearGradient id="pcb" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#171c20" />
          <stop offset="1" stopColor="#0d1114" />
        </linearGradient>
        <linearGradient id="shield" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#3a4248" />
          <stop offset="0.4" stopColor="#1c2226" />
          <stop offset="1" stopColor="#101418" />
        </linearGradient>
        <filter id="ledglow" x="-120%" y="-120%" width="340%" height="340%">
          <feGaussianBlur stdDeviation="2.8" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      {pins.j3.map((pin, index) => (
        <SideLabel key={`l-${pin.id}`} pin={pin} x={BOARD.x - 16} y={pinY(index) + 3.2} anchor="end" />
      ))}
      {pins.j2.map((pin, index) => (
        <SideLabel key={`r-${pin.id}`} pin={pin} x={BOARD.x + BOARD.w + 16} y={pinY(index) + 3.2} anchor="start" />
      ))}

      <rect x={BOARD.x} y={BOARD.y} width={BOARD.w} height={BOARD.h} rx="13" fill="url(#pcb)" stroke="#4a555c" strokeWidth="1.5" />
      <rect x={BOARD.x + 7} y={BOARD.y + 7} width={BOARD.w - 14} height={BOARD.h - 14} rx="9" fill="none" stroke="#232b31" />

      <rect x="250" y="32" width="132" height="22" rx="2" fill="#152018" stroke="#3d5a44" />
      <path
        d="M256 43 h8 v-6 h6 v6 h8 v-6 h6 v6 h8 v-6 h6 v6 h8 v-6 h6 v6 h8 v-6 h6 v6 h8 v-6 h6 v6 h8"
        fill="none"
        stroke="#7d9a72"
        strokeWidth="1.3"
      />

      {pins.j1.map((pin, index) => (
        <Hole key={pin.id} pin={pin} x={BOARD.x + 46 + index * 26} y={BOARD.y + 16} />
      ))}

      <rect x="246" y="68" width="136" height="122" rx="5" fill="url(#shield)" stroke="#8a949c" strokeWidth="1.3" />
      <circle cx="254" cy="76" r="2.1" fill="#1a1a1a" stroke="#6a737a" />
      <circle cx="374" cy="76" r="2.1" fill="#1a1a1a" stroke="#6a737a" />
      <circle cx="254" cy="182" r="2.1" fill="#1a1a1a" stroke="#6a737a" />
      <circle cx="374" cy="182" r="2.1" fill="#1a1a1a" stroke="#6a737a" />
      <text x="314" y="118" textAnchor="middle" fill="#f4f6f7" fontSize="14" fontWeight="700">PB-03F</text>
      <text x="314" y="136" textAnchor="middle" fill="#00ccff" fontSize="10">PHY6252</text>
      <text x="314" y="152" textAnchor="middle" fill="#9aa2a8" fontSize="8">BLE 5.2  ·  Mesh</text>
      <text x="314" y="168" textAnchor="middle" fill="#6a737a" fontSize="7">2ATPO-PB03</text>

      <Led5050
        x={410}
        y={112}
        r={drive.ledR ? 1 : 0}
        g={drive.ledG ? 1 : 0}
        b={drive.ledB ? 1 : 0}
        identify={board.identify === 1}
        caption="RGB"
      />

      <rect x="246" y="206" width="48" height="20" rx="1.5" fill="#2c333a" stroke="#8a949c" />
      <rect x="250" y="224" width="12" height="6" fill="#c4a574" />
      <rect x="266" y="224" width="8" height="6" fill="#c4a574" />
      <rect x="278" y="224" width="8" height="6" fill="#c4a574" />

      <rect x="368" y="204" width="58" height="28" rx="2" fill="#1a2026" stroke="#8a949c" />
      {Array.from({ length: 8 }, (_, i) => (
        <rect key={`chl${i}`} x={372 + i * 6.4} y="201" width="3.4" height="5" fill="#c4a574" />
      ))}
      {Array.from({ length: 8 }, (_, i) => (
        <rect key={`chr${i}`} x={372 + i * 6.4} y="230" width="3.4" height="5" fill="#c4a574" />
      ))}

      <text x="342" y="268" textAnchor="middle" fill="#5a656e" fontSize="9">NodeMCU-Series</text>
      <text x="342" y="284" textAnchor="middle" fill="#e8ecef" fontSize="12" fontWeight="700">PB-03F-Kit</text>
      <text x="342" y="298" textAnchor="middle" fill="#5a656e" fontSize="8">A148</text>

      <rect x="250" y="494" width="20" height="16" rx="3" fill="#161a1e" stroke="#cfd3d6" />
      <circle cx="260" cy="502" r="3.2" fill="#2a3036" />
      <text x="260" y="522" textAnchor="middle" fill="#8e9396" fontSize="7">RST</text>
      <rect x="414" y="494" width="20" height="16" rx="3" fill="#161a1e" stroke="#cfd3d6" />
      <circle cx="424" cy="502" r="3.2" fill="#2a3036" />
      <text x="424" y="522" textAnchor="middle" fill="#8e9396" fontSize="7">Restore</text>

      <rect x="298" y="498" width="68" height="16" rx="2" fill="#111" stroke="#9aa0a4" />
      <rect x="304" y="502" width="56" height="5" rx="1" fill="#2b2b2b" />
      <text x="332" y="494" textAnchor="middle" fill="#8e9396" fontSize="7">Micro-USB</text>

      {pins.j3.map((pin, index) => (
        <Hole key={`h3-${pin.id}`} pin={pin} x={LEFT_X} y={pinY(index)} />
      ))}
      {pins.j2.map((pin, index) => (
        <Hole key={`h2-${pin.id}`} pin={pin} x={RIGHT_X} y={pinY(index)} />
      ))}
    </svg>
  );
}
