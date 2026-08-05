#!/usr/bin/env python3
"""Generate the Test-DPLS interactive behaviour model from firmware sources.

The generated HTML is a build artifact. It must not be committed: this script
introspects the current pin map, mode switch, firmware version, timeouts and ADC
thresholds so release documentation cannot silently drift from the code.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Final

DEFINE_RE: Final = re.compile(r"^\s*#define\s+([A-Z0-9_]+)\s+([^/\r\n]+)", re.MULTILINE)
MODE_RE: Final = re.compile(r"\b(DPLS_MODE_[A-Z0-9_]+)\s*=\s*(\d+)")
CASE_RE: Final = re.compile(
    r"case\s+(DPLS_MODE_[A-Z0-9_]+)\s*:(.*?)(?=\n\s*case\s+DPLS_MODE_|\n\s*default\s*:)",
    re.DOTALL,
)
GPIO_WRITE_RE: Final = re.compile(r"hal_gpio_write\((DPLS_PIN_[A-Z0-9_]+),\s*1\s*\)")

MODE_LABELS: Final = {
    "DPLS_MODE_NORMAL": "Норма",
    "DPLS_MODE_OPEN_T": "Обрыв T",
    "DPLS_MODE_OPEN_MAIN": "Обрыв магистрали",
    "DPLS_MODE_SHORT_1": "КЗ +1",
    "DPLS_MODE_SHORT_2": "КЗ +2",
    "DPLS_MODE_SHORT_T": "КЗ T",
}

PIN_LABELS: Final = {
    "DPLS_PIN_ISO_1": "ISO_1 · разрыв +1",
    "DPLS_PIN_ISO_2": "ISO_2 · разрыв +2",
    "DPLS_PIN_ISO_T": "ISO_T · разрыв T",
    "DPLS_PIN_KZ_1": "KZ_1 · КЗ +1",
    "DPLS_PIN_KZ_2": "KZ_2 · КЗ +2",
    "DPLS_PIN_KZ_T": "KZ_T · КЗ T",
    "DPLS_PIN_LED_OPEN_T": "лампа · Обрыв T",
    "DPLS_PIN_LED_OPEN_MAIN": "лампа · Обрыв магистрали",
    "DPLS_PIN_LED_SHORT_1": "лампа · КЗ +1",
    "DPLS_PIN_LED_SHORT_2": "лампа · КЗ +2",
    "DPLS_PIN_LED_SHORT_T": "лампа · КЗ T",
    "DPLS_PIN_LINE_ADC": "ADC · напряжение ДПЛС",
    "DPLS_PIN_VCAP_ADC": "ADC · резерв",
    "DPLS_PIN_FACTORY_RESET": "сброс пароля",
    "DPLS_PIN_STATUS_LED": "статус · идентификация",
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
        die(f"unsupported value for {name}: {raw!r}")
    try:
        value = eval(token, {"__builtins__": {}}, {})
    except Exception as exc:
        die(f"cannot evaluate {name}={raw!r}: {exc}")
    if not isinstance(value, int):
        die(f"{name} is not an integer: {raw!r}")
    return value


def resolve_pin(name: str, board_defs: dict[str, str], seen: set[str] | None = None) -> str:
    seen = set() if seen is None else seen
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
    board_path = root / "Firmware/phy6252/dpls_board.h"
    server_path = root / "Firmware/include/dpls_server.h"
    app_path = root / "Firmware/phy6252/dpls_phy6252_app.c"

    board = read(board_path)
    server = read(server_path)
    app = read(app_path)
    board_defs = defines(board)
    server_defs = defines(server)
    app_defs = defines(app)
    tables = [server_defs, app_defs, board_defs]

    enum_match = re.search(r"typedef\s+enum\s*\{(.*?)\}\s*dpls_mode_t\s*;", server, re.DOTALL)
    if not enum_match:
        die("dpls_mode_t enum not found")
    modes = sorted(MODE_RE.findall(enum_match.group(1)), key=lambda item: int(item[1]))
    if [name for name, _ in modes] != list(MODE_LABELS):
        die(f"unexpected mode enum: {[name for name, _ in modes]}")

    case_blocks = {name: body for name, body in CASE_RE.findall(app)}
    mode_rows: list[dict[str, object]] = []
    for mode_name, value in modes:
        writes = GPIO_WRITE_RE.findall(case_blocks.get(mode_name, ""))
        controls = [pin for pin in writes if not pin.startswith("DPLS_PIN_LED_")]
        indicators = [pin for pin in writes if pin.startswith("DPLS_PIN_LED_")]
        if mode_name == "DPLS_MODE_NORMAL":
            controls = []
            indicators = []
        elif not controls or not indicators:
            die(f"{mode_name} must expose both control and indicator writes; got {writes}")
        mode_rows.append({
            "id": mode_name,
            "value": int(value),
            "label": MODE_LABELS[mode_name],
            "controls": controls,
            "indicators": indicators,
        })

    pin_rows = []
    for name in sorted(name for name in board_defs if name.startswith("DPLS_PIN_")):
        pin_rows.append({
            "name": name,
            "pin": resolve_pin(name, board_defs),
            "label": PIN_LABELS.get(name, name.removeprefix("DPLS_PIN_").replace("_", " ").lower()),
            "kind": (
                "indicator" if name.startswith("DPLS_PIN_LED_") or name == "DPLS_PIN_STATUS_LED"
                else "analog" if name.endswith("_ADC")
                else "input" if name == "DPLS_PIN_FACTORY_RESET"
                else "control"
            ),
        })

    fw = ".".join(str(numeric(name, tables)) for name in (
        "DPLS_FW_VERSION_MAJOR", "DPLS_FW_VERSION_MINOR", "DPLS_FW_VERSION_PATCH"
    ))
    return {
        "firmware": fw,
        "commit": os.environ.get("GITHUB_SHA", "local")[:12],
        "modes": mode_rows,
        "pins": pin_rows,
        "timing": {
            "modeTimeoutMs": numeric("DPLS_MODE_MAX_MS", tables),
            "sessionTimeoutMs": numeric("DPLS_SESSION_TIMEOUT_MS", tables),
            "identifyMaxMs": numeric("DPLS_IDENTIFY_MAX_MS", tables),
            "identifyBlinkMs": numeric("DPLS_IDENTIFY_BLINK_MS", tables),
            "authBlockMs": numeric("DPLS_AUTH_BLOCK_MS", tables),
            "authAttempts": numeric("DPLS_AUTH_MAX_ATTEMPTS", tables),
        },
        "adc": {
            "decimate": numeric("DPLS_ADC_DECIMATE", tables),
            "window": numeric("DPLS_ADC_WINDOW", tables),
            "linePresentMv": numeric("DPLS_LINE_PRESENT_MV", tables),
            "lineAbsentMv": numeric("DPLS_LINE_ABSENT_MV", tables),
            "reserveLowMv": numeric("DPLS_RESERVE_LOW_MV", tables),
            "reserveOkMv": numeric("DPLS_RESERVE_OK_MV", tables),
            "autoIsoTripMv": numeric("DPLS_AUTOISO_TRIP_MV", tables),
            "autoIsoClearMv": numeric("DPLS_AUTOISO_CLEAR_MV", tables),
            "sequential": "status == all_channels" in app and "adc_pending" in app,
        },
        "sources": [str(path.relative_to(root)) for path in (board_path, server_path, app_path)],
    }


HTML_TEMPLATE: Final = r'''<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Тест-ДПЛС · интерактивная интроспекция</title>
<style>
:root{color-scheme:light dark;--bg:#f4f6f8;--panel:#fff;--ink:#15191f;--dim:#657080;--line:#d8dee8;--accent:#2867c7;--hot:#d5760c;--ok:#238451;--bad:#c54535}
@media(prefers-color-scheme:dark){:root{--bg:#11151a;--panel:#1a2027;--ink:#edf1f6;--dim:#98a5b5;--line:#303944;--accent:#73aaff;--hot:#ffad4c;--ok:#61d69a;--bad:#ff7e6b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}main{max-width:1180px;margin:auto;padding:20px}.hero,.panel{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px;margin-bottom:14px}.hero{position:sticky;top:0;z-index:3;box-shadow:0 6px 18px #0002}.hero h1{font-size:19px;margin:0 0 8px}.meta{color:var(--dim);font:12px ui-monospace,monospace}.buttons{display:flex;gap:7px;flex-wrap:wrap;margin-top:12px}button{border:1px solid var(--line);border-radius:9px;background:transparent;color:var(--ink);padding:7px 11px;cursor:pointer}button.active{background:var(--accent);border-color:var(--accent);color:white}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}@media(max-width:820px){.grid{grid-template-columns:1fr}}h2{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--dim);margin:0 0 10px}.state{display:grid;grid-template-columns:auto 1fr;gap:5px 14px}.state dt{color:var(--dim)}.state dd{margin:0;font-family:ui-monospace,monospace}.chips{display:flex;gap:7px;flex-wrap:wrap}.chip{border:1px solid var(--line);border-radius:999px;padding:5px 9px;font:12px ui-monospace,monospace}.chip.on{border-color:var(--hot);color:var(--hot);background:color-mix(in srgb,var(--hot) 12%,transparent)}table{border-collapse:collapse;width:100%}th,td{padding:6px 8px;border-bottom:1px solid var(--line);text-align:left}th{color:var(--dim);font-size:11px;text-transform:uppercase}.mono{font-family:ui-monospace,monospace}.controls{display:grid;grid-template-columns:1fr auto;gap:8px 12px;align-items:center}.controls input{width:100%}.value{font:12px ui-monospace,monospace;min-width:78px;text-align:right}.status-ok{color:var(--ok)}.status-bad{color:var(--bad)}.note{color:var(--dim);font-size:12px}.source{font:12px ui-monospace,monospace;color:var(--dim);word-break:break-all}
</style></head><body><main>
<section class="hero"><h1>Тест-ДПЛС · модель, собранная интроспекцией прошивки</h1><div class="meta" id="meta"></div><div class="buttons" id="modeButtons"></div></section>
<div class="grid">
<section class="panel"><h2>Командуемое состояние</h2><dl class="state" id="state"></dl><h2 style="margin-top:14px">Активные выводы</h2><div class="chips" id="activePins"></div></section>
<section class="panel"><h2>Таймауты и защита</h2><table id="timing"></table><p class="note">Автовозврат в «Норму» моделируется по значениям из <span class="mono">dpls_server.h</span>.</p></section>
<section class="panel"><h2>ADC и гистерезис</h2><div class="controls"><label for="line">Напряжение ДПЛС</label><span class="value" id="lineValue"></span><input id="line" type="range" min="0" max="30000" step="100" value="24000"><span></span><label for="vcap">Напряжение резерва</label><span class="value" id="vcapValue"></span><input id="vcap" type="range" min="0" max="6000" step="50" value="4500"><span></span></div><dl class="state" id="adcState" style="margin-top:14px"></dl></section>
<section class="panel"><h2>Распиновка</h2><table><thead><tr><th>Символ</th><th>GPIO</th><th>Роль</th><th>Тип</th></tr></thead><tbody id="pins"></tbody></table></section>
</div><section class="panel"><h2>Источник данных</h2><div id="sources"></div><p class="note">HTML не хранится в репозитории. Он создаётся release workflow из текущих исходников.</p></section>
</main><script id="model" type="application/json">__MODEL_JSON__</script><script>
const model=JSON.parse(document.getElementById('model').textContent);let current=model.modes[0];let linePresent=true,reserveLow=false,autoIso=false;const fmtMs=ms=>ms>=60000?`${ms/60000} мин`:`${ms/1000} с`;
document.getElementById('meta').textContent=`FW ${model.firmware} · commit ${model.commit} · ADC ${model.adc.sequential?'последовательный':'проверь реализацию'}`;const buttons=document.getElementById('modeButtons');model.modes.forEach(m=>{const b=document.createElement('button');b.textContent=m.label;b.onclick=()=>{current=m;renderMode()};b.dataset.id=m.id;buttons.appendChild(b)});
function renderMode(){[...buttons.children].forEach(b=>b.classList.toggle('active',b.dataset.id===current.id));const deadline=current.value===0?'нет':fmtMs(model.timing.modeTimeoutMs);document.getElementById('state').innerHTML=`<dt>Режим</dt><dd>${current.label}</dd><dt>Код</dt><dd>${current.value}</dd><dt>Автовозврат</dt><dd>${deadline}</dd><dt>Break-before-make</dt><dd>да</dd>`;const names=[...current.controls,...current.indicators];document.getElementById('activePins').innerHTML=model.pins.map(p=>`<span class="chip ${names.includes(p.name)?'on':''}">${p.pin} · ${p.label}</span>`).join('')}
const timing=[['Режим испытания',model.timing.modeTimeoutMs],['Без активности сессии',model.timing.sessionTimeoutMs],['Идентификация',model.timing.identifyMaxMs],['Полупериод мигания',model.timing.identifyBlinkMs],['Блокировка аутентификации',model.timing.authBlockMs]];document.getElementById('timing').innerHTML=timing.map(([n,v])=>`<tr><td>${n}</td><td class="mono">${fmtMs(v)}</td></tr>`).join('')+`<tr><td>Ошибок до блокировки</td><td class="mono">${model.timing.authAttempts}</td></tr>`;
document.getElementById('pins').innerHTML=model.pins.map(p=>`<tr><td class="mono">${p.name}</td><td class="mono">${p.pin}</td><td>${p.label}</td><td>${p.kind}</td></tr>`).join('');document.getElementById('sources').innerHTML=model.sources.map(s=>`<div class="source">${s}</div>`).join('');
function renderAdc(){const line=+document.getElementById('line').value,vcap=+document.getElementById('vcap').value;if(linePresent&&line<model.adc.lineAbsentMv)linePresent=false;else if(!linePresent&&line>model.adc.linePresentMv)linePresent=true;if(!reserveLow&&vcap<model.adc.reserveLowMv)reserveLow=true;else if(reserveLow&&vcap>model.adc.reserveOkMv)reserveLow=false;if(!autoIso&&line<model.adc.autoIsoTripMv&&current.value===0)autoIso=true;else if(autoIso&&line>model.adc.autoIsoClearMv)autoIso=false;document.getElementById('lineValue').textContent=`${(line/1000).toFixed(1)} В`;document.getElementById('vcapValue').textContent=`${(vcap/1000).toFixed(2)} В`;document.getElementById('adcState').innerHTML=`<dt>Источник питания</dt><dd class="${linePresent?'status-ok':'status-bad'}">${linePresent?'линия':'резерв'}</dd><dt>Низкий резерв</dt><dd class="${reserveLow?'status-bad':'status-ok'}">${reserveLow?'да':'нет'}</dd><dt>Автоизоляция КЗ</dt><dd class="${autoIso?'status-bad':'status-ok'}">${autoIso?'активна':'нет'}</dd><dt>Окно усреднения</dt><dd>${model.adc.window} отсчётов</dd><dt>Период запуска</dt><dd>${model.adc.decimate} × системный тик</dd>`}
document.getElementById('line').oninput=renderAdc;document.getElementById('vcap').oninput=renderAdc;renderMode();renderAdc();
</script></body></html>'''


def render(model: dict[str, object]) -> str:
    payload = json.dumps(model, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    return HTML_TEMPLATE.replace("__MODEL_JSON__", payload)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    model = extract_model(root)
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(model), encoding="utf-8")
    print(f"generated {output} from {len(model['sources'])} source files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
