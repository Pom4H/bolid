import type { BoardSnapshot } from "./types";

export type PinKind = "gpio" | "adc" | "power" | "gnd" | "uart" | "swd" | "nc" | "reset";

export type HeaderId = "J3" | "J2" | "J1";

export type HeaderPin = {
  id: string;
  header: HeaderId;
  label: string;
  kind: PinKind;
  role: string;
};

export type LivePin = HeaderPin & {
  volts: number | null;
  high: boolean | null;
  detail: string;
};

/* DIP-30 silkscreen, USB at bottom, antenna at top.
 * Order matches the kit schematic and docs/hardware/pb03f-kit-power-pinout.png.
 * Spec table 6 lists J2 pin 1 as P13; the schematic, Restore=P15, and the
 * photographed A148 board all start J2 with P15. */
export const J3_PINS: HeaderPin[] = [
  { id: "P24", header: "J3", label: "P24", kind: "adc", role: "+Т ADC" },
  { id: "P23", header: "J3", label: "P23", kind: "adc", role: "резерв ADC" },
  { id: "P20", header: "J3", label: "P20", kind: "adc", role: "+1 ADC" },
  { id: "P3", header: "J3", label: "P3", kind: "swd", role: "SWD CLK" },
  { id: "P2", header: "J3", label: "P2", kind: "swd", role: "SWD IO" },
  { id: "3V3a", header: "J3", label: "3V3", kind: "power", role: "3.3 В" },
  { id: "GNDa", header: "J3", label: "GND", kind: "gnd", role: "GND" },
  { id: "NCa", header: "J3", label: "NC", kind: "nc", role: "NC" },
  { id: "P34", header: "J3", label: "P34", kind: "reset", role: "Reset / Cool" },
  { id: "P0", header: "J3", label: "P0", kind: "gpio", role: "Warm LED" },
  { id: "P18", header: "J3", label: "P18", kind: "gpio", role: "LED B" },
  { id: "RX0", header: "J3", label: "RX0", kind: "uart", role: "UART RX" },
  { id: "TX0", header: "J3", label: "TX0", kind: "uart", role: "UART TX" },
  { id: "GNDb", header: "J3", label: "GND", kind: "gnd", role: "GND" },
  { id: "3V3b", header: "J3", label: "3V3", kind: "power", role: "3.3 В" },
];

export const J2_PINS: HeaderPin[] = [
  { id: "P15", header: "J2", label: "P15", kind: "adc", role: "+2 ADC" },
  { id: "P11", header: "J2", label: "P11", kind: "gpio", role: "LED G" },
  { id: "P31", header: "J2", label: "P31", kind: "gpio", role: "ISO_1" },
  { id: "P7", header: "J2", label: "P7", kind: "gpio", role: "LED R" },
  { id: "P32", header: "J2", label: "P32", kind: "gpio", role: "ISO_2" },
  { id: "P33", header: "J2", label: "P33", kind: "gpio", role: "ISO_T" },
  { id: "P14", header: "J2", label: "P14", kind: "gpio", role: "KZ_1" },
  { id: "P16", header: "J2", label: "P16", kind: "gpio", role: "KZ_2" },
  { id: "P17", header: "J2", label: "P17", kind: "gpio", role: "KZ_T" },
  { id: "GNDc", header: "J2", label: "GND", kind: "gnd", role: "GND" },
  { id: "3V3c", header: "J2", label: "3V3", kind: "power", role: "3.3 В" },
  { id: "NCb", header: "J2", label: "NC", kind: "nc", role: "NC" },
  { id: "NCc", header: "J2", label: "NC", kind: "nc", role: "NC" },
  { id: "GNDd", header: "J2", label: "GND", kind: "gnd", role: "GND" },
  { id: "5V", header: "J2", label: "5V", kind: "power", role: "5 В USB" },
];

export const J1_PINS: HeaderPin[] = [
  { id: "J1NC", header: "J1", label: "NC", kind: "nc", role: "NC" },
  { id: "J1P34", header: "J1", label: "P34", kind: "reset", role: "Reset / Cool" },
  { id: "J1P0", header: "J1", label: "P0", kind: "gpio", role: "Warm LED" },
  { id: "J1P18", header: "J1", label: "P18", kind: "gpio", role: "LED B" },
  { id: "J1P17", header: "J1", label: "P17", kind: "gpio", role: "KZ_T" },
  { id: "J1P16", header: "J1", label: "P16", kind: "gpio", role: "KZ_2" },
];

export type Drive = {
  iso1: boolean;
  iso2: boolean;
  isoT: boolean;
  kz1: boolean;
  kz2: boolean;
  kzT: boolean;
  ledR: boolean;
  ledG: boolean;
  ledB: boolean;
  cool: boolean;
  warm: boolean;
};

