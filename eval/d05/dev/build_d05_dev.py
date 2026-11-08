#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the D05 cross-source diagnostic dev set.

This is an explicitly per-case-diagnosable DEVELOPMENT set, not an acceptance
set. It uses CC0 zh-TW files that were not used (or only partially used) by the
v3 acceptance corpus: sayit.txt, lms.txt, cvsprint_201902.txt, exam_text.txt,
gov_press_release.txt, cofacts.txt, web_slang.txt and the remaining lines of
chatlogs.txt. All sentences are converted to simplified Chinese with zhconv
and screened against v3/v1/v2 expected sentences (exact + near-duplicate) and
against whole-sentence dictionary entries.

Special categories (typo / shorthand / mixed_pinyin / mixed_en) are built from
generic jieba dictionary words; they are never derived by rewriting v3/v2
expected sentences. The production PinyinEngine is never called.

Run from the repository root:

    python eval/d05/dev/build_d05_dev.py

Outputs (deterministic, LF, UTF-8):
    eval/d05/dev/dev.tsv
    eval/d05/dev/manifest.json
    eval/d05/dev/ATTRIBUTION.md
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
_pp = ROOT / ".tmp" / "pypinyin_pkg"
if _pp.is_dir():
    sys.path.insert(0, str(_pp))

import zhconv  # noqa: E402
from pypinyin import lazy_pinyin, Style  # noqa: E402

PUNCT = set("，。！？、；：（）《》〈〉“”‘’…—～·")
FORBIDDEN = set("“”‘’《》〈〉（）【】[]{}")
PRIMARY_FILES = ["setences.txt", "g0v_slack_rand0m.txt", "tg_common_voice.txt"]
SUPPLEMENT_FILES = [
    "sayit.txt",
    "lms.txt",
    "cvsprint_201902.txt",
    "exam_text.txt",
    "gov_press_release.txt",
    "cofacts.txt",
    "web_slang.txt",
    "chatlogs.txt",
]
SOURCE_FILES = PRIMARY_FILES + SUPPLEMENT_FILES
NATURAL_TARGETS = {"short": 300, "medium": 250, "long": 150}
NATURAL_BUCKETS = {"short": (2, 8), "medium": (9, 16), "long": (17, 30)}
SPECIAL_TARGETS = {"typo": 100, "shorthand": 100, "mixed_pinyin": 100, "mixed_en": 50}
CATEGORY_ORDER = ["natural_short", "natural_medium", "natural_long", "typo", "shorthand", "mixed_pinyin", "mixed_en"]
MAX_PINYIN_LENGTH = 70
MAX_CLUSTER_TAKE = 2
NEAR_DUP_JACCARD = 0.75
REFERENCE_NEAR_DUP_JACCARD = 0.75


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
        out.append("".join(ch for ch in syllable.lower() if "a" <= ch <= "z"))
    return "".join(out)


def pinyin_syllables(text: str) -> list[str]:
    return ["".join(ch for ch in s.lower() if "a" <= ch <= "z")
            for s in lazy_pinyin(text, style=Style.NORMAL)]


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
    with io.open(path, encoding="utf-8-sig", errors="replace") as handle:
        for raw in handle:
            yield raw.rstrip("\n").rstrip("\r")


def classify_bucket(length: int) -> str | None:
    for bucket, (lo, hi) in NATURAL_BUCKETS.items():
        if lo <= length <= hi:
            return bucket
    return None


def convert_to_simplified(text: str) -> str:
    return zhconv.convert(text, "zh-cn")


def clean_natural(text: str) -> str | None:
    if not text:
        return None
    if any(not allowed_char(ch) for ch in text):
        return None
    if any(ch in FORBIDDEN for ch in text):
        return None
    if any(ch in "。！？" for ch in text[:-1]):
        return None
    normalized = norm_text(text)
    if classify_bucket(len(normalized)) is None:
        return None
    return normalized


SPLIT_PUNCT = re.compile(r"[，。！？；：]")


def source_segments(text: str):
    """Yield (candidate_text, was_split) for one converted source line.

    Whole lines are preferred. Lines that are not valid as a whole (too long
    or with internal sentence punctuation) are split at Chinese punctuation
    and 17-30-character clauses are kept; the source line number is unchanged.
    """
    if clean_natural(text) is not None:
        yield text, False
        return
    if len(norm_text(text)) <= 30 and not any(ch in "。！？" for ch in text[:-1]):
        return
    for part in SPLIT_PUNCT.split(text):
        part = part.strip()
        if not part or not allowed_char_all(part):
            continue
        normalized = norm_text(part)
        if 17 <= len(normalized) <= 30 and clean_natural(part) is not None:
            yield part, True


