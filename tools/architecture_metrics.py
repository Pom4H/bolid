#!/usr/bin/env python3
"""Small dependency-free structural complexity report for Kotlin/C sources.

This is intentionally a repository-local proxy, not a claim to reproduce Sonar.
It tracks the things this refactor is trying to delete: executable LOC, branch
points, boolean decision terms and nesting-weighted control flow.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

CONTROL = re.compile(r"\b(if|when|for|while|catch|switch)\b")
BOOL = re.compile(r"(&&|\|\|)")
LINE_COMMENT = re.compile(r"//.*$")


def measure(path: Path) -> dict[str, int | str]:
    loc = 0
    branch_points = 0
    boolean_terms = 0
    cognitive_proxy = 0
    depth = 0
    block_comment = False

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw
        if block_comment:
            if "*/" not in line:
                continue
            line = line.split("*/", 1)[1]
            block_comment = False
        while "/*" in line:
            before, after = line.split("/*", 1)
            if "*/" in after:
                line = before + after.split("*/", 1)[1]
            else:
                line = before
                block_comment = True
                break
        line = LINE_COMMENT.sub("", line).strip()
        if not line:
            continue
        loc += 1
        closing = len(re.match(r"^}*", line).group(0))
        decision_depth = max(0, depth - closing)
        controls = len(CONTROL.findall(line))
        bools = len(BOOL.findall(line))
        branch_points += controls
        boolean_terms += bools
        if controls:
            cognitive_proxy += controls * (1 + decision_depth)
        cognitive_proxy += bools
        depth += line.count("{") - line.count("}")
        depth = max(0, depth)

    return {
        "file": str(path),
        "loc": loc,
        "branch_points": branch_points,
        "boolean_terms": boolean_terms,
        "cognitive_proxy": cognitive_proxy,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    rows = [measure(path) for path in args.paths]
    print(json.dumps(rows, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
