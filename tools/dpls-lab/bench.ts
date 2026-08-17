import { spawn } from "bun";
import { existsSync } from "node:fs";
import { createConnection } from "node:net";
import { join, resolve } from "node:path";
import process from "node:process";
import { applyBenchScript, benchSockPath, emptyNet, parseBenchArgv } from "./src/benchNet";

const ROOT = resolve(import.meta.dir, "../..");
const LAB = import.meta.dir;

function help(): string {
  return [
    "PB-03F-Kit bench — провода на площадки. Глаза: тот же tools/dpls_board.sh.",
    "",
    "  bash tools/dpls_board.sh                     # TUI + сокет",
    "  bash tools/dpls_board.sh --listen            # сокет без TTY",
    "  bash tools/dpls_bench.sh TIE P20 12V",
    "  bash tools/dpls_bench.sh tie p20 12v         # регистр не важен",
    "  bash tools/dpls_bench.sh TIE P20 12V TIE P23 GND",
    "  bash tools/dpls_bench.sh SCENE vcap",
    "  bash tools/dpls_bench.sh --once               # кадр сцены line",
    "  bash tools/dpls_bench.sh --once scene vcap",
    "",
    "  TIE <Pxx> <GND|3V3|5V|12V>   только площадка↔рельс, не pad-to-pad",
    "  OPEN <Pxx>",
    "  SCENE bare    все OPEN",
    "  SCENE line    P20/P15/P24=12V (снимок)",
    "  SCENE vcap    line + P23=GND (снимок)",
    "  SCENE reset   line + P34=3V3 (снимок)",
    "  CLEAR / NET",
    "",
  ].join("\n");
}

function send(path: string, line: string): Promise<string> {
  return new Promise((resolveSend, reject) => {
    const socket = createConnection(path);
    let buffer = "";
    socket.on("connect", () => {
      socket.write(`${line}\n`);
    });
    socket.on("data", (chunk: Buffer) => {
      buffer += chunk.toString("utf8");
      if (buffer.includes("\n")) {
        socket.end();
        resolveSend(buffer.trim());
      }
    });
    socket.on("error", reject);
  });
}

function validate(lines: string[]): string | null {
  const net = emptyNet();
  const reply = applyBenchScript(net, lines.join(";"));
  return reply.ok ? null : reply.line;
}

async function runOnce(lines: string[]): Promise<void> {
  const cli = join(LAB, "cli.ts");
  const bunArgs = ["bun", cli, "--once"];
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined) env[key] = value;
  }
  env.DPLS_SIMULATOR = process.env.DPLS_SIMULATOR ?? join(ROOT, "firmware/build/dpls_simulator");
  const mutating = lines.filter((line) => {
    const verb = line.split(" ")[0]?.toUpperCase() ?? "";
    return verb !== "NET" && verb !== "SHOW";
  });
  if (mutating.length > 0) env.DPLS_BENCH_BOOT = lines.join(";");
  const proc = spawn(bunArgs, {
    cwd: LAB,
    stdout: "inherit",
    stderr: "inherit",
    env,
  });
  const code = await proc.exited;
  if (code !== 0) process.exitCode = code;
}

function noTuiHint(path: string, rest: string[]): string {
  const once = rest.length === 0 ? "--once" : `--once ${rest.join(" ")}`;
  return [
    "нет TUI — сначала: bash tools/dpls_board.sh",
    `или один кадр: bash tools/dpls_bench.sh ${once}`,
    `сокет: ${path}`,
    "",
  ].join("\n");
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.includes("-h") || args.includes("--help")) {
    process.stdout.write(help());
    return;
  }
  const once = args[0] === "--once";
  const rest = once ? args.slice(1) : args;
  if (!once && rest.length === 0) {
    process.stdout.write(help());
    return;
  }
  const parsed = parseBenchArgv(rest);
  if (!parsed.ok) {
    process.stderr.write(`${parsed.line}\n`);
    process.exitCode = 1;
    return;
  }
  if (parsed.lines.length > 0) {
    const bad = validate(parsed.lines);
    if (bad !== null) {
      process.stderr.write(`${bad}\n`);
      process.exitCode = 1;
      return;
    }
  }
  if (once) {
    await runOnce(parsed.lines);
    return;
  }
  const path = benchSockPath();
  if (!existsSync(path)) {
    process.stderr.write(noTuiHint(path, rest));
    process.exitCode = 1;
    return;
  }
  try {
    const reply = await send(path, parsed.lines.join(";"));
    process.stdout.write(`${reply}\n`);
    if (!reply.startsWith("OK ")) process.exitCode = 1;
  } catch {
    process.stderr.write(
      [
        `сокет есть, но TUI не слушает`,
        "перезапустите: bash tools/dpls_board.sh",
        `или кадр: bash tools/dpls_bench.sh --once ${rest.join(" ")}`.trimEnd(),
        `файл: ${path}`,
        "",
      ].join("\n"),
    );
    process.exitCode = 1;
  }
}

await main();
