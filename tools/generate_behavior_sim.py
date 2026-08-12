#!/usr/bin/env python3
"""Generate an interactive Test-DPLS hardware/behaviour model from source.

No pin table or mode table is duplicated here.  The generator reads board.h,
the protocol server, the hardware owner and the ADC owner.  If that structure
cannot be understood it fails the build instead of producing plausible-looking
stale documentation.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

DEFINE_RE = re.compile(r"^\s*#define\s+([A-Z0-9_]+)\s+([^/\r\n]+)", re.MULTILINE)
MODE_RE = re.compile(r"\b(DPLS_MODE_[A-Z0-9_]+)\s*=\s*(\d+)")
CASE_RE = re.compile(
    r"case\s+(DPLS_MODE_[A-Z0-9_]+)\s*:(.*?)(?=\n\s*case\s+DPLS_MODE_|\n\s*default\s*:)",
    re.DOTALL,
)
WRITE_RE = re.compile(r"hal_gpio_write\((DPLS_PIN_[A-Z0-9_]+),\s*1\s*\)")

MODE_LABELS = {
    "DPLS_MODE_NORMAL": "Норма",
    "DPLS_MODE_OPEN_T": "Обрыв +T",
    "DPLS_MODE_OPEN_MAIN": "Обрыв магистрали",
    "DPLS_MODE_SHORT_1": "КЗ +1",
    "DPLS_MODE_SHORT_2": "КЗ +2",
    "DPLS_MODE_SHORT_T": "КЗ +T",
}

PIN_LABELS = {
    "DPLS_PIN_ISO_1": "ISO_1 · разрыв +1",
    "DPLS_PIN_ISO_2": "ISO_2 · разрыв +2",
    "DPLS_PIN_ISO_T": "ISO_T · разрыв +T",
    "DPLS_PIN_KZ_1": "KZ_1 · КЗ +1",
    "DPLS_PIN_KZ_2": "KZ_2 · КЗ +2",
    "DPLS_PIN_KZ_T": "KZ_T · КЗ +T",
    "DPLS_PIN_PORT1_ADC": "ADC · +1",
    "DPLS_PIN_PORT2_ADC": "ADC · +2",
    "DPLS_PIN_PORT_T_ADC": "ADC · +T",
    "DPLS_PIN_VCAP_ADC": "ADC · резерв",
    "DPLS_PIN_LINE_ADC": "ADC · +1 (alias)",
    "DPLS_PIN_LED_RED": "RGB · красный",
    "DPLS_PIN_LED_GREEN": "RGB · зелёный / поиск",
    "DPLS_PIN_LED_BLUE": "RGB · синий",
    "DPLS_PIN_FACTORY_RESET": "сброс пароля",
}


def die(message: str) -> "NoReturn":
    raise SystemExit(f"generate_behavior_sim: {message}")


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        die(f"cannot read {path}: {exc}")


def defines(text: str) -> dict[str, str]:
    return {name: value.strip() for name, value in DEFINE_RE.findall(text)}


def numeric(name: str, tables: list[dict[str, str]]) -> int:
    raw = next((table[name] for table in tables if name in table), None)
    if raw is None:
        die(f"required numeric define {name} not found")
    token = raw.strip().strip("()")
    token = re.sub(r"(?<=\d)[uUlL]+\b", "", token)
    if not re.fullmatch(r"[0-9xXa-fA-F\s+\-*/()]+", token):
        die(f"unsupported numeric expression {name}={raw!r}")
    try:
        value = eval(token, {"__builtins__": {}}, {})
    except Exception as exc:
        die(f"cannot evaluate {name}={raw!r}: {exc}")
    if not isinstance(value, int):
        die(f"{name} is not an integer")
    return value


def resolve_pin(name: str, board_defs: dict[str, str], seen: set[str] | None = None) -> str:
    seen = set() if seen is None else set(seen)
    if name in seen:
        die(f"cyclic pin alias at {name}")
    seen.add(name)
    raw = board_defs.get(name)
    if raw is None:
        die(f"pin define {name} not found")
    token = raw.split()[0].strip("()")
    if re.fullmatch(r"GPIO_P\d{2}", token):
        return token.replace("GPIO_", "")
    if token.startswith("DPLS_PIN_"):
        return resolve_pin(token, board_defs, seen)
    die(f"unsupported pin expression {name}={raw!r}")


def extract_model(root: Path) -> dict[str, object]:
    paths = {
        "board": root / "Firmware/phy6252/dpls_board.h",
        "server": root / "Firmware/include/dpls_server.h",
        "app": root / "Firmware/phy6252/dpls_phy6252_app.c",
        "hw": root / "Firmware/phy6252/dpls_phy6252_hw.c",
        "adc": root / "Firmware/phy6252/dpls_phy6252_adc.c",
    }
    source = {name: read(path) for name, path in paths.items()}
    defs = {name: defines(text) for name, text in source.items()}
    tables = [defs["server"], defs["app"], defs["adc"], defs["board"]]

    enum_match = re.search(r"typedef\s+enum\s*\{(.*?)\}\s*dpls_mode_t\s*;", source["server"], re.DOTALL)
    if not enum_match:
        die("dpls_mode_t enum not found")
    modes = sorted(MODE_RE.findall(enum_match.group(1)), key=lambda item: int(item[1]))
    if [name for name, _ in modes] != list(MODE_LABELS):
        die(f"unexpected mode enum: {[name for name, _ in modes]}")

    cases = {name: body for name, body in CASE_RE.findall(source["hw"])}
    mode_rows = []
    for name, value in modes:
        writes = WRITE_RE.findall(cases.get(name, ""))
        if name != "DPLS_MODE_NORMAL" and len(writes) != 1:
            die(f"{name} must assert exactly one hardware output, got {writes}")
        mode_rows.append({
            "id": name,
            "value": int(value),
            "label": MODE_LABELS[name],
            "controls": [] if name == "DPLS_MODE_NORMAL" else writes,
        })

    pin_rows = []
    for name in sorted(n for n in defs["board"] if n.startswith("DPLS_PIN_")):
        kind = (
            "analog" if name.endswith("_ADC")
            else "indicator" if name.startswith("DPLS_PIN_LED_")
            else "input" if name == "DPLS_PIN_FACTORY_RESET"
            else "control"
        )
        pin_rows.append({
            "name": name,
            "pin": resolve_pin(name, defs["board"]),
            "label": PIN_LABELS.get(name, name.removeprefix("DPLS_PIN_").replace("_", " ").lower()),
            "kind": kind,
        })

    firmware = ".".join(str(numeric(name, tables)) for name in (
        "DPLS_FW_VERSION_MAJOR", "DPLS_FW_VERSION_MINOR", "DPLS_FW_VERSION_PATCH"
    ))
    model = {
        "firmware": firmware,
        "commit": os.environ.get("GITHUB_SHA", "local")[:12],
        "modes": mode_rows,
        "pins": pin_rows,
        "timing": {
            "modeTimeoutMs": numeric("DPLS_MODE_MAX_MS", tables),
            "sessionTimeoutMs": numeric("DPLS_SESSION_TIMEOUT_MS", tables),
            "identifyMaxMs": numeric("DPLS_IDENTIFY_MAX_MS", tables),
            "identifyBlinkMs": numeric("DPLS_IDENTIFY_BLINK_MS", tables),
            "adcPeriodMs": numeric("DPLS_ADC_PERIOD_MS", tables),
            "adcStaleMs": numeric("DPLS_ADC_STALE_MS", tables),
        },
        "adc": {
            "linePresentMv": numeric("DPLS_LINE_PRESENT_MV", tables),
            "lineAbsentMv": numeric("DPLS_LINE_ABSENT_MV", tables),
            "reserveLowMv": numeric("DPLS_RESERVE_LOW_MV", tables),
            "reserveOkMv": numeric("DPLS_RESERVE_OK_MV", tables),
            "autoIsoTripMv": numeric("DPLS_AUTOISO_TRIP_MV", tables),
            "autoIsoClearMv": numeric("DPLS_AUTOISO_CLEAR_MV", tables),
            "sequential": "inflight_index" in source["adc"] and "adc_pending" in source["adc"],
            "channels": [
                {"label": "+1", "pin": resolve_pin("DPLS_PIN_PORT1_ADC", defs["board"]), "channel": "ADC_CH9"},
                {"label": "+2", "pin": resolve_pin("DPLS_PIN_PORT2_ADC", defs["board"]), "channel": "ADC_CH4"},
                {"label": "+T", "pin": resolve_pin("DPLS_PIN_PORT_T_ADC", defs["board"]), "channel": "ADC_CH2"},
                {"label": "резерв", "pin": resolve_pin("DPLS_PIN_VCAP_ADC", defs["board"]), "channel": "ADC_CH1"},
            ],
        },
        "safety": {
            "connectionSleepLock": "hal_pwrmgr_lock(MOD_USR1)" in source["hw"],
            "xtalWakeFix": "disable_32k_xtal" in source["hw"],
            "glitchSafeInit": "prime_all_outputs_low" in source["hw"],
            "staleAdcInvalidation": "DPLS_ADC_STALE_MS" in source["adc"],
        },
        "sources": [str(path.relative_to(root)) for path in paths.values()],
    }
    return model


HTML = r'''<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Тест-ДПЛС · hardware model</title><style>
:root{font-family:system-ui,sans-serif;color-scheme:light dark}body{margin:0;background:#11151a;color:#edf1f6}main{max-width:1100px;margin:auto;padding:20px}.card{background:#1a2027;border:1px solid #303944;border-radius:14px;padding:16px;margin:0 0 14px}h1{font-size:20px;margin:0 0 5px}h2{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:#98a5b5}.meta,.mono{font-family:ui-monospace,monospace}.meta{color:#98a5b5;font-size:12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}@media(max-width:760px){.grid{grid-template-columns:1fr}}button{margin:4px;padding:7px 10px;border-radius:8px;border:1px solid #3a4654;background:transparent;color:inherit}button.on{background:#2867c7;border-color:#2867c7}.chip{display:inline-block;padding:5px 8px;margin:3px;border:1px solid #3a4654;border-radius:999px;font:12px ui-monospace,monospace}.chip.hot{border-color:#ffad4c;color:#ffad4c}table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:6px;border-bottom:1px solid #303944;text-align:left}th{color:#98a5b5}ul{padding-left:20px}.ok{color:#61d69a}.bad{color:#ff7e6b}</style></head><body><main>
<section class="card"><h1>Тест-ДПЛС · модель текущей прошивки</h1><div id="meta" class="meta"></div><div id="buttons"></div></section>
<div class="grid"><section class="card"><h2>Режим</h2><div id="mode"></div><h2>Активные управляющие выводы</h2><div id="active"></div></section><section class="card"><h2>Hardware safety</h2><ul id="safety"></ul></section></div>
<div class="grid"><section class="card"><h2>Четыре ADC</h2><table><thead><tr><th>Вход</th><th>GPIO</th><th>ADC</th></tr></thead><tbody id="adc"></tbody></table><p class="meta" id="adcmeta"></p></section><section class="card"><h2>Распиновка</h2><table><thead><tr><th>Символ</th><th>GPIO</th><th>Роль</th></tr></thead><tbody id="pins"></tbody></table></section></div>
<section class="card"><h2>Источник</h2><div id="sources" class="meta"></div></section>
<script id="model" type="application/json">__MODEL__</script><script>
const m=JSON.parse(document.getElementById('model').textContent),$=id=>document.getElementById(id);let cur=m.modes[0];
$('meta').textContent=`FW ${m.firmware} · ${m.commit} · ADC ${m.adc.sequential?'последовательный':'НЕ подтверждён'}`;
function render(){ $('buttons').innerHTML=''; for(const mode of m.modes){const b=document.createElement('button');b.textContent=mode.label;b.className=mode.id===cur.id?'on':'';b.onclick=()=>{cur=mode;render()};$('buttons').appendChild(b)} $('mode').textContent=cur.label;$('active').innerHTML=cur.controls.length?cur.controls.map(x=>`<span class="chip hot">${x}</span>`).join(''):'<span class="chip">все 0 · Norma</span>' } render();
$('safety').innerHTML=Object.entries(m.safety).map(([k,v])=>`<li class="${v?'ok':'bad'}">${v?'✓':'✕'} ${k}</li>`).join('');
$('adc').innerHTML=m.adc.channels.map(x=>`<tr><td>${x.label}</td><td class="mono">${x.pin}</td><td class="mono">${x.channel}</td></tr>`).join('');
$('adcmeta').textContent=`цикл ${m.timing.adcPeriodMs} мс · stale после ${m.timing.adcStaleMs} мс · line hysteresis ${m.adc.lineAbsentMv}/${m.adc.linePresentMv} мВ · reserve ${m.adc.reserveLowMv}/${m.adc.reserveOkMv} мВ`;
$('pins').innerHTML=m.pins.map(x=>`<tr><td class="mono">${x.name}</td><td class="mono">${x.pin}</td><td>${x.label}</td></tr>`).join('');$('sources').innerHTML=m.sources.map(x=>`<div>${x}</div>`).join('');
</script></main></body></html>'''


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="docs/behavior-sim.html")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    model = extract_model(root)
    encoded = json.dumps(model, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(HTML.replace("__MODEL__", encoded), encoding="utf-8")
    print(f"generated {output} from {len(model['sources'])} source files")


if __name__ == "__main__":
    main()
