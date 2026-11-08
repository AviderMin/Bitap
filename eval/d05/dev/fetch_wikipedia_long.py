#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fetch long complete Chinese sentences from Chinese Wikipedia (CC BY-SA 4.0).

This creates a frozen source snapshot for the D05 long-sentence supplement.
The snapshot records pageid, oldid, title, stable URL and the sentence text
(converted to simplified Chinese with zhconv). The D05 builder consumes the
snapshot, never the live API, so generated corpus hashes are reproducible once
the snapshot is committed.

Run from the repository root:
    python eval/d05/dev/fetch_wikipedia_long.py --target 250 --out eval/d05/dev/wikipedia_long_source.tsv

The API is used only to obtain text; no PinyinEngine is called. The snapshot is
an evaluation-only source and is never copied into the production dictionary.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import zhconv

API = "https://zh.wikipedia.org/w/api.php"
USER_AGENT = "Bitap-eval/1.0 (offline corpus research; local evaluation only)"
PUNCT = set("，。！？、；：…—～·")
CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
TERMINAL = set("。！？")


def cjk(ch: str) -> bool:
    return 0x4E00 <= ord(ch) <= 0x9FFF


def allowed_char(ch: str) -> bool:
    return cjk(ch) or ch in PUNCT


def norm_len(text: str) -> int:
    return sum(1 for ch in text if cjk(ch))


def api_get(params: dict) -> dict:
    params = dict(params)
    params.update({"format": "json", "formatversion": "2"})
    url = API + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last_error = None
    for attempt in range(6):
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code not in (429, 503):
                raise
        except urllib.error.URLError as error:
            last_error = error
        time.sleep(3.0 * (attempt + 1))
    raise SystemExit("Wikipedia API retry exhausted: " + str(last_error))


def clean_sentences(extract: str) -> list[str]:
    if not extract:
        return []
    text = zhconv.convert(extract.replace("\r", " ").replace("\n", " "), "zh-cn")
    # Drop section headings and simple markup.
    text = re.sub(r"={2,}[^=]*={2,}", " ", text)
    text = text.replace("\u3000", " ")
    chunks = re.split(r"(?<=[。！？])", text)
    result: list[str] = []
    for chunk in chunks:
        sentence = chunk.strip()
        if not sentence or sentence[-1] not in TERMINAL:
            continue
        if not all(allowed_char(ch) or ch == " " for ch in sentence):
            continue
        sentence = " ".join(sentence.split())
        length = norm_len(sentence)
        if 17 <= length <= 30:
            result.append(sentence)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=int, default=250)
    parser.add_argument("--max-pages", type=int, default=500)
    parser.add_argument("--out", default="eval/d05/dev/wikipedia_long_source.tsv")
    args = parser.parse_args()

    rows: list[tuple[int, int, str, str, str]] = []
    seen: set[str] = set()
    seen_pages: set[int] = set()
    pages_scanned = 0
    while len(rows) < args.target and pages_scanned < args.max_pages:
        listing = api_get({"action": "query", "list": "random", "rnnamespace": "0", "rnlimit": "50"})
        page_ids = [str(item["id"]) for item in listing.get("query", {}).get("random", [])]
        for start in range(0, len(page_ids), 20):
            if len(rows) >= args.target or pages_scanned >= args.max_pages:
                break
            batch = page_ids[start:start + 20]
            details = api_get({
                "action": "query",
                "pageids": "|".join(batch),
                "prop": "extracts|revisions",
                "explaintext": "1",
                "exintro": "1",
                "exlimit": str(min(20, len(batch))),
                "rvprop": "ids|timestamp",
            })
            for page in details.get("query", {}).get("pages", []):
                pageid = int(page.get("pageid", 0))
                if pageid <= 0 or pageid in seen_pages:
                    continue
                seen_pages.add(pageid)
                pages_scanned += 1
                revisions = page.get("revisions") or []
                if not revisions:
                    continue
                oldid = int(revisions[0].get("revid", 0))
                if oldid <= 0:
                    continue
                title = str(page.get("title", ""))
                url = "https://zh.wikipedia.org/w/index.php?oldid=" + str(oldid)
                for sentence in clean_sentences(str(page.get("extract", ""))):
                    key = "".join(ch for ch in sentence if cjk(ch))
                    if key in seen:
                        continue
                    seen.add(key)
                    rows.append((pageid, oldid, title, url, sentence))
                    if len(rows) >= args.target:
                        break
                if len(rows) >= args.target:
                    break
            time.sleep(0.8)
        time.sleep(0.8)

    rows.sort(key=lambda item: (item[0], item[1], item[4]))
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("pageid\toldid\ttitle\turl\tsentence\n")
        for row in rows:
            handle.write("\t".join(str(value).replace("\t", " ").replace("\n", " ") for value in row) + "\n")
    print("wrote " + str(len(rows)) + " sentences from " + str(len({row[0] for row in rows})) + " pages to " + args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
