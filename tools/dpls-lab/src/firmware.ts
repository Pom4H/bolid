export type FirmwareRelease = {
  version: string;
  label: string;
};

/** Shipped Test-DPLS firmware versions from git tags and current tree. */
export const FIRMWARE_RELEASES: readonly FirmwareRelease[] = [
  { version: "1.4.1", label: "1.4.1 · текущая" },
  { version: "1.4.0", label: "1.4.0 · релиз" },
  { version: "1.3.0", label: "1.3.0 · v1.3.0-rc.1" },
  { version: "1.2.1", label: "1.2.1 · релиз" },
  { version: "1.2.0", label: "1.2.0 · релиз" },
  { version: "1.1.3", label: "1.1.3 · v1.1.3-rc.1" },
  { version: "1.1.2", label: "1.1.2 · v1.1.2-rc.1" },
  { version: "1.1.1", label: "1.1.1 · v1.1.1-rc.1" },
  { version: "1.1.0", label: "1.1.0 · релиз" },
];

/** Must match `DPLS_FW_VERSION_*` in `firmware/include/dpls_server.h`. */
export const DEFAULT_FIRMWARE = "1.4.1";

export function firmwareChoices(seen: Array<string | null>): FirmwareRelease[] {
  const known = new Set(FIRMWARE_RELEASES.map((item) => item.version));
  const extra: FirmwareRelease[] = [];
  for (const value of seen) {
    if (value === null || value.length === 0 || known.has(value)) continue;
    known.add(value);
    extra.push({ version: value, label: `${value} · с платы` });
  }
  return extra.length === 0 ? [...FIRMWARE_RELEASES] : [...FIRMWARE_RELEASES, ...extra];
}
