# -*- coding: utf-8 -*-
"""Detect Bitap keyboard key centers from a screenshot (read-only helper).

Usage: python detect_keys.py <screenshot.png>
Prints one JSON object with key name -> "x y" coordinates.
Works for portrait (1080x2400) and landscape (2400x1080) based on image size.
Detection: white key rows are found by counting near-white pixels per row;
row 1 must contain 10 white key runs, row 2 has 9, row 3 has 7 letter keys.
All reference coordinates below were measured on 1080x2400 / 2400x1080;
a vertical delta is applied if the keyboard window is shifted.
"""
import json
import sys
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8")

REF_PORTRAIT = {
    "row1_y": 1673, "row2_y": 1849, "row3_y": 2025,
    "q": 61, "w": 168, "e": 275, "r": 382, "t": 488, "y": 594, "u": 700, "i": 806, "o": 912, "p": 1018,
    "a": 106, "s": 215, "d": 324, "f": 433, "g": 541, "h": 650, "j": 757, "k": 866, "l": 974,
    "z": 222, "x": 328, "c": 434, "v": 540, "b": 646, "n": 752, "m": 858,
    "shift": 88, "backspace": 991,
    "123": 69, "zh_en": 186, "comma": 293, "space": 590, "period": 887, "enter": 1004,
    "expand": 740, "ai": 871, "gear": 1004,
}
REF_LANDSCAPE = {
    "row1_y": 521, "row2_y": 659, "row3_y": 797,
    "q": 216, "w": 445, "e": 674, "r": 903, "t": 1132, "y": 1361, "u": 1590, "i": 1819, "o": 2048, "p": 2277,
    "a": 268, "s": 512, "d": 756, "f": 1000, "g": 1245, "h": 1490, "j": 1735, "k": 1980, "l": 2225,
    "z": 560, "x": 789, "c": 1018, "v": 1247, "b": 1476, "n": 1705, "m": 1934,
    "shift": 274, "backspace": 2220,
    "123": 161, "zh_en": 311, "comma": 413, "space": 1355, "period": 2161, "enter": 2328,
    "expand": 2070, "ai": 2191, "gear": 2324,
}


def is_white(c):
    r, g, b = c[:3]
    return r > 250 and g > 250 and b > 250


def runs_at(px, y, width, min_w=40):
    out = []
    start = None
    for x in range(width):
        if is_white(px[x, y]):
            if start is None:
                start = x
        else:
            if start is not None:
                if x - start > min_w:
                    out.append((start, x - 1, (start + x - 1) // 2))
                start = None
    if start is not None and width - start > min_w:
        out.append((start, width - 1, (start + width - 1) // 2))
    # drop left-edge system window artifact in landscape (x<100); portrait q is at x=61
    if width > 3000 or width > 2000:
        return [r for r in out if r[2] >= 100]
    return out


def find_bands(px, width, height):
    bands = []
    cur = None
    for y in range(max(0, height // 4), height):
        cnt = sum(1 for x in range(0, width, 5) if is_white(px[x, y]))
        if cnt > 60:
            if cur is None:
                cur = [y, y, cnt]
            else:
                cur[1] = y
                cur[2] = max(cur[2], cnt)
        else:
            if cur:
                bands.append(cur)
                cur = None
    if cur:
        bands.append(cur)
    return bands


def main():
    path = sys.argv[1]
    im = Image.open(path).convert("RGB")
    width, height = im.size
    px = im.load()
    landscape = width > height
    ref = REF_LANDSCAPE if landscape else REF_PORTRAIT
    bands = find_bands(px, width, height)
    row1 = row2 = row3 = None
    rows = []
    for y1, y2, _ in bands:
        if y2 - y1 < 40:
            continue
        sample_y = y1 + max(6, (y2 - y1) // 8)
        rs = runs_at(px, sample_y, width)
        rows.append((y1, y2, rs))
    # row1 = first row with 10 runs, row2 next with 9, row3 next with 7
    for i, (y1, y2, rs) in enumerate(rows):
        if row1 is None and len(rs) == 10:
            row1 = (y1, y2, rs)
            continue
        if row1 is not None and row2 is None and len(rs) == 9:
            row2 = (y1, y2, rs)
            continue
        if row2 is not None and row3 is None and len(rs) == 7:
            row3 = (y1, y2, rs)
            break
    if not row1 or not row2 or not row3:
        print(json.dumps({"error": "cannot detect keyboard rows", "rows": [(r[0], r[1], len(r[2])) for r in rows]}))
        return 1
    row1_y = (row1[0] + row1[1]) // 2
    dy = row1_y - ref["row1_y"]
    result = {}
    letters1 = "qwertyuiop"
    letters2 = "asdfghjkl"
    letters3 = "zxcvbnm"
    for name, center in zip(letters1, [r[2] for r in row1[2]]):
        result[name] = f"{center} {row1_y}"
    row2_y = (row2[0] + row2[1]) // 2
    for name, center in zip(letters2, [r[2] for r in row2[2]]):
        result[name] = f"{center} {row2_y}"
    row3_y = (row3[0] + row3[1]) // 2
    row3_centers = [r[2] for r in row3[2]]
    for name, center in zip(letters3, row3_centers):
        result[name] = f"{center} {row3_y}"
    # function keys keep reference x, shift vertically
    for name in ["shift", "backspace"]:
        result[name] = f"{ref[name]} {row3_y}"
    # bottom row y: reference row3 + row pitch; keep reference bottom y plus dy
    pitch = row1_y - row2_y
    bottom_y = row3_y - pitch
    for name in ["123", "zh_en", "comma", "space", "period", "enter"]:
        result[name] = f"{ref[name]} {bottom_y}"
    # candidate row is above row1: reference candidate y plus the same vertical delta
    if landscape:
        cand_y = 390 + dy
    else:
        cand_y = 1516 + dy
    for name in ["expand", "ai", "gear"]:
        result[name] = f"{ref[name]} {cand_y}"
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