def allowed_char_all(text: str) -> bool:
    return all(allowed_char(ch) for ch in text)


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
    signature = []
    for i in range(num_hashes):
        best = (1 << 65)
        for h1, h2 in pairs:
            value = (h1 + i * h2) & mask
            if value < best:
                best = value
        signature.append(best)
    return signature


def build_clusters(items: list[dict]) -> list[list[int]]:
    features = [char_bigrams(item["norm"]) for item in items]
    signatures = [minhash_signature(fs) for fs in features]
    band_rows = 4
    bands: dict[tuple, list[int]] = defaultdict(list)
    for index, signature in enumerate(signatures):
        for band in range(len(signature) // band_rows):
            bands[(band, tuple(signature[band * band_rows:(band + 1) * band_rows]))].append(index)
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
                li, lj = len(items[i]["norm"]), len(items[j]["norm"])
                if min(li, lj) / max(li, lj) < 0.6:
                    continue
                if jaccard(features[i], features[j]) >= NEAR_DUP_JACCARD:
                    union(i, j)
    clusters: dict[int, list[int]] = defaultdict(list)
    for index in range(len(items)):
        clusters[find(index)].append(index)
    result = list(clusters.values())
    result.sort(key=lambda cluster: min(items[i]["line"] for i in cluster))
    return result


def load_dictionary_words(path: Path) -> set[str]:
    words: set[str] = set()
    for line in read_text_lines(path):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].strip():
            words.add(parts[1].strip())
    return words


def load_reference_data() -> tuple[list[str], dict[str, str]]:
    texts: list[str] = []
    files: dict[str, str] = {}
    paths = [
        ROOT / "eval" / "pinyin_cases_dev.tsv",
        ROOT / "eval" / "pinyin_cases_holdout.tsv",
        ROOT / "eval" / "pinyin_cases_sealed.tsv",
        ROOT / "eval" / "v2" / "corpus" / "pinyin_cases_v2_dev.tsv",
        ROOT / "eval" / "v2" / "corpus" / "pinyin_cases_v2_validation.tsv",
        ROOT / "eval" / "v3" / "corpus" / "pinyin_cases_v3_validation.tsv",
    ]
    for path in paths:
        if not path.is_file():
            continue
        files[str(path.relative_to(ROOT)).replace("\\", "/")] = sha256_file(path)
        for line in read_text_lines(path):
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 4:
                for alternative in parts[3].split("|"):
                    normalized = norm_text(alternative)
                    if normalized:
                        texts.append(normalized)
    return texts, files


def load_natural_items(source_dir: Path):
    items: list[dict] = []
    seen: set[str] = set()
    stats = Counter()
    file_info: list[dict] = []
    for filename in SOURCE_FILES:
        path = source_dir / filename
        if not path.is_file():
            raise SystemExit("missing CC0 source file: " + str(path))
        file_info.append({"name": filename, "sha256": sha256_file(path), "bytes": path.stat().st_size})
        for line_number, raw in enumerate(read_text_lines(path), start=1):
            stats["source_lines"] += 1
            converted = convert_to_simplified(raw.strip())
            segments = list(source_segments(converted))
            if not segments:
                stats["rejected_filter"] += 1
                continue
            for segment, was_split in segments:
                normalized = clean_natural(segment)
                if normalized is None:
                    stats["rejected_filter"] += 1
                    continue
                if normalized in seen:
                    stats["rejected_duplicate"] += 1
                    continue
                seen.add(normalized)
                items.append({
                    "norm": normalized,
                    "text": segment,
                    "file": filename,
                    "line": line_number,
                    "bucket": classify_bucket(len(normalized)),
                    "priority": "primary" if filename in PRIMARY_FILES else "supplement",
                    "split": was_split,
                })
    stats["unique_texts"] = len(items)
    return items, file_info, stats


def near_reference(item: dict, reference_items: list[tuple[int, set[str]]], threshold: float = REFERENCE_NEAR_DUP_JACCARD) -> bool:
    features = char_bigrams(item["norm"])
    length = len(item["norm"])
    for other_length, other_features in reference_items:
        if other_length == 0:
            continue
        if min(length, other_length) / max(length, other_length) < 0.5:
            continue
        if jaccard(features, other_features) >= threshold:
            return True
    return False


