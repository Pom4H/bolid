import { firmwareChoices } from "./firmware";
import type { RosterDevice } from "./types";

type Props = {
  devices: RosterDevice[];
  firmware: string;
  onFirmware: (fw: string) => void;
  onSpawn: () => void;
  onKill: (id: string) => void;
  onFocus: (id: string) => void;
  onAdvertise: () => void;
  onScanBoards: () => void;
  scanningBoards: boolean;
  bleError: string | null;
};

export function RosterBar(props: Props) {
  const focused = props.devices.find((device) => device.focused);
  const advertising = focused?.advertising === true;
  const choices = firmwareChoices(props.devices.map((device) => device.firmware));
  return (
    <div className="roster">
      <div className="roster-list">
        {props.devices.map((device) => (
          <button
            key={device.id}
            type="button"
            className={`roster-item${device.focused ? " on" : ""}`}
            onClick={() => props.onFocus(device.id)}
          >
            <span className="roster-name">{device.name}</span>
            <span className="roster-meta">
              {device.kind === "ble" ? "плата" : "сим"}
              {device.firmware !== null ? ` · ${device.firmware}` : ""}
              {device.advertising ? " · эфир" : ""}
              {device.linked ? " · link" : ""}
            </span>
            {device.kind === "sim" ? (
              <span
                className="roster-x"
                role="button"
                tabIndex={0}
                onClick={(event) => {
                  event.stopPropagation();
                  props.onKill(device.id);
                }}
                onKeyDown={(event) => {
                  if (event.key !== "Enter" && event.key !== " ") return;
                  event.preventDefault();
                  event.stopPropagation();
                  props.onKill(device.id);
                }}
              >
                ×
              </span>
            ) : null}
          </button>
        ))}
      </div>
      <label className="roster-fw">
        Прошивка
        <select
          value={props.firmware}
          onChange={(event) => props.onFirmware(event.target.value)}
          aria-label="версия прошивки симулятора"
        >
          {choices.map((item) => (
            <option key={item.version} value={item.version}>
              {item.label}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className="primary" onClick={props.onSpawn}>
        + сим
      </button>
      <button type="button" className={advertising ? "on" : ""} onClick={props.onAdvertise}>
        BLE сервер
      </button>
      <button type="button" className={props.scanningBoards ? "on" : ""} onClick={props.onScanBoards}>
        {props.scanningBoards ? "Ищу плату…" : "Найти плату"}
      </button>
      {props.bleError !== null ? <span className="roster-err">{props.bleError}</span> : null}
    </div>
  );
}
