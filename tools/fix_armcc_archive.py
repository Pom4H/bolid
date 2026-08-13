#!/usr/bin/env python3
"""Make ARM Compiler static archives acceptable to GNU ld.

ARMCC ELF objects often keep STB_LOCAL symbols after sh_info. GNU ld 10+
rejects that with ".symtab local symbol at index N (>= sh_info of M)".
This rewrites each ELF member in place: locals first (index 0 stays the
undefined dummy), remaps REL/RELA symbol indices, and sets sh_info.
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

ELF32_EHDR = struct.Struct("<16sHHIIIIIHHHHHH")
ELF32_SHDR = struct.Struct("<IIIIIIIIII")
ELF32_SYM = struct.Struct("<IIIBBH")
ELF32_REL = struct.Struct("<II")
ELF32_RELA = struct.Struct("<IIi")

SHT_RELA = 4
SHT_SYMTAB = 2
SHT_REL = 9
STB_LOCAL = 0


def _st_bind(st_info: int) -> int:
    return st_info >> 4


def _shdr(blob: bytearray, e_shoff: int, e_shentsize: int, index: int) -> tuple[int, tuple[int, ...]]:
    off = e_shoff + index * e_shentsize
    return off, ELF32_SHDR.unpack_from(blob, off)


def fix_elf32(blob: bytearray) -> bool:
    ident, _e_type, _e_machine, _e_version, _e_entry, _e_phoff, e_shoff, _e_flags, _e_ehsize, _e_phentsize, _e_phnum, e_shentsize, e_shnum, _e_shstrndx = ELF32_EHDR.unpack_from(
        blob, 0
    )
    if ident[:4] != b"\x7fELF" or ident[4] != 1 or ident[5] != 1:
        return False
    if e_shentsize != ELF32_SHDR.size or e_shnum == 0:
        return False

    changed = False
    for sh_index in range(e_shnum):
        sh_off, sh = _shdr(blob, e_shoff, e_shentsize, sh_index)
        sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, sh_info, sh_addralign, sh_entsize = sh
        if sh_type != SHT_SYMTAB or sh_entsize != ELF32_SYM.size or sh_size < ELF32_SYM.size:
            continue
        nsym = sh_size // ELF32_SYM.size
        symbols = [
            ELF32_SYM.unpack_from(blob, sh_offset + i * ELF32_SYM.size)
            for i in range(nsym)
        ]
        locals = [i for i in range(1, nsym) if _st_bind(symbols[i][3]) == STB_LOCAL]
        others = [i for i in range(1, nsym) if _st_bind(symbols[i][3]) != STB_LOCAL]
        new_order = [0] + locals + others
        new_info = 1 + len(locals)
        if new_order == list(range(nsym)) and sh_info == new_info:
            continue
        remap = {old: new for new, old in enumerate(new_order)}
        blob[sh_offset : sh_offset + sh_size] = b"".join(
            ELF32_SYM.pack(*symbols[old]) for old in new_order
        )
        ELF32_SHDR.pack_into(
            blob,
            sh_off,
            sh_name,
            sh_type,
            sh_flags,
            sh_addr,
            sh_offset,
            sh_size,
            sh_link,
            new_info,
            sh_addralign,
            sh_entsize,
        )
        for rel_index in range(e_shnum):
            _rel_off, rel = _shdr(blob, e_shoff, e_shentsize, rel_index)
            _n, rel_type, _f, _a, rel_offset, rel_size, rel_link, _info, _al, rel_entsize = rel
            if rel_link != sh_index:
                continue
            if rel_type == SHT_REL and rel_entsize == ELF32_REL.size:
                for i in range(rel_size // ELF32_REL.size):
                    pos = rel_offset + i * ELF32_REL.size
                    r_offset, r_info = ELF32_REL.unpack_from(blob, pos)
                    old_sym = r_info >> 8
                    if old_sym not in remap:
                        continue
                    ELF32_REL.pack_into(
                        blob, pos, r_offset, (remap[old_sym] << 8) | (r_info & 0xFF)
                    )
            elif rel_type == SHT_RELA and rel_entsize == ELF32_RELA.size:
                for i in range(rel_size // ELF32_RELA.size):
                    pos = rel_offset + i * ELF32_RELA.size
                    r_offset, r_info, r_addend = ELF32_RELA.unpack_from(blob, pos)
                    old_sym = r_info >> 8
                    if old_sym not in remap:
                        continue
                    ELF32_RELA.pack_into(
                        blob,
                        pos,
                        r_offset,
                        (remap[old_sym] << 8) | (r_info & 0xFF),
                        r_addend,
                    )
        changed = True
    return changed


def fix_archive(src: Path, dst: Path) -> int:
    data = bytearray(src.read_bytes())
    if not data.startswith(b"!<arch>\n"):
        raise SystemExit(f"{src}: not an ar archive")
    off = 8
    fixed = 0
    while off + 60 <= len(data):
        hdr = data[off : off + 60]
        try:
            size = int(hdr[48:58].decode("ascii").strip())
        except ValueError as exc:
            raise SystemExit(f"{src}: bad ar header at {off}") from exc
        body_off = off + 60
        body_end = body_off + size
        if body_end > len(data):
            raise SystemExit(f"{src}: truncated member at {off}")
        if data[body_off : body_off + 4] == b"\x7fELF":
            member = bytearray(data[body_off:body_end])
            if fix_elf32(member):
                data[body_off:body_end] = member
                fixed += 1
        off = body_end + (size & 1)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(data)
    return fixed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("src", type=Path)
    parser.add_argument("dst", type=Path)
    args = parser.parse_args()
    n = fix_archive(args.src, args.dst)
    sys.stdout.write(f"{args.dst}: reordered {n} ELF member(s)\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
