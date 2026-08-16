import { spawn, type Subprocess } from "bun";
import { existsSync } from "node:fs";
import { join } from "node:path";

export type LineStdin = {
  write(data: string | Uint8Array): number | Promise<number>;
  flush?: () => number | Promise<number>;
};

const decoder = new TextDecoder();

export function bleToolPath(labRoot: string): string {
  return join(labRoot, "native/dpls-ble");
}

async function readLines(stdout: ReadableStream<Uint8Array>, onLine: (line: string) => void): Promise<void> {
  const reader = stdout.getReader();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let nl = buffer.indexOf("\n");
    while (nl >= 0) {
      const line = buffer.slice(0, nl).replace(/\r$/, "");
      buffer = buffer.slice(nl + 1);
      if (line.length > 0) onLine(line);
      nl = buffer.indexOf("\n");
    }
  }
}

function write(stdin: LineStdin, line: string): void {
  stdin.write(`${line}\n`);
  stdin.flush?.();
}

export class NativeBle {
  private readonly tool: string;
  private central: { proc: Subprocess; stdin: LineStdin } | null = null;
  private air: { proc: Subprocess; stdin: LineStdin } | null = null;

  constructor(labRoot: string) {
    this.tool = bleToolPath(labRoot);
  }

  available(): boolean {
    return existsSync(this.tool);
  }

  startCentral(onLine: (line: string) => void): boolean {
    if (this.central !== null) return true;
    if (!this.available()) return false;
    this.stopPeripheral();
    const proc = spawn([this.tool, "central"], { stdin: "pipe", stdout: "pipe", stderr: "inherit" });
    if (proc.stdin === undefined || proc.stdout === undefined) return false;
    this.central = { proc, stdin: proc.stdin };
    void readLines(proc.stdout, onLine);
    return true;
  }

  writeCentral(line: string): void {
    if (this.central === null) return;
    write(this.central.stdin, line);
  }

  stopCentral(): void {
    if (this.central === null) return;
    write(this.central.stdin, "QUIT");
    this.central.proc.kill();
    this.central = null;
  }

  startPeripheral(
    opts: { name: string; deviceId: number; firmware: string; status: number },
    onLine: (line: string) => void,
  ): boolean {
    this.stopPeripheral();
    this.stopCentral();
    if (!this.available()) return false;
    const proc = spawn(
      [
        this.tool,
        "peripheral",
        "--name",
        opts.name,
        "--id",
        `0x${opts.deviceId.toString(16)}`,
        "--fw",
        opts.firmware,
        "--status",
        String(opts.status),
      ],
        { stdin: "pipe", stdout: "pipe", stderr: "inherit" },
    );
    if (proc.stdin === undefined || proc.stdout === undefined) return false;
    this.air = { proc, stdin: proc.stdin };
    void readLines(proc.stdout, onLine);
    return true;
  }

  writePeripheral(line: string): void {
    if (this.air === null) return;
    write(this.air.stdin, line);
  }

  stopPeripheral(): void {
    if (this.air === null) return;
    write(this.air.stdin, "QUIT");
    this.air.proc.kill();
    this.air = null;
  }
}
