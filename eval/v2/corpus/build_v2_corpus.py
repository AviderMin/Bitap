#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the v2 independent pinyin evaluation corpus.

The natural-sentence part is derived only from the Tatoeba Chinese (cmn)
sentences export (.tmp/tatoeba/cmn_sentences.tsv, CC BY 2.0 FR).  Sentences
are filtered to simplified Chinese, deduplicated, screened against the
production dictionary for whole-sentence leakage and split by near-duplicate
clusters (character n-gram Jaccard + MinHash/SimHash-style LSH) so that
dev and validation never share a near-duplicate cluster.

The special categories (homophone, shorthand, typo, mixed pinyin, number and
punctuation, proper nouns, mixed Chinese/English) are constructed from
templates and a small hand-written seed list.  Pinyin is produced by the
generic pypinyin library; the production PinyinEngine is never called.

Run from the repository root:

    python eval/v2/corpus/build_v2_corpus.py

Outputs (all deterministic, LF, UTF-8):
    eval/v2/corpus/pinyin_cases_v2_dev.tsv
    eval/v2/corpus/pinyin_cases_v2_validation.tsv
    eval/v2/corpus/manifest.json
    eval/v2/corpus/ATTRIBUTION.md
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
_pp = ROOT / ".tmp" / "pypinyin_pkg"
if _pp.is_dir():
    sys.path.insert(0, str(_pp))

from pypinyin import lazy_pinyin, Style  # noqa: E402

PUNCT = set("，。！？、；：（）《》〈〉“”‘’…—～·")
TERMINAL = set("。！？…")
FORBIDDEN = set("“”‘’《》〈〉（）【】[]{}")
CATEGORY_ORDER = [
    "natural_short",
    "natural_medium",
    "natural_long",
    "homophone",
    "shorthand",
    "typo",
    "mixed_pinyin",
    "number_punct",
    "proper_noun",
    "mixed_en",
]
NATURAL_CATEGORIES = {"natural_short", "natural_medium", "natural_long"}
INTERACTION_CATEGORIES = {"mixed_en"}

# Natural-sentence bucket definition: bucket -> (min_chars, max_chars, dev_target, validation_target)
NATURAL_BUCKETS = {
    "short": (2, 8, 400, 200),
    "medium": (9, 16, 450, 220),
    "long": (17, 24, 300, 150),
}
POOL_BUCKET_CAP = {"short": 4000, "medium": 4000, "long": 6000}
MAX_PINYIN_LENGTH = 60
MAX_CLUSTER_TAKE = 3
NEAR_DUP_JACCARD = 0.75
SPECIAL_DEV_TARGET = 40
SPECIAL_VAL_TARGET = 20


def cjk(ch: str) -> bool:
    return 0x4E00 <= ord(ch) <= 0x9FFF


def gb2312_ok(ch: str) -> bool:
    try:
        ch.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def allowed_char(ch: str) -> bool:
    return (cjk(ch) and gb2312_ok(ch)) or ch in PUNCT


def norm_text(text: str) -> str:
    return "".join(ch for ch in text if cjk(ch))


def pinyin_of(text: str) -> str:
    out = []
    for syllable in lazy_pinyin(text, style=Style.NORMAL):
        cleaned = "".join(ch for ch in syllable.lower() if "a" <= ch <= "z")
        out.append(cleaned)
    return "".join(out)


def pinyin_syllables(text: str) -> list[str]:
    result = []
    for syllable in lazy_pinyin(text, style=Style.NORMAL):
        cleaned = "".join(ch for ch in syllable.lower() if "a" <= ch <= "z")
        result.append(cleaned)
    return result


def stable_key(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with io.open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_text_lines(path: Path):
    with io.open(path, encoding="utf-8") as handle:
        for raw in handle:
            yield raw.rstrip("\n").rstrip("\r")


def load_dictionary_words(path: Path) -> set[str]:
    words: set[str] = set()
    for line in read_text_lines(path):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].strip():
            words.add(parts[1].strip())
    return words


def load_v1_sentence_norms() -> set[str]:
    norms: set[str] = set()
    for name in ("dev", "holdout", "sealed"):
        path = ROOT / "eval" / ("pinyin_cases_" + name + ".tsv")
        if not path.is_file():
            continue
        for line in read_text_lines(path):
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 4:
                for alt in parts[3].split("|"):
                    norms.add(norm_text(alt))
    return norms


def load_tatoeba(path: Path) -> list[tuple[int, str]]:
    rows: list[tuple[int, str]] = []
    for line in read_text_lines(path):
        parts = line.split("\t")
        if len(parts) < 3 or parts[1] != "cmn":
            continue
        try:
            sentence_id = int(parts[0])
        except ValueError:
            continue
        rows.append((sentence_id, parts[2]))
    return rows


def classify_bucket(length: int) -> str | None:
    for name, (lo, hi, _, _) in NATURAL_BUCKETS.items():
        if lo <= length <= hi:
            return name
    return None


def natural_sentence_norm(text: str) -> str | None:
    if not text or len(text) > 80:
        return None
    if any(not allowed_char(ch) for ch in text):
        return None
    if any(ch in FORBIDDEN for ch in text):
        return None
    if text[-1] not in TERMINAL:
        return None
    if any(ch in "。！？" for ch in text[:-1]):
        return None
    normalized = norm_text(text)
    if classify_bucket(len(normalized)) is None:
        return None
    return normalized


