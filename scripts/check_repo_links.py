"""Check maintained Markdown links and docs/legal references without network IO."""
from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".md", ".py", ".ps1", ".kt", ".java", ".txt"}
LINK = re.compile(r"\]\(([^\s)]+)(?:\s+\"[^\"]*\")?\)")
REFERENCE = re.compile(r"(?<![\w/\\])(?:docs|legal)/[A-Za-z0-9_./-]+\.(?:md|txt)")


def main():
    files = [ROOT / "README.md", ROOT / "THIRD_PARTY_NOTICES.md"]
    for directory in ("docs", "legal", "eval", "scripts", "app/src"):
        files.extend(path for path in (ROOT / directory).rglob("*")
                     if path.is_file() and path.suffix in SUFFIXES and "__pycache__" not in path.parts)
    errors = set()
    for path in files:
        content = path.read_text(encoding="utf-8-sig")
        for ref in REFERENCE.findall(content):
            if not (ROOT / ref).is_file():
                errors.add((path.relative_to(ROOT).as_posix(), ref))
        if path.suffix == ".md":
            for target in LINK.findall(content):
                target = unquote(target.strip("<>"))
                if ":" in target or target.startswith("#"):
                    continue
                target = target.split("#")[0]
                if Path(target).suffix not in {".md", ".txt"}:
                    continue
                if not (path.parent / target).is_file():
                    errors.add((path.relative_to(ROOT).as_posix(), target))
    for filename, target in sorted(errors):
        print(f"BROKEN {filename}: {target}")
    print(f"checkedFiles={len(files)} brokenReferences={len(errors)}")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
