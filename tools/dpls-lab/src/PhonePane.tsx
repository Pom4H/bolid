import type { RosterDevice } from "./types";

type Props = { devices: RosterDevice[] };

export function PhonePane({ devices }: Props) {
  const names = devices.map((device) => `${device.name} ${device.firmware ?? ""}`.trim()).join(", ");
  return (
    <section className="pane phone">
      <p className="eyebrow">Приложение</p>
      <p className="phone-hint">
        В списке — симуляторы и живые платы с Bluetooth ноутбука. Поиск в телефоне поднимает оба.
        {names.length > 0 ? ` Сейчас: ${names}.` : " Нажмите «+ сим» или «Bluetooth ноутбука»."}
      </p>
      <div className="phone-shell">
        <iframe className="phone-frame" title="Test-DPLS" src="/phone/" />
      </div>
    </section>
  );
}
