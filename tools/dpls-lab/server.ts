import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { DeviceHub } from "./hub";

const ROOT = resolve(import.meta.dir, "../..");
const SIM =
  process.env.DPLS_SIMULATOR ??
  (existsSync(join(ROOT, "Firmware/build/dpls_simulator"))
    ? join(ROOT, "Firmware/build/dpls_simulator")
    : join(ROOT, "firmware/build/dpls_simulator"));
const PORT = Number(process.env.DPLS_LAB_PORT ?? "8787");
const PHONE_DIST = [
  join(ROOT, "mobile/web/build/dist/wasmJs/productionExecutable"),
  join(ROOT, "mobile/web/build/dist/wasmJs/developmentExecutable"),
].find((path) => existsSync(join(path, "index.html")));

function mimeType(path: string): string {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
  if (path.endsWith(".mjs")) return "text/javascript; charset=utf-8";
  if (path.endsWith(".wasm")) return "application/wasm";
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".map")) return "application/json; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  return "application/octet-stream";
}

function phoneAsset(pathname: string): Response | null {
  if (PHONE_DIST === undefined) {
    return new Response("Compose app is not built. Run :web:wasmJsBrowserDistribution", { status: 503 });
  }
  const root = resolve(PHONE_DIST);
  const rel = pathname === "/phone" || pathname === "/phone/" ? "index.html" : pathname.slice("/phone/".length);
  if (rel.includes("..")) return new Response("bad path", { status: 400 });
  const file = resolve(root, rel);
  if (!file.startsWith(root) || !existsSync(file)) return null;
  return new Response(Bun.file(file), { headers: { "content-type": mimeType(file) } });
}

if (!existsSync(SIM)) {
  throw new Error(`dpls_simulator not found at ${SIM}`);
}

const hub = new DeviceHub(SIM, import.meta.dir);
hub.spawnSim({});

setInterval(() => hub.tickAll(), 80);

const labRoot = import.meta.dir;
const decoder = new TextDecoder();

Bun.serve({
  port: PORT,
  async fetch(req, server) {
    const url = new URL(req.url);
    if (url.pathname === "/ws") {
      if (!server.upgrade(req)) return new Response("websocket upgrade failed", { status: 500 });
      return;
    }
    if (url.pathname === "/app.js") {
      const built = await Bun.build({
        entrypoints: [join(labRoot, "src/main.tsx")],
        target: "browser",
      });
      if (!built.success || built.outputs[0] === undefined) {
        return new Response(built.logs.map((item) => String(item)).join("\n"), {
          status: 500,
          headers: { "content-type": "text/plain; charset=utf-8" },
        });
      }
      return new Response(await built.outputs[0].text(), {
        headers: { "content-type": "text/javascript; charset=utf-8" },
      });
    }
    if (url.pathname === "/" || url.pathname === "/index.html") {
      return new Response(Bun.file(join(labRoot, "index.html")));
    }
    if (url.pathname === "/phone" || url.pathname.startsWith("/phone/")) {
      return phoneAsset(url.pathname) ?? new Response("not found", { status: 404 });
    }
    return new Response("not found", { status: 404 });
  },
  websocket: {
    open(ws) {
      hub.clients.add(ws);
      hub.onClientJoin();
    },
    message(_ws, data) {
      hub.handle(typeof data === "string" ? data : decoder.decode(data));
    },
    close(ws) {
      hub.clients.delete(ws);
      if (hub.clients.size === 0) hub.onLastClientLeft();
    },
  },
});

console.log(`DPLS lab http://127.0.0.1:${PORT}${PHONE_DIST === undefined ? " (phone UI not built)" : ""}`);