export function driveFromBoard(board: BoardSnapshot): Drive {
  return {
    iso1: board.gpio.iso1 === 1,
    iso2: board.gpio.iso2 === 1,
    isoT: board.gpio.isoT === 1,
    kz1: board.gpio.kz1 === 1,
    kz2: board.gpio.kz2 === 1,
    kzT: board.gpio.kzT === 1,
    ledR: board.gpio.ledR === 1,
    ledG: board.gpio.ledG === 1,
    ledB: board.gpio.ledB === 1,
    cool: false,
    warm: false,
  };
}

function gpio(high: boolean): Pick<LivePin, "volts" | "high" | "detail"> {
  return {
    volts: high ? 3.3 : 0,
    high,
    detail: high ? "3.3 В HIGH" : "0 В LOW",
  };
}

function liveOne(pin: HeaderPin, board: BoardSnapshot, drive: Drive): LivePin {
  const base = { ...pin, volts: null as number | null, high: null as boolean | null, detail: pin.role };
  switch (pin.label) {
    case "GND":
      return { ...base, volts: 0, high: false, detail: "0 В" };
    case "3V3":
      return { ...base, volts: 3.3, high: true, detail: "3.3 В" };
    case "5V":
      return { ...base, volts: 5, high: true, detail: "5 В USB" };
    case "NC":
      return { ...base, detail: "нет цепи" };
    case "P20":
      return { ...base, volts: board.line_mv / 1000, high: null, detail: `${(board.line_mv / 1000).toFixed(2)} В +1` };
    case "P15":
      return { ...base, volts: board.port2_mv / 1000, high: null, detail: `${(board.port2_mv / 1000).toFixed(2)} В +2` };
    case "P24":
      return { ...base, volts: board.port_t_mv / 1000, high: null, detail: `${(board.port_t_mv / 1000).toFixed(2)} В +Т` };
    case "P23":
      return { ...base, volts: board.reserve_mv / 1000, high: null, detail: `${(board.reserve_mv / 1000).toFixed(2)} В резерв` };
    case "P31":
      return { ...base, ...gpio(drive.iso1) };
    case "P32":
      return { ...base, ...gpio(drive.iso2) };
    case "P33":
      return { ...base, ...gpio(drive.isoT) };
    case "P14":
      return { ...base, ...gpio(drive.kz1) };
    case "P16":
      return { ...base, ...gpio(drive.kz2) };
    case "P17":
      return { ...base, ...gpio(drive.kzT) };
    case "P7":
      return { ...base, ...gpio(drive.ledR), detail: drive.ledR ? "LED R ON" : "LED R off" };
    case "P11":
      return { ...base, ...gpio(drive.ledG), detail: drive.ledG ? "LED G ON" : "LED G off" };
    case "P18":
      return { ...base, ...gpio(drive.ledB), detail: drive.ledB ? "LED B ON" : "LED B off" };
    case "P34":
      return { ...base, volts: 3.3, high: true, detail: "вход Reset, Cool off" };
    case "P0":
      return { ...base, ...gpio(drive.warm), detail: "Warm LED, прошивка не трогает" };
    case "RX0":
      return { ...base, volts: 3.3, high: true, detail: "UART RX idle" };
    case "TX0":
      return { ...base, volts: 3.3, high: true, detail: "UART TX idle" };
    case "P2":
    case "P3":
      return { ...base, detail: "SWD, не в Test-DPLS" };
    default:
      return base;
  }
}

export function livePins(board: BoardSnapshot): { j3: LivePin[]; j2: LivePin[]; j1: LivePin[] } {
  const drive = driveFromBoard(board);
  return {
    j3: J3_PINS.map((pin) => liveOne(pin, board, drive)),
    j2: J2_PINS.map((pin) => liveOne(pin, board, drive)),
    j1: J1_PINS.map((pin) => liveOne(pin, board, drive)),
  };
}

export function pinFill(pin: LivePin): string {
  if (pin.kind === "gnd") return "#3d6ea8";
  if (pin.kind === "nc") return "#1b2228";
  if (pin.label === "5V") return "#d45252";
  if (pin.label === "3V3") return "#ff8a3d";
  if (pin.kind === "adc" && pin.volts !== null) {
    const t = Math.min(1, pin.volts / 12);
    return t > 0.4 ? "#34c759" : t > 0.05 ? "#ff8a3d" : "#5a3a2a";
  }
  if (pin.high === true) return "#00ccff";
  if (pin.high === false) return "#2a343c";
  return "#3a444c";
}