def char_bigrams(text: str) -> set[str]:
    if len(text) < 2:
        return {text} if text else set()
    return {text[i:i + 2] for i in range(len(text) - 1)}


def jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    union = left | right
    return len(left & right) / len(union) if union else 0.0


def minhash_signature(features: set[str], num_hashes: int = 64) -> list[int]:
    pairs = []
    for feature in features:
        raw = feature.encode("utf-8")
        h1 = int.from_bytes(hashlib.blake2b(raw, digest_size=8).digest(), "big")
        h2 = int.from_bytes(hashlib.blake2b(raw + b"\x01", digest_size=8).digest(), "big") | 1
        pairs.append((h1, h2))
    mask = (1 << 64) - 1
    signature: list[int] = []
    for i in range(num_hashes):
        best = (1 << 65)
        for h1, h2 in pairs:
            value = (h1 + i * h2) & mask
            if value < best:
                best = value
        signature.append(best)
    return signature


def build_clusters(items: list[dict], threshold: float = NEAR_DUP_JACCARD) -> list[list[int]]:
    """Union-find over MinHash-LSH candidate pairs; links only above Jaccard threshold."""
    features = [char_bigrams(item["norm"]) for item in items]
    signatures = [minhash_signature(fs) for fs in features]
    band_rows = 4
    bands: dict[tuple, list[int]] = defaultdict(list)
    for index, signature in enumerate(signatures):
        for band in range(len(signature) // band_rows):
            key = (band, tuple(signature[band * band_rows:(band + 1) * band_rows]))
            bands[key].append(index)
    parent = list(range(len(items)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    checked: set[tuple[int, int]] = set()
    linked = 0
    for indexes in bands.values():
        if len(indexes) < 2:
            continue
        for ai in range(len(indexes)):
            i = indexes[ai]
            for bi in range(ai + 1, len(indexes)):
                j = indexes[bi]
                pair = (i, j) if i < j else (j, i)
                if pair in checked:
                    continue
                checked.add(pair)
                li = len(items[i]["norm"])
                lj = len(items[j]["norm"])
                if min(li, lj) / max(li, lj) < 0.6:
                    continue
                if jaccard(features[i], features[j]) >= threshold:
                    union(i, j)
                    linked += 1
    clusters: dict[int, list[int]] = defaultdict(list)
    for index in range(len(items)):
        clusters[find(index)].append(index)
    result = list(clusters.values())
    result.sort(key=lambda cluster: min(items[i]["sid"] for i in cluster))
    return result


def select_natural(items: list[dict], clusters: list[list[int]]) -> dict[str, list[int]]:
    cluster_buckets: dict[str, list[list[int]]] = defaultdict(list)
    for cluster in clusters:
        counts = Counter(items[i]["bucket"] for i in cluster)
        cluster_buckets[counts.most_common(1)[0][0]].append(cluster)
    chosen: dict[str, list[int]] = {"dev": [], "validation": []}
    for bucket in ("short", "medium", "long"):
        _, _, dev_target, val_target = NATURAL_BUCKETS[bucket]
        ordered = sorted(
            cluster_buckets[bucket],
            key=lambda cluster: stable_key(min(items[i]["norm"] for i in cluster)),
        )
        used: set[int] = set()
        val_count = 0
        for index, cluster in enumerate(ordered):
            if val_count >= val_target:
                break
            members = sorted(cluster, key=lambda i: stable_key(items[i]["norm"]))
            take = min(len(members), MAX_CLUSTER_TAKE, val_target - val_count)
            if take <= 0:
                continue
            chosen["validation"].extend(members[:take])
            val_count += take
            used.add(index)
        dev_count = 0
        for index, cluster in enumerate(ordered):
            if index in used or dev_count >= dev_target:
                continue
            members = sorted(cluster, key=lambda i: stable_key(items[i]["norm"]))
            take = min(len(members), MAX_CLUSTER_TAKE, dev_target - dev_count)
            if take <= 0:
                continue
            chosen["dev"].extend(members[:take])
            dev_count += take
        if val_count < val_target or dev_count < dev_target:
            raise SystemExit(
                "natural bucket " + bucket + " cannot reach targets: val=" + str(val_count) +
                " dev=" + str(dev_count)
            )
    return chosen


def build_natural_items(dict_words: set[str], v1_norms: set[str], tatoeba: list[tuple[int, str]]):
    filtered: list[dict] = []
    seen: set[str] = set()
    stats = Counter()
    for sentence_id, text in tatoeba:
        normalized = natural_sentence_norm(text)
        if normalized is None:
            stats["rejected_filter"] += 1
            continue
        if normalized in seen:
            stats["rejected_duplicate"] += 1
            continue
        if normalized in dict_words or text in dict_words:
            stats["rejected_dictionary_leak"] += 1
            continue
        if normalized in v1_norms:
            stats["rejected_v1_overlap"] += 1
            continue
        pinyin = pinyin_of(normalized)
        if not pinyin or len(pinyin) > MAX_PINYIN_LENGTH:
            stats["rejected_pinyin_length"] += 1
            continue
        seen.add(normalized)
        filtered.append({
            "sid": sentence_id,
            "text": text,
            "norm": normalized,
            "pinyin": pinyin,
            "bucket": classify_bucket(len(normalized)),
        })
    pool: list[dict] = []
    for bucket, cap in POOL_BUCKET_CAP.items():
        bucket_items = [item for item in filtered if item["bucket"] == bucket]
        bucket_items.sort(key=lambda item: stable_key(item["norm"]))
        pool.extend(bucket_items[:cap])
    pool.sort(key=lambda item: item["sid"])
    stats["filtered_total"] = len(filtered)
    stats["pool_total"] = len(pool)
    clusters = build_clusters(pool)
    stats["clusters"] = len(clusters)
    stats["multi_member_clusters"] = sum(1 for cluster in clusters if len(cluster) > 1)
    chosen = select_natural(pool, clusters)
    rows: dict[str, list[dict]] = {"dev": [], "validation": []}
    for split, indexes in chosen.items():
        for index in indexes:
            item = pool[index]
            rows[split].append({
                "category": "natural_" + item["bucket"],
                "input": item["pinyin"],
                "expected": item["text"],
                "note": "自然句：" + item["bucket"] + "（Tatoeba cmn）",
                "source": "tatoeba:" + str(item["sid"]),
            })
    return rows, stats


# ---------------------------------------------------------------------------
# Special categories
# ---------------------------------------------------------------------------

QWERTY_NEIGHBORS = {
    "q": "wa", "w": "qes", "e": "wrd", "r": "etf", "t": "ryg", "y": "tuh", "u": "yij",
    "i": "uok", "o": "ipl", "p": "ol", "a": "qsz", "s": "awdx", "d": "sefc", "f": "drgv",
    "g": "fthb", "h": "gyjn", "j": "hukm", "k": "jil", "l": "kop", "z": "asx", "x": "zsdc",
    "c": "xdfv", "v": "cfgb", "b": "vghn", "n": "bhjm", "m": "njk",
}


def load_jieba_entries(path: Path, dict_words: set[str]) -> list[dict]:
    entries: list[dict] = []
    seen: set[str] = set()
    for line in read_text_lines(path):
        parts = line.split(" ")
        if len(parts) < 2:
            continue
        word = parts[0]
        tag = parts[2] if len(parts) >= 3 else ""
        if word in seen or not (2 <= len(word) <= 4):
            continue
        if not all(cjk(ch) for ch in word):
            continue
        if tag in {"nr", "ns", "nz", "nt", "nrt", "x"}:
            continue
        try:
            frequency = int(parts[1])
        except ValueError:
            continue
        if frequency < 500 or word not in dict_words:
            continue
        syllables = pinyin_syllables(word)
        if len(syllables) != len(word) or not all(s and s.isalpha() for s in syllables):
            continue
        seen.add(word)
        entries.append({
            "word": word,
            "freq": frequency,
            "pinyin": "".join(syllables),
            "syllables": syllables,
        })
    entries.sort(key=lambda entry: (-entry["freq"], entry["word"]))
    return entries


def make_item(category: str, input_text: str, expected: list[str], note: str, source: str) -> dict:
    return {
        "category": category,
        "input": input_text,
        "expected": "|".join(expected),
        "note": note,
        "source": source,
    }


def build_homophone_items(entries: list[dict]) -> list[dict]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for entry in entries:
        groups[entry["pinyin"]].append(entry)
    items: list[dict] = []
    for pinyin, group in groups.items():
        group.sort(key=lambda entry: (-entry["freq"], entry["word"]))
        words = [entry["word"] for entry in group[:3]]
        if len(words) < 2:
            continue
        items.append(make_item(
            "homophone",
            pinyin,
            words,
            "同音词歧义：均可接受",
            "template:homophone:" + pinyin,
        ))
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


def build_shorthand_items(entries: list[dict]) -> list[dict]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for entry in entries:
        initials = "".join(syllable[0] for syllable in entry["syllables"])
        if not (2 <= len(initials) <= 4):
            continue
        groups[initials].append(entry)
    items: list[dict] = []
    for initials, group in groups.items():
        group.sort(key=lambda entry: (-entry["freq"], entry["word"]))
        if len(group) >= 2:
            words = [entry["word"] for entry in group[:3]]
            items.append(make_item(
                "shorthand",
                initials,
                words,
                "简拼：全声母，存在歧义",
                "template:shorthand:" + initials,
            ))
        else:
            items.append(make_item(
                "shorthand",
                initials,
                [group[0]["word"]],
                "简拼：全声母",
                "template:shorthand:" + initials,
            ))
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


def build_mixed_pinyin_items(entries: list[dict]) -> list[dict]:
    items: list[dict] = []
    for entry in entries:
        syllables = entry["syllables"]
        if len(syllables) < 2:
            continue
        full = "".join(syllables)
        mixed = "".join(syllables[:-1]) + syllables[-1][0]
        if mixed == full or len(mixed) < 2:
            continue
        items.append(make_item(
            "mixed_pinyin",
            mixed,
            [entry["word"]],
            "混拼：完整音节+" + syllables[-1][0] + "（末音节声母）",
            "template:mixed_pinyin:" + entry["word"],
        ))
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


def build_typo_items(entries: list[dict]) -> list[dict]:
    items: list[dict] = []
    seen_inputs: set[str] = set()
    for entry in entries:
        pinyin = entry["pinyin"]
        if len(pinyin) < 3:
            continue
        middle = max(1, min(len(pinyin) - 2, len(pinyin) // 2))
        variants: list[tuple[str, str]] = []
        missing = pinyin[:middle] + pinyin[middle + 1:]
        variants.append((missing, "missing"))
        extra = pinyin[:middle] + pinyin[middle] + pinyin[middle:]
        variants.append((extra, "extra"))
        if middle + 1 < len(pinyin):
            swapped = pinyin[:middle] + pinyin[middle + 1] + pinyin[middle] + pinyin[middle + 2:]
            variants.append((swapped, "swap"))
        neighbors = QWERTY_NEIGHBORS.get(pinyin[middle])
        if neighbors:
            variants.append((pinyin[:middle] + neighbors[0] + pinyin[middle + 1:], "adjacent"))
        order = int(stable_key(entry["word"])[:8], 16) % len(variants)
        variants = variants[order:] + variants[:order]
        for variant, subtype in variants:
            if variant == pinyin or len(variant) < 2 or not variant.isalpha():
                continue
            if variant in seen_inputs:
                continue
            note = {
                "missing": "漏字母：正确拼音 " + pinyin,
                "extra": "多字母：正确拼音 " + pinyin,
                "swap": "相邻交换：正确拼音 " + pinyin,
                "adjacent": "邻键：正确拼音 " + pinyin,
            }[subtype]
            items.append(make_item(
                "typo",
                variant,
                [entry["word"]],
                note,
                "template:typo:" + entry["word"],
            ))
            seen_inputs.add(variant)
            break
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


NUMBER_PUNCT_SEEDS: list[tuple[str, str | None, str]] = [
    ("三十分钟后", None, "数字：中文数词"),
    ("二百五十块钱", None, "数字：金额"),
    ("百分之九十", None, "数字：百分比"),
    ("十月一号", None, "数字：日期"),
    ("第一次", None, "数字：序数"),
    ("四点零五分", None, "数字：时间"),
    ("五十分钟以后", None, "数字：中文数词"),
    ("三百五十块", None, "数字：金额"),
    ("百分之三", None, "数字：百分比"),
    ("十二月二十五号", None, "数字：日期"),
    ("第二次", None, "数字：序数"),
    ("九点零八分", None, "数字：时间"),
    ("这个手机花了两千多块", None, "数字：金额"),
    ("我在车站等了二十分钟", None, "数字：时长"),
    ("这件衣服花了几百块", None, "数字：金额"),
    ("他在家等了一个小时", None, "数字：时长"),
    ("一百五十米", None, "数字：长度"),
    ("两百块钱", None, "数字：金额"),
    ("三分之一", None, "数字：分数"),
    ("四舍五入", None, "数字：成语"),
    ("零点五", None, "数字：小数"),
    ("三天以后", None, "数字：时长"),
    ("六楼会议室", None, "数字：楼层"),
    ("七点半出发", None, "数字：时间"),
    ("八折优惠", None, "数字：折扣"),
    ("九十九块", None, "数字：金额"),
    ("十一点睡觉", None, "数字：时间"),
    ("十二点整", None, "数字：时间"),
    ("二零二六年", None, "数字：年份"),
    ("第一个人", None, "数字：序数"),
    ("两个星期", None, "数字：时长"),
    ("五个小时", None, "数字：时长"),
    ("七千八百元", None, "数字：金额"),
    ("百分之九十九", None, "数字：百分比"),
    ("四点二十分", None, "数字：时间"),
    ("三十一号", None, "数字：日期"),
    ("星期一", None, "数字：星期"),
    ("星期三下午", None, "数字：星期"),
    ("两千零八年", None, "数字：年份"),
    ("十几个人", None, "数字：概数"),
    ("二十多分钟", None, "数字：概数"),
    ("百分之二十左右", None, "数字：百分比"),
    ("五分钟就到", None, "数字：时长"),
    ("六点起床", None, "数字：时间"),
    ("七月放假", None, "数字：月份"),
    ("八月十五号", None, "数字：日期"),
    ("九点半开会", None, "数字：时间"),
    ("十块钱", None, "数字：金额"),
    ("一百零五个", None, "数字：数量"),
    ("三千六百秒", None, "数字：时间"),
    ("五百分之三", None, "数字：分数"),
    ("西安", "xi'an", "标点：撇号显式音节分隔"),
    ("天安门", "tian'anmen", "标点：撇号显式音节分隔"),
    ("长安", "chang'an", "标点：撇号显式音节分隔"),
    ("北京", "bei jing", "标点：空格分隔音节"),
    ("上海", "shang hai", "标点：空格分隔音节"),
    ("你好", "ni hao", "标点：空格分隔音节"),
    ("你好", "nihao,", "标点：输入末尾标点应被忽略"),
    ("再见", "zaijian!", "标点：输入末尾标点应被忽略"),
    ("明天", "mingtian?", "标点：输入末尾标点应被忽略"),
    ("晚上", "wanshang.", "标点：输入末尾标点应被忽略"),
    ("我要买", "woyaomai3", "阿拉伯数字：3 原文提交，评测首个中文段"),
    ("明天", "mingtian10", "阿拉伯数字：10 原文提交，评测首个中文段"),
    ("这个", "zhege5", "阿拉伯数字：5 原文提交，评测首个中文段"),
    ("我有", "woyou2", "阿拉伯数字：2 原文提交，评测首个中文段"),
    ("他", "ta3", "阿拉伯数字：3 原文提交，评测首个中文段"),
    ("买了", "maile4", "阿拉伯数字：4 原文提交，评测首个中文段"),
    ("等了", "dengle20", "阿拉伯数字：20 原文提交，评测首个中文段"),
    ("今天", "jintian2026", "阿拉伯数字：2026 原文提交，评测首个中文段"),
    ("电话", "dianhua138", "阿拉伯数字：138 原文提交，评测首个中文段"),
    ("下午", "xiawu4", "阿拉伯数字：4 原文提交，评测首个中文段"),
    ("三十分钟", "sanshifenzhong30", "阿拉伯数字：30 原文提交，评测首个中文段"),
    ("苹果", "pingguo6", "阿拉伯数字：6 原文提交，评测首个中文段"),
]


def build_number_punct_items() -> list[dict]:
    items: list[dict] = []
    for expected_text, explicit_input, note in NUMBER_PUNCT_SEEDS:
        input_text = explicit_input if explicit_input is not None else pinyin_of(expected_text)
        if not input_text:
            continue
        if not all(ch.isalpha() or ch.isdigit() or ch in " ,!?.'" for ch in input_text):
            continue
        items.append(make_item(
            "number_punct",
            input_text,
            [expected_text],
            note,
            "template:number_punct:" + stable_key(expected_text)[:10],
        ))
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


PROPER_NOUNS = [
    "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "西安", "南京", "武汉",
    "长沙", "郑州", "青岛", "大连", "苏州", "厦门", "福州", "昆明", "贵阳", "南宁",
    "海口", "三亚", "拉萨", "乌鲁木齐", "呼和浩特", "哈尔滨", "长春", "沈阳", "济南", "太原",
    "石家庄", "兰州", "银川", "西宁", "南昌", "合肥", "宁波", "温州", "佛山", "东莞",
    "珠海", "香港", "澳门", "台北", "新加坡", "吉隆坡", "东京", "首尔", "纽约", "伦敦",
    "巴黎", "柏林", "悉尼", "多伦多", "洛杉矶", "旧金山", "西雅图", "张伟", "王芳", "李娜",
    "刘洋", "陈静", "杨明", "赵敏", "周杰伦", "马云", "马化腾", "雷军", "任正非", "王健林",
    "刘强东", "李彦宏", "丁磊", "腾讯", "阿里巴巴", "字节跳动", "百度", "京东", "美团", "小米",
    "华为", "网易", "新浪", "搜狐", "滴滴", "携程", "拼多多",
]


def build_proper_noun_items() -> list[dict]:
    items: list[dict] = []
    for name in PROPER_NOUNS:
        pinyin = pinyin_of(name)
        if not pinyin or not pinyin.isalpha():
            continue
        items.append(make_item("proper_noun", pinyin, [name], "专有名词：" + name, "manual:proper_noun:" + name))
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


MIXED_EN_SEEDS: list[tuple[str, str]] = [
    ("我要买iPhone", "iPhone"),
    ("明天发你PDF", "PDF"),
    ("我需要下载一个APP", "APP"),
    ("这个版本已经release", "release"),
    ("我们用Python", "Python"),
    ("这个App很好", "App"),
    ("你在Slack上回复一下", "Slack"),
    ("记得check邮件", "check"),
    ("会议改到Teams进行", "Teams"),
    ("这个功能在iOS上有点问题", "iOS"),
    ("我明天要开会review", "review"),
    ("我们先看看feedback", "feedback"),
    ("我要买个iPad", "iPad"),
    ("明天发你Excel", "Excel"),
    ("我们需要做一个Demo", "Demo"),
    ("这个文档已经update", "update"),
    ("我先看看email", "email"),
    ("在公司Slack上找我", "Slack"),
    ("今天check了吗", "check"),
    ("报告在meeting前发给我", "meeting"),
    ("我的account被封了", "account"),
    ("请把简历导到Trello", "Trello"),
    ("明天整理Report", "Report"),
    ("这个API需要重新测试", "API"),
    ("我要订一个Zoom", "Zoom"),
    ("明天开始用GitHub", "GitHub"),
    ("这个PR现在就可以", "PR"),
    ("我们在Jira上看", "Jira"),
    ("请把检测发到WeChat", "WeChat"),
    ("我是在Mac上", "Mac"),
    ("这个bug已经fix了", "fix"),
    ("我们用Docker部署", "Docker"),
    ("这个手机是Android", "Android"),
    ("我在等Email", "Email"),
    ("周五开OKR会议", "OKR"),
    ("这个页面用Figma设计", "Figma"),
    ("把文件传到Drive", "Drive"),
    ("明天上线v2版本", "v2"),
    ("请更新Notion文档", "Notion"),
    ("用Photoshop打开", "Photoshop"),
    ("我要发个WeChat给你", "WeChat"),
    ("这个需求用Axure画", "Axure"),
    ("下周做SEO优化", "SEO"),
    ("这个接口是REST风格", "REST"),
    ("把日志发到Kibana", "Kibana"),
    ("用Gradle构建", "Gradle"),
    ("这个脚本用Bash写", "Bash"),
    ("服务器是Linux系统", "Linux"),
    ("这个库用Kotlin实现", "Kotlin"),
]


MIXED_EN_EXTRA: list[tuple[str, str]] = [
    ("明天发你Word", "Word"),
    ("这个文件是PDF", "PDF"),
    ("我要用Excel", "Excel"),
    ("请打开PowerPoint", "PowerPoint"),
    ("这个图标用SVG", "SVG"),
    ("明天测试API", "API"),
    ("我们用Redis缓存", "Redis"),
    ("这个服务用Go写", "Go"),
    ("数据库是MySQL", "MySQL"),
    ("前端用React", "React"),
    ("后端用Spring", "Spring"),
    ("这个组件用Vue", "Vue"),
    ("部署到AWS", "AWS"),
    ("容器用Kubernetes", "Kubernetes"),
    ("监控用Prometheus", "Prometheus"),
    ("日志用ELK", "ELK"),
    ("测试用JUnit", "JUnit"),
    ("构建用Maven", "Maven"),
    ("代码在GitLab", "GitLab"),
    ("文档在Confluence", "Confluence"),
    ("任务在Trello", "Trello"),
    ("沟通用Teams", "Teams"),
    ("文件发我邮箱email", "email"),
    ("记得回复邮件Email", "Email"),
    ("这个版本是beta", "beta"),
    ("已经发布release", "release"),
    ("请更新README", "README"),
    ("这个脚本用Python", "Python"),
    ("接口返回JSON", "JSON"),
    ("配置用YAML", "YAML"),
    ("这个页面用HTML", "HTML"),
    ("样式用CSS", "CSS"),
    ("脚本用JavaScript", "JavaScript"),
    ("框架用Flutter", "Flutter"),
    ("客户端用Swift", "Swift"),
    ("安卓用Kotlin", "Kotlin"),
    ("服务器用Ubuntu", "Ubuntu"),
    ("电脑是Windows", "Windows"),
    ("手机是iPhone", "iPhone"),
    ("平板是iPad", "iPad"),
    ("耳机是AirPods", "AirPods"),
    ("手表是AppleWatch", "AppleWatch"),
    ("用Photoshop修图", "Photoshop"),
    ("用Figma画图", "Figma"),
    ("用Chrome打开", "Chrome"),
    ("用Safari浏览", "Safari"),
    ("搜索用Google", "Google"),
    ("地图用GoogleMap", "GoogleMap"),
    ("视频用YouTube", "YouTube"),
    ("音乐用Spotify", "Spotify"),
]


def build_mixed_en_items() -> list[dict]:
    items: list[dict] = []
    for expected_text, tail in MIXED_EN_SEEDS + MIXED_EN_EXTRA:
        if not expected_text.endswith(tail):
            continue
        prefix = expected_text[: len(expected_text) - len(tail)]
        if not prefix or not all(cjk(ch) for ch in prefix):
            continue
        pinyin = pinyin_of(prefix)
        if not pinyin or not pinyin.isalpha():
            continue
        input_text = pinyin + tail.lower()
        items.append(make_item(
            "mixed_en",
            input_text,
            [expected_text],
            "中英混输：中文前缀 + 英文原文提交",
            "template:mixed_en:" + stable_key(expected_text)[:10],
        ))
    items.sort(key=lambda item: stable_key(item["input"]))
    return items


def near_duplicate(item: dict, others: list[dict], threshold: float = 0.85) -> bool:
    first_expected = norm_text(item["expected"].split("|")[0])
    features = char_bigrams(first_expected)
    for other in others:
        other_expected = norm_text(other["expected"].split("|")[0])
        if jaccard(features, char_bigrams(other_expected)) >= threshold:
            return True
    return False


def select_special_items(
    candidates: list[dict],
    used_inputs: set[str],
    selected_other: list[dict],
    category: str,
) -> dict[str, list[dict]]:
    ordered = sorted(candidates, key=lambda item: stable_key(item["input"] + "\x1f" + item["expected"]))
    chosen: dict[str, list[dict]] = {"dev": [], "validation": []}
    local_selected: list[dict] = [row for row in selected_other if row["category"] == category]
    index = 0
    for split, target in (("validation", SPECIAL_VAL_TARGET), ("dev", SPECIAL_DEV_TARGET)):
        while len(chosen[split]) < target and index < len(ordered):
            item = ordered[index]
            index += 1
            if item["input"] in used_inputs:
                continue
            if near_duplicate(item, local_selected):
                continue
            used_inputs.add(item["input"])
            chosen[split].append(item)
            local_selected.append(item)
        if len(chosen[split]) < target:
            raise SystemExit(
                "special category " + category + " cannot reach " + split + " target " + str(target)
            )
    return chosen


def build_special_rows(dict_words: set[str], used_inputs: set[str]) -> dict[str, list[dict]]:
    entries = load_jieba_entries(ROOT / ".tmp" / "jieba.txt", dict_words)
    candidates = {
        "homophone": build_homophone_items(entries),
        "shorthand": build_shorthand_items(entries),
        "typo": build_typo_items(entries),
        "mixed_pinyin": build_mixed_pinyin_items(entries),
        "number_punct": build_number_punct_items(),
        "proper_noun": build_proper_noun_items(),
        "mixed_en": build_mixed_en_items(),
    }
    rows: dict[str, list[dict]] = {"dev": [], "validation": []}
    selected_all: list[dict] = []
    for category in ["homophone", "shorthand", "typo", "mixed_pinyin", "number_punct", "proper_noun", "mixed_en"]:
        chosen = select_special_items(candidates[category], used_inputs, selected_all, category)
        for split in ("dev", "validation"):
            rows[split].extend(chosen[split])
            selected_all.extend(chosen[split])
    return rows


def render_rows(rows_by_split: dict[str, list[dict]]) -> dict[str, tuple[str, int]]:
    output: dict[str, tuple[str, int]] = {}
    prefixes = {"dev": "v2d", "validation": "v2v"}
    for split in ("dev", "validation"):
        rows = rows_by_split[split]
        rows = sorted(
            rows,
            key=lambda row: (CATEGORY_ORDER.index(row["category"]), stable_key(row["input"])),
        )
        counters: Counter = Counter()
        lines: list[str] = []
        seen_ids: set[str] = set()
        for row in rows:
            category = row["category"]
            counters[category] += 1
            case_id = prefixes[split] + "-" + category + "-" + str(counters[category]).zfill(3)
            if case_id in seen_ids:
                raise SystemExit("duplicate case id " + case_id)
            seen_ids.add(case_id)
            fields = [case_id, category, row["input"], row["expected"], row["note"], row["source"]]
            for field in fields:
                if "\t" in field or "\n" in field:
                    raise SystemExit("field contains tab/newline")
            lines.append("\t".join(fields))
        output[split] = ("\n".join(lines) + "\n", len(rows))
    return output


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def attribution_text(source_sha: str) -> str:
    return (
        "# Evaluation corpus v2 - data attribution and license\n"
        "\n"
        "This directory contains an evaluation-only pinyin corpus. It must never be\n"
        "copied into the production dictionary or used as a training lexicon.\n"
        "\n"
        "## Tatoeba (natural sentences)\n"
        "\n"
        "Natural sentences are selected from the Tatoeba Chinese (cmn) sentences\n"
        "export (https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences.tsv.bz2),\n"
        "licensed under Creative Commons Attribution 2.0 France (CC BY 2.0 FR,\n"
        "https://creativecommons.org/licenses/by/2.0/fr/).\n"
        "\n"
        "Source file: .tmp/tatoeba/cmn_sentences.tsv\n"
        "SHA-256: " + source_sha + "\n"
        "\n"
        "Contains Chinese sentence data from Tatoeba (https://tatoeba.org), licensed\n"
        "under CC BY 2.0 FR. Authors: see docs/TATOEBA_CONTRIBUTORS.txt (976\n"
        "contributors, generated mechanically from the 2026-11-07 detailed export).\n"
        "Changes: a subset of sentences was filtered, punctuation-normalized for\n"
        "near-duplicate comparison, and redistributed verbatim as evaluation\n"
        "expectations. No original sentence was added to the production dictionary.\n"
        "\n"
        "## jieba and pypinyin (special-category construction)\n"
        "\n"
        "Special-category word seeds were taken from the jieba dictionary\n"
        "(https://github.com/fxsjy/jieba, MIT License); pinyin strings were produced\n"
        "with pypinyin (https://github.com/mozillazg/python-pinyin, MIT License).\n"
        "Neither library's output is used to select natural evaluation sentences.\n"
        "\n"
        "## Generated files\n"
        "\n"
        "- pinyin_cases_v2_dev.tsv - development split.\n"
        "- pinyin_cases_v2_validation.tsv - independent validation split; use only\n"
        "  for acceptance, never for tuning.\n"
        "- manifest.json - SHA-256 hashes, source hashes, category counts and the\n"
        "  exact generation command.\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the v2 independent pinyin evaluation corpus.")
    parser.add_argument("--tatoeba", default=".tmp/tatoeba/cmn_sentences.tsv")
    parser.add_argument("--jieba", default=".tmp/jieba.txt")
    parser.add_argument("--dictionary", default="app/src/main/assets/pinyin_dict.tsv")
    parser.add_argument("--out-dir", default="eval/v2/corpus")
    args = parser.parse_args()

    tatoeba_path = (ROOT / args.tatoeba).resolve()
    jieba_path = (ROOT / args.jieba).resolve()
    dictionary_path = (ROOT / args.dictionary).resolve()
    out_dir = (ROOT / args.out_dir).resolve()
    for path in (tatoeba_path, jieba_path, dictionary_path):
        if not path.is_file():
            raise SystemExit("missing input file: " + str(path))

    dict_words = load_dictionary_words(dictionary_path)
    v1_norms = load_v1_sentence_norms()
    tatoeba = load_tatoeba(tatoeba_path)
    natural_rows, natural_stats = build_natural_items(dict_words, v1_norms, tatoeba)
    used_inputs = {row["input"] for rows in natural_rows.values() for row in rows}
    special_rows = build_special_rows(dict_words, used_inputs)
    rows = {
        "dev": natural_rows["dev"] + special_rows["dev"],
        "validation": natural_rows["validation"] + special_rows["validation"],
    }
    rendered = render_rows(rows)
    dev_text, dev_count = rendered["dev"]
    val_text, val_count = rendered["validation"]
    if dev_count < 1000:
        raise SystemExit("dev corpus below 1000 rows: " + str(dev_count))
    if val_count < 500:
        raise SystemExit("validation corpus below 500 rows: " + str(val_count))

    dev_path = out_dir / "pinyin_cases_v2_dev.tsv"
    val_path = out_dir / "pinyin_cases_v2_validation.tsv"
    write_text(dev_path, dev_text)
    write_text(val_path, val_text)
    dev_sha = sha256_bytes(dev_text.encode("utf-8"))
    val_sha = sha256_bytes(val_text.encode("utf-8"))

    def counts_for(split_rows: list[dict]) -> dict[str, int]:
        counter = Counter(row["category"] for row in split_rows)
        return {category: counter.get(category, 0) for category in CATEGORY_ORDER}

    manifest = {
        "schema_version": 2,
        "generator": "eval/v2/corpus/build_v2_corpus.py",
        "generator_version": "1.0.0",
        "command": "python eval/v2/corpus/build_v2_corpus.py",
        "source": {
            "name": "Tatoeba Chinese (cmn) sentences export",
            "file": ".tmp/tatoeba/cmn_sentences.tsv",
            "sha256": sha256_file(tatoeba_path),
            "license": "CC BY 2.0 FR",
            "url": "https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences.tsv.bz2",
            "rows": len(tatoeba),
            "attribution": "ATTRIBUTION.md",
        },
        "auxiliary_sources": {
            "jieba": {
                "file": ".tmp/jieba.txt",
                "sha256": sha256_file(jieba_path),
                "license": "MIT",
                "use": "special-category word seeds only",
            },
            "pypinyin": {
                "license": "MIT",
                "version": getattr(__import__("pypinyin"), "__version__", "unknown"),
                "use": "pinyin generation for all corpus rows",
            },
        },
        "files": {
            "dev": {
                "path": "eval/v2/corpus/pinyin_cases_v2_dev.tsv",
                "sha256": dev_sha,
                "rows": dev_count,
            },
            "validation": {
                "path": "eval/v2/corpus/pinyin_cases_v2_validation.tsv",
                "sha256": val_sha,
                "rows": val_count,
            },
        },
        "category_counts": {
            "dev": counts_for(rows["dev"]),
            "validation": counts_for(rows["validation"]),
        },
        "split": {
            "method": "near-duplicate clusters assigned whole to one split; both splits share the same Tatoeba source",
            "near_duplicate": {
                "features": "character 2-gram Jaccard",
                "jaccard_threshold": NEAR_DUP_JACCARD,
                "lsh": "MinHash 64 permutations, 16 bands x 4 rows",
                "max_members_per_cluster": MAX_CLUSTER_TAKE,
            },
            "natural_pool": {
                "filtered": natural_stats["filtered_total"],
                "pool": natural_stats["pool_total"],
                "clusters": natural_stats["clusters"],
                "multi_member_clusters": natural_stats["multi_member_clusters"],
            },
            "natural_bucket_targets": {
                bucket: {"dev": values[2], "validation": values[3]}
                for bucket, values in NATURAL_BUCKETS.items()
            },
            "special_category_targets": {
                "dev": SPECIAL_DEV_TARGET,
                "validation": SPECIAL_VAL_TARGET,
            },
        },
        "filters": {
            "language": "cmn",
            "script": "simplified Chinese (CJK Unified Ideographs encodable with GB2312) plus Chinese punctuation",
            "natural_length_chars": {"short": [2, 8], "medium": [9, 16], "long": [17, 24]},
            "max_pinyin_length": MAX_PINYIN_LENGTH,
            "excluded": {
                "dictionary_whole_sentence": "normalized sentence equals a whole pinyin_dict.tsv word",
                "v1_corpus_overlap": "normalized sentence equals an expected sentence in eval/pinyin_cases_{dev,holdout,sealed}.tsv",
            },
            "rejected_counts": dict(natural_stats),
        },
        "engine_independence": "no PinyinEngine call is made during corpus generation; pinyin comes from pypinyin only",
    }
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    write_text(out_dir / "manifest.json", manifest_text)
    write_text(out_dir / "ATTRIBUTION.md", attribution_text(sha256_file(tatoeba_path)))

    print("dev rows: " + str(dev_count) + " sha256=" + dev_sha)
    print("validation rows: " + str(val_count) + " sha256=" + val_sha)
    print("category counts dev: " + json.dumps(manifest["category_counts"]["dev"], ensure_ascii=False))
    print("category counts validation: " + json.dumps(manifest["category_counts"]["validation"], ensure_ascii=False))
    print("natural pool: " + json.dumps(manifest["split"]["natural_pool"], ensure_ascii=False))
    print("wrote: " + str(dev_path))
    print("wrote: " + str(val_path))
    print("wrote: " + str(out_dir / "manifest.json"))
    print("wrote: " + str(out_dir / "ATTRIBUTION.md"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