def load_wikipedia_items(path: Path) -> list[dict]:
    """Load frozen Chinese Wikipedia long sentences (CC BY-SA 4.0 snapshot)."""
    if not path.is_file():
        return []
    items: list[dict] = []
    with io.open(path, encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            line = raw.rstrip("\n").rstrip("\r")
            if not line or line.startswith("pageid\t"):
                continue
            parts = line.split("\t")
            if len(parts) != 5:
                continue
            try:
                pageid = int(parts[0])
                oldid = int(parts[1])
            except ValueError:
                continue
            converted = convert_to_simplified(parts[4].strip())
            normalized = clean_natural(converted)
            if normalized is None or classify_bucket(len(normalized)) != "long":
                continue
            items.append({
                "norm": normalized,
                "text": converted,
                "file": "wikipedia",
                "line": line_number,
                "bucket": "long",
                "priority": "wikipedia",
                "pageid": pageid,
                "oldid": oldid,
                "url": parts[3].strip(),
            })
    return items


def select_natural(items: list[dict], reference_items: list[tuple[int, set[str]]]):
    """Select natural dev sentences with primary CC0 chat files first.

    Primary: setences.txt / g0v_slack_rand0m.txt / tg_common_voice.txt.
    Other files only fill a bucket if the primary pool is insufficient.
    All candidates must pass exact/near-duplicate isolation against v3/v1/v2.
    """
    clusters = build_clusters(items)
    cluster_buckets: dict[tuple[str, str], list[list[int]]] = defaultdict(list)
    for cluster in clusters:
        bucket_counts = Counter(items[i]["bucket"] for i in cluster)
        bucket = bucket_counts.most_common(1)[0][0]
        priorities = {items[i]["priority"] for i in cluster}
        if "primary" in priorities:
            priority = "primary"
        elif "wikipedia" in priorities:
            priority = "wikipedia"
        else:
            priority = "supplement"
        cluster_buckets[(bucket, priority)].append(cluster)
    chosen: dict[str, list[int]] = {bucket: [] for bucket in NATURAL_TARGETS}
    used: set[int] = set()
    stats = Counter()

    def take_bucket(priority: str, bucket: str, target: int) -> None:
        if len(chosen[bucket]) >= target:
            return
        clusters_for_bucket = sorted(
            cluster_buckets[(bucket, priority)],
            key=lambda cluster: stable_key(min(items[i]["norm"] for i in cluster)),
        )
        for cluster in clusters_for_bucket:
            members = sorted(
                [i for i in cluster
                 if items[i]["bucket"] == bucket and items[i]["priority"] == priority and i not in used],
                key=lambda i: stable_key(items[i]["norm"]),
            )
            for index in members[:MAX_CLUSTER_TAKE]:
                if len(chosen[bucket]) >= target:
                    break
                if near_reference(items[index], reference_items):
                    stats["rejected_reference_near_duplicate"] += 1
                    continue
                chosen[bucket].append(index)
                used.add(index)
            if len(chosen[bucket]) >= target:
                break

    for bucket in ("long", "medium", "short"):
        target = NATURAL_TARGETS[bucket]
        take_bucket("primary", bucket, target)
        if len(chosen[bucket]) < target:
            take_bucket("supplement", bucket, target)
        if bucket == "long" and len(chosen[bucket]) < target:
            take_bucket("wikipedia", bucket, target)
        stats["selected_" + bucket] = len(chosen[bucket])

    natural_total_target = sum(NATURAL_TARGETS.values())
    total = sum(len(v) for v in chosen.values())
    # Fill the configured natural total from remaining primary then supplement items.
    if total < natural_total_target:
        # Fill the long-sentence shortfall with medium sentences first, then
        # short sentences, so the natural distribution stays near
        # short ~300 / medium ~250 rather than inflating short.
        for bucket in ("medium", "short"):
            ordering = sorted(
                [i for i in range(len(items)) if items[i]["bucket"] == bucket],
                key=lambda i: (0 if items[i]["priority"] == "primary" else 1, stable_key(items[i]["norm"])),
            )
            for index in ordering:
                if total >= natural_total_target:
                    break
                if index in used:
                    continue
                if near_reference(items[index], reference_items):
                    stats["rejected_reference_near_duplicate"] += 1
                    continue
                chosen[bucket].append(index)
                used.add(index)
                total += 1
                stats["selected_" + bucket] = len(chosen[bucket])
    if total < natural_total_target:
        raise SystemExit("natural candidates cannot reach natural target: " + str(total))
    stats["clusters"] = len(clusters)
    stats["multi_member_clusters"] = sum(1 for cluster in clusters if len(cluster) > 1)
    selected: list[dict] = []
    for bucket, indexes in chosen.items():
        for index in indexes:
            selected.append(items[index])
    return selected, stats


# ---------------------------------------------------------------------------
# Special categories (generic dictionary words only)
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
        if frequency < 1000 or word not in dict_words:
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


def build_typo_items(entries: list[dict], count: int) -> list[dict]:
    items: list[dict] = []
    seen_inputs: set[str] = set()
    for entry in entries:
        pinyin = entry["pinyin"]
        if len(pinyin) < 3:
            continue
        middle = max(1, min(len(pinyin) - 2, len(pinyin) // 2))
        variants = []
        variants.append((pinyin[:middle] + pinyin[middle + 1:], "missing"))
        variants.append((pinyin[:middle] + pinyin[middle] + pinyin[middle:], "extra"))
        if middle + 1 < len(pinyin):
            variants.append((pinyin[:middle] + pinyin[middle + 1] + pinyin[middle] + pinyin[middle + 2:], "swap"))
        neighbors = QWERTY_NEIGHBORS.get(pinyin[middle])
        if neighbors:
            variants.append((pinyin[:middle] + neighbors[0] + pinyin[middle + 1:], "adjacent"))
        order = int(stable_key(entry["word"])[:8], 16) % len(variants)
        variants = variants[order:] + variants[:order]
        for variant, subtype in variants:
            if variant == pinyin or len(variant) < 2 or not variant.isalpha() or variant in seen_inputs:
                continue
            note = {
                "missing": "漏字母：正确拼音 " + pinyin,
                "extra": "多字母：正确拼音 " + pinyin,
                "swap": "相邻交换：正确拼音 " + pinyin,
                "adjacent": "邻键：正确拼音 " + pinyin,
            }[subtype]
            items.append({
                "category": "typo",
                "input": variant,
                "expected": entry["word"],
                "note": note,
                "source": "template:typo:" + entry["word"],
            })
            seen_inputs.add(variant)
            break
        if len(items) >= count:
            break
    return items[:count]


def build_shorthand_items(entries: list[dict], count: int) -> list[dict]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for entry in entries:
        initials = "".join(syllable[0] for syllable in entry["syllables"])
        if 2 <= len(initials) <= 4:
            groups[initials].append(entry)
    items: list[dict] = []
    for initials in sorted(groups, key=lambda key: stable_key(key)):
        group = sorted(groups[initials], key=lambda entry: (-entry["freq"], entry["word"]))
        if len(group) >= 2:
            words = [entry["word"] for entry in group[:3]]
            note = "简拼：全声母，存在歧义"
        else:
            words = [group[0]["word"]]
            note = "简拼：全声母"
        items.append({
            "category": "shorthand",
            "input": initials,
            "expected": "|".join(words),
            "note": note,
            "source": "template:shorthand:" + initials,
        })
        if len(items) >= count:
            break
    return items


def build_mixed_pinyin_items(entries: list[dict], count: int) -> list[dict]:
    items: list[dict] = []
    seen_inputs: set[str] = set()
    for entry in entries:
        syllables = entry["syllables"]
        if len(syllables) < 2:
            continue
        full = "".join(syllables)
        mixed = "".join(syllables[:-1]) + syllables[-1][0]
        if mixed == full or len(mixed) < 2 or mixed in seen_inputs:
            continue
        items.append({
            "category": "mixed_pinyin",
            "input": mixed,
            "expected": entry["word"],
            "note": "混拼：完整音节+" + syllables[-1][0] + "（末音节声母）",
            "source": "template:mixed_pinyin:" + entry["word"],
        })
        seen_inputs.add(mixed)
        if len(items) >= count:
            break
    return items


MIXED_EN_SEEDS: list[tuple[str, str]] = [
    ("这个方案用AWS", "AWS"),
    ("数据放在Redis", "Redis"),
    ("服务部署在Docker", "Docker"),
    ("代码提交到GitHub", "GitHub"),
    ("文档放在Notion", "Notion"),
    ("任务记录在Jira", "Jira"),
    ("团队沟通用Slack", "Slack"),
    ("文件发我Email", "Email"),
    ("这个版本是Beta", "Beta"),
    ("页面用Figma", "Figma"),
    ("接口返回JSON", "JSON"),
    ("配置写成YAML", "YAML"),
    ("脚本用Python", "Python"),
    ("前端用React", "React"),
    ("后端用Spring", "Spring"),
    ("数据库用MySQL", "MySQL"),
    ("缓存用Redis", "Redis"),
    ("容器用Kubernetes", "Kubernetes"),
    ("部署到AWS", "AWS"),
    ("监控用Prometheus", "Prometheus"),
    ("日志发到Kibana", "Kibana"),
    ("测试用JUnit", "JUnit"),
    ("构建用Gradle", "Gradle"),
    ("代码检查用Sonar", "Sonar"),
    ("图片用Photoshop", "Photoshop"),
    ("设计用Sketch", "Sketch"),
    ("邮件用Outlook", "Outlook"),
    ("日历用Calendar", "Calendar"),
    ("笔记用Evernote", "Evernote"),
    ("文件传到Drive", "Drive"),
    ("视频用YouTube", "YouTube"),
    ("音乐用Spotify", "Spotify"),
    ("地图用GoogleMap", "GoogleMap"),
    ("搜索用Google", "Google"),
    ("浏览器用Chrome", "Chrome"),
    ("手机是iPhone", "iPhone"),
    ("平板是iPad", "iPad"),
    ("电脑是Windows", "Windows"),
    ("系统是Linux", "Linux"),
    ("这个App是Android", "Android"),
    ("耳机是AirPods", "AirPods"),
    ("手表是AppleWatch", "AppleWatch"),
    ("这个版本用Kotlin", "Kotlin"),
    ("脚本用Bash", "Bash"),
    ("页面用HTML", "HTML"),
    ("样式用CSS", "CSS"),
    ("框架用Flutter", "Flutter"),
    ("客户端用Swift", "Swift"),
    ("服务器是Ubuntu", "Ubuntu"),
    ("这个文件是PDF", "PDF"),
    ("表格用Excel", "Excel"),
    ("演示用PowerPoint", "PowerPoint"),
    ("文档是Word", "Word"),
    ("这个图标是SVG", "SVG"),
    ("图片是PNG", "PNG"),
    ("压缩包是ZIP", "ZIP"),
    ("这个接口是REST", "REST"),
    ("服务用Go写", "Go"),
    ("缓存用Memcached", "Memcached"),
    ("消息用Kafka", "Kafka"),
    ("队列用RabbitMQ", "RabbitMQ"),
    ("搜索用Elastic", "Elastic"),
    ("数据库是MongoDB", "MongoDB"),
    ("统计用SQL", "SQL"),
    ("报表用Tableau", "Tableau"),
    ("看板用Trello", "Trello"),
    ("会议用Zoom", "Zoom"),
    ("聊天用Teams", "Teams"),
    ("语音用Discord", "Discord"),
    ("文件用Dropbox", "Dropbox"),
    ("备份用OneDrive", "OneDrive"),
    ("密码用Keychain", "Keychain"),
    ("代码用VSCode", "VSCode"),
    ("终端用Terminal", "Terminal"),
    ("这个PR用GitLab", "GitLab"),
    ("镜像用Harbor", "Harbor"),
    ("域名用Cloudflare", "Cloudflare"),
    ("证书用OpenSSL", "OpenSSL"),
]


def build_mixed_en_items(count: int, reference_norms: set[str]) -> list[dict]:
    items: list[dict] = []
    seen_inputs: set[str] = set()
    for expected_text, tail in MIXED_EN_SEEDS:
        if not expected_text.endswith(tail):
            continue
        prefix = expected_text[: len(expected_text) - len(tail)]
        if not prefix or not all(cjk(ch) for ch in prefix):
            continue
        pinyin = pinyin_of(prefix)
        if not pinyin or not pinyin.isalpha():
            continue
        if norm_text(expected_text) in reference_norms:
            continue
        input_text = pinyin + tail.lower()
        if input_text in seen_inputs:
            continue
        seen_inputs.add(input_text)
        items.append({
            "category": "mixed_en",
            "input": input_text,
            "expected": expected_text,
            "note": "交互项：中英混输（中文前缀 + 英文原文提交）",
            "source": "template:mixed_en:" + stable_key(expected_text)[:10],
        })
        if len(items) >= count:
            break
    return items


def take_unique(candidates: list[dict], count: int, used_inputs: set[str]) -> list[dict]:
    result: list[dict] = []
    for item in candidates:
        if item["input"] in used_inputs:
            continue
        used_inputs.add(item["input"])
        result.append(item)
        if len(result) >= count:
            break
    return result


def render_rows(rows: list[dict]) -> tuple[str, int]:
    rows = sorted(rows, key=lambda row: (CATEGORY_ORDER.index(row["category"]), stable_key(row["input"])))
    counters: Counter = Counter()
    lines: list[str] = []
    for row in rows:
        category = row["category"]
        counters[category] += 1
        case_id = "d05-" + category + "-" + str(counters[category]).zfill(3)
        fields = [case_id, category, row["input"], row["expected"], row["note"], row["source"]]
        for field in fields:
            if "\t" in field or "\n" in field:
                raise SystemExit("field contains tab/newline")
        lines.append("\t".join(fields))
    return "\n".join(lines) + "\n", len(rows)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def attribution_text(source_archive_sha: str) -> str:
    return (
        "# D05 diagnostic dev set - attribution\n"
        "\n"
        "This is a development/diagnostic set, not an acceptance set. It must\n"
        "never be copied into the production dictionary or used as validation.\n"
        "\n"
        "## CC0 sentences\n"
        "\n"
        "Natural sentences are selected from irvin/cc0-sentences, zh-TW files.\n"
        "Primary (remaining lines after v3 exclusion): setences.txt,\n"
        "g0v_slack_rand0m.txt, tg_common_voice.txt. Supplement: sayit.txt,\n"
        "lms.txt, cvsprint_201902.txt, exam_text.txt, gov_press_release.txt,\n"
        "cofacts.txt, web_slang.txt, chatlogs.txt.\n"
        "Repository: https://github.com/irvin/cc0-sentences\n"
        "License: CC0 1.0 Universal, https://creativecommons.org/publicdomain/zero/1.0/\n"
        "Archive: .tmp/cc0/cc0-sentences.zip\n"
        "SHA-256: " + source_archive_sha + "\n"
        "\n"
        "The source is traditional Chinese (zh-TW); text was converted with\n"
        "zhconv 1.4.3 to simplified Chinese before filtering. No Tatoeba text\n"
        "is used. The set excludes v3/v1/v2 exact and near-duplicate sentences.\n"
        "\n"
        "## Chinese Wikipedia long sentences\n"
        "\n"
        "natural_long sentences also include text from Chinese Wikipedia\n"
        "(https://zh.wikipedia.org/), licensed under Creative Commons\n"
        "Attribution-ShareAlike 4.0 International (CC BY-SA 4.0).\n"
        "Source snapshot: wikipedia_long_source.tsv. Each row records pageid,\n"
        "oldid, title, stable URL (oldid URL) and sentence; attribution is given\n"
        "through those revision links. The Wikipedia text is evaluation-only and\n"
        "is not copied into the production dictionary.\n"
        "\n"
        "## Special categories\n"
        "\n"
        "typo/shorthand/mixed_pinyin word candidates come from the jieba\n"
        "dictionary (MIT, https://github.com/fxsjy/jieba); pinyin strings use\n"
        "pypinyin (MIT, https://github.com/mozillazg/python-pinyin). They are\n"
        "constructed from generic dictionary words, not rewritten from v3/v2\n"
        "expected sentences.\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the D05 cross-source diagnostic dev set.")
    parser.add_argument("--source-dir", default=".tmp/cc0/extract/cc0-sentences-master/zh-TW")
    parser.add_argument("--dictionary", default="app/src/main/assets/pinyin_dict.tsv")
    parser.add_argument("--jieba", default=".tmp/jieba.txt")
    parser.add_argument("--out-dir", default="eval/d05/dev")
    parser.add_argument("--zip", default=".tmp/cc0/cc0-sentences.zip")
    parser.add_argument("--wikipedia", default="eval/d05/dev/wikipedia_long_source.tsv")
    args = parser.parse_args()

    source_dir = (ROOT / args.source_dir).resolve()
    dictionary_path = (ROOT / args.dictionary).resolve()
    jieba_path = (ROOT / args.jieba).resolve()
    out_dir = (ROOT / args.out_dir).resolve()
    zip_path = (ROOT / args.zip).resolve()
    wikipedia_path = (ROOT / args.wikipedia).resolve()
    for path in (dictionary_path, jieba_path):
        if not path.is_file():
            raise SystemExit("missing input: " + str(path))

    dict_words = load_dictionary_words(dictionary_path)
    reference_texts, reference_files = load_reference_data()
    reference_set = set(reference_texts)
    reference_items = [(len(text), char_bigrams(text)) for text in reference_texts if text]

    natural_items, natural_file_info, load_stats = load_natural_items(source_dir)
    wikipedia_items = load_wikipedia_items(wikipedia_path)
    load_stats["wikipedia_snapshot_rows"] = len(wikipedia_items)
    natural_pool: list[dict] = []
    stats = Counter(load_stats)
    seen_norms: set[str] = set()
    for item in natural_items + wikipedia_items:
        if item["norm"] in seen_norms:
            stats["rejected_duplicate"] += 1
            continue
        seen_norms.add(item["norm"])
        if item["norm"] in dict_words or item["text"] in dict_words:
            stats["rejected_dictionary_leak"] += 1
            continue
        if item["norm"] in reference_set:
            stats["rejected_v1_v2_v3_exact"] += 1
            continue
        pinyin = pinyin_of(item["norm"])
        if not pinyin or len(pinyin) > MAX_PINYIN_LENGTH:
            stats["rejected_pinyin_length"] += 1
            continue
        item["pinyin"] = pinyin
        natural_pool.append(item)
    stats["natural_pool"] = len(natural_pool)
    natural_selected, selection_stats = select_natural(natural_pool, reference_items)
    stats.update(selection_stats)

    entries = load_jieba_entries(jieba_path, dict_words)
    used_inputs: set[str] = {item["pinyin"] for item in natural_selected}
    special_candidates = {
        "typo": build_typo_items(entries, SPECIAL_TARGETS["typo"] * 3),
        "shorthand": build_shorthand_items(entries, SPECIAL_TARGETS["shorthand"] * 3),
        "mixed_pinyin": build_mixed_pinyin_items(entries, SPECIAL_TARGETS["mixed_pinyin"] * 3),
        "mixed_en": build_mixed_en_items(SPECIAL_TARGETS["mixed_en"] * 2, reference_set),
    }
    special_selected: dict[str, list[dict]] = {}
    for category in ("typo", "shorthand", "mixed_pinyin", "mixed_en"):
        selected = take_unique(special_candidates[category], SPECIAL_TARGETS[category], used_inputs)
        if len(selected) < SPECIAL_TARGETS[category]:
            raise SystemExit("special category " + category + " cannot reach target")
        special_selected[category] = selected

    rows: list[dict] = []
    for item in natural_selected:
        if item.get("priority") == "wikipedia":
            rows.append({
                "category": "natural_" + item["bucket"],
                "input": item["pinyin"],
                "expected": item["text"],
                "note": "中文维基百科（CC BY-SA 4.0，pageid/oldid/URL 见 wikipedia_long_source.tsv）",
                "source": "wikipedia:" + str(item["pageid"]) + ":" + str(item["oldid"]),
            })
        else:
            rows.append({
                "category": "natural_" + item["bucket"],
                "input": item["pinyin"],
                "expected": item["text"],
                "note": "CC0 跨来源诊断句（" + item["file"] + "，zh-TW→简体" + ("；分句" if item.get("split") else "") + "）",
                "source": "cc0:" + item["file"] + ":" + str(item["line"]),
            })
    for category, selected in special_selected.items():
        rows.extend(selected)

    content, row_count = render_rows(rows)
    if row_count < 1050:
        raise SystemExit("D05 dev below 1050 rows: " + str(row_count))
    if row_count > 1200:
        raise SystemExit("D05 dev above 1200 rows: " + str(row_count))
    dev_path = out_dir / "dev.tsv"
    write_text(dev_path, content)
    dev_sha = sha256_bytes(content.encode("utf-8"))
    category_counts = Counter(row["category"] for row in rows)
    ordered_counts = {category: category_counts.get(category, 0) for category in CATEGORY_ORDER}
    natural_source_counts: dict[str, dict[str, int]] = {}
    for row in rows:
        if not row["category"].startswith("natural_"):
            continue
        filename = "wikipedia" if row["source"].startswith("wikipedia:") else row["source"].split(":")[1]
        bucket_counts = natural_source_counts.setdefault(row["category"], {})
        bucket_counts[filename] = bucket_counts.get(filename, 0) + 1
    archive_sha = sha256_file(zip_path) if zip_path.is_file() else ""
    manifest = {
        "schema_version": "d05-dev-v1",
        "generator": "eval/d05/dev/build_d05_dev.py",
        "generator_version": "1.2.0",
        "command": "python eval/d05/dev/build_d05_dev.py",
        "purpose": "D05 cross-source diagnostic development set; per-case diagnosis allowed, never acceptance",
        "source": {
            "name": "irvin/cc0-sentences zh-TW remaining lines + Chinese Wikipedia CC BY-SA 4.0 long-sentence snapshot",
            "repository": "https://github.com/irvin/cc0-sentences",
            "license": "CC0-1.0",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "archive": ".tmp/cc0/cc0-sentences.zip",
            "archive_sha256": archive_sha,
            "files": natural_file_info,
            "conversion": "zhconv 1.4.3 zh-TW -> zh-CN (simplified), UTF-8",
            "primary_files": PRIMARY_FILES,
            "supplement_files": SUPPLEMENT_FILES,
        },
        "wikipedia_source": {
            "file": "eval/d05/dev/wikipedia_long_source.tsv",
            "sha256": sha256_file(wikipedia_path) if wikipedia_path.is_file() else "",
            "rows": 0 if not wikipedia_path.is_file() else max(0, sum(1 for _ in io.open(wikipedia_path, encoding="utf-8")) - 1),
            "license": "CC BY-SA 4.0",
            "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
            "attribution": "Chinese Wikipedia (https://zh.wikipedia.org/); pageid, oldid and stable URL recorded per row in wikipedia_long_source.tsv",
            "api_endpoint": "https://zh.wikipedia.org/w/api.php",
            "fetch_script": "eval/d05/dev/fetch_wikipedia_long.py",
            "retrieved_at": "2026-11-08",
            "retrieval_note": "snapshot fetched via MediaWiki API (random zh Wikipedia articles, intro extracts), converted to simplified Chinese; not live at build time",
        },
        "reference_files": reference_files,
        "files": {
            "dev": {
                "path": "eval/d05/dev/dev.tsv",
                "sha256": dev_sha,
                "rows": row_count,
            }
        },
        "category_counts": ordered_counts,
        "natural_source_counts": natural_source_counts,
        "long_pinyin_over_64": sum(1 for row in rows if row["category"] == "natural_long" and len(row["input"]) > 64),
        "long_shortfall_note": "natural_long user target >=100 met: actual=" + str(ordered_counts.get("natural_long", 0)) + " (selector target 150). Long sentences are supplied by the Chinese Wikipedia CC BY-SA 4.0 snapshot (17-30 CJK complete sentences, pageid/oldid/URL recorded); no production engine output or acceptance sentence is used.",
        "filters": {
            "natural_script": "simplified Chinese (CJK Unified Ideographs encodable with GB2312) plus Chinese punctuation",
            "natural_length_chars": {"natural_short": [2, 8], "natural_medium": [9, 16], "natural_long": [17, 30]},
            "max_pinyin_length": MAX_PINYIN_LENGTH,
            "dedup": "exact normalized character sequence",
            "excluded": {
                "dictionary_whole_sentence": "normalized natural sentence equals a whole pinyin_dict.tsv word",
                "v1_v2_v3_exact": "normalized natural sentence equals any expected text in v1/v2/v3",
                "v1_v2_v3_near_duplicate": "character 2-gram Jaccard >= 0.75 against v1/v2/v3 expected text",
            },
            "rejected_counts": dict(stats),
        },
        "special_construction": {
            "source": ".tmp/jieba.txt (MIT); pypinyin 0.55.0",
            "jieba_sha256": sha256_file(jieba_path),
            "categories": SPECIAL_TARGETS,
            "rule": "constructed from generic jieba dictionary words; never rewritten from v3/v2 expected sentences",
        },
        "split": {
            "mode": "dev-only diagnostic set; no acceptance split",
            "natural_near_duplicate": {
                "features": "character 2-gram Jaccard",
                "jaccard_threshold": NEAR_DUP_JACCARD,
                "max_members_per_cluster": MAX_CLUSTER_TAKE,
            },
        },
        "engine_independence": "no PinyinEngine call during generation",
    }
    write_text(out_dir / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    write_text(out_dir / "ATTRIBUTION.md", attribution_text(archive_sha))
    print("rows: " + str(row_count) + " sha256=" + dev_sha)
    print("category counts: " + json.dumps(ordered_counts, ensure_ascii=False))
    print("filter stats: " + json.dumps(dict(stats), ensure_ascii=False))
    print("wrote: " + str(dev_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
