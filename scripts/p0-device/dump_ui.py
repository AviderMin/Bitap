#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""Dump current Android UI (uiautomator) and print a compact node summary.

Usage:
  python dump_ui.py --out D:\path\ui.xml [--all]
  python dump_ui.py --find "空格"        # print nodes whose text/content-desc contains keyword

The raw XML is saved to --out (default .tmp/p0final/ui-last.xml) AND pulled to that path.
"""
import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("BITAP_DEVICE", "fe4ab855")
ADB = os.environ.get("ADB", "adb")
REMOTE = "/sdcard/p0_ui_dump.xml"


def adb(*args, timeout=30):
    cmd = [ADB, "-s", SERIAL, *args]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout or ""), (p.stderr or "")


def dump(out_path):
    rc, out, err = adb("shell", "uiautomator", "dump", REMOTE)
    if rc != 0 or "dumped" not in out.lower():
        # try exec-out alternative
        rc2, out2, err2 = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        if rc2 == 0 and "<hierarchy" in out2:
            xml_text = out2[out2.find("<?xml"):]
            with open(out_path, "w", encoding="utf-8") as f:
                f.write(xml_text)
            return xml_text
        sys.stderr.write(f"uiautomator dump failed rc={rc} out={out!r} err={err!r}\n{err2}\n")
        sys.exit(2)
    rc, out, err = adb("pull", REMOTE, out_path.replace("\\", "/"))
    if rc != 0:
        sys.stderr.write(f"adb pull failed: {err}\n")
        sys.exit(3)
    with open(out_path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "..", ".tmp", "p0final", "ui-last.xml"))
    ap.add_argument("--find", default=None)
    ap.add_argument("--all", action="store_true", help="print nodes without text/desc too")
    args = ap.parse_args()
    out_path = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    xml_text = dump(out_path)
    try:
        root = ET.fromstring(xml_text)
    except Exception as e:
        sys.stderr.write(f"XML parse error: {e}\n")
        sys.exit(4)

    rows = []
    def walk(node):
        cls = node.get("class", "")
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        bounds = node.get("bounds", "")
        focused = node.get("focused", "false")
        selected = node.get("selected", "false")
        rid = node.get("resource-id", "")
        interesting = bool(text or desc) or cls in (
            "android.widget.EditText", "android.widget.Button", "android.widget.ImageButton",
        )
        if args.find:
            kw = args.find
            if kw in text or kw in desc:
                rows.append((cls, text, desc, bounds, focused, selected, rid))
        elif args.all or interesting:
            rows.append((cls, text, desc, bounds, focused, selected, rid))
        for ch in list(node):
            walk(ch)
    walk(root)

    print(f"# rotation={root.get('rotation')} nodes={len(rows)} xml={out_path}")
    for cls, text, desc, bounds, focused, selected, rid in rows:
        tag = cls.split(".")[-1]
        extra = ""
        if focused == "true":
            extra += " FOCUSED"
        if selected == "true":
            extra += " SELECTED"
        print(f"{tag:14s} text={text!r} desc={desc!r} bounds={bounds}{extra} id={rid}")


if __name__ == "__main__":
    main()
