"""Host-only librime quality probe. No production code, learning or editor writes.

Uses the pinned release's C ABI. The explicit DLL/header paths must belong to
the same distribution. Results are dev-only; validation needs --acceptance.
"""
from __future__ import annotations

import argparse
import ctypes as c
import hashlib
import json
import re
import time
from pathlib import Path


class Traits(c.Structure):
    _fields_ = [("data_size", c.c_int)] + [
        (name, c.c_char_p) for name in (
            "shared_data_dir", "user_data_dir", "distribution_name",
            "distribution_code_name", "distribution_version", "app_name",
        )
    ] + [("modules", c.c_void_p), ("min_log_level", c.c_int)] + [
        (name, c.c_char_p) for name in ("log_dir", "prebuilt_data_dir", "staging_dir")
    ]


class Candidate(c.Structure):
    _fields_ = [("text", c.c_char_p), ("comment", c.c_char_p), ("reserved", c.c_void_p)]


class Iterator(c.Structure):
    _fields_ = [("ptr", c.c_void_p), ("index", c.c_int), ("candidate", Candidate)]


class Commit(c.Structure):
    _fields_ = [("data_size", c.c_int), ("text", c.c_char_p)]


def init_struct(kind):
    value = kind()
    value.data_size = c.sizeof(kind) - c.sizeof(c.c_int)
    return value


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class RimeProbe:
    def __init__(self, dll: Path, shared: Path, user: Path):
        # Rime uses process-global state. Exactly one probe per process.
        self.lib = c.CDLL(str(dll.resolve()))
        self.session = 0
        signatures = {
            "RimeSetup": ([c.POINTER(Traits)], None),
            "RimeInitialize": ([c.POINTER(Traits)], None),
            "RimeFinalize": ([], None),
            "RimeStartMaintenance": ([c.c_int], c.c_int),
            "RimeJoinMaintenanceThread": ([], None),
            "RimeCreateSession": ([], c.c_size_t),
            "RimeDestroySession": ([c.c_size_t], c.c_int),
            "RimeSelectSchema": ([c.c_size_t, c.c_char_p], c.c_int),
            "RimeSetOption": ([c.c_size_t, c.c_char_p, c.c_int], None),
            "RimeClearComposition": ([c.c_size_t], None),
            "RimeSetInput": ([c.c_size_t, c.c_char_p], c.c_int),
            "RimeCandidateListBegin": ([c.c_size_t, c.POINTER(Iterator)], c.c_int),
            "RimeCandidateListNext": ([c.POINTER(Iterator)], c.c_int),
            "RimeCandidateListEnd": ([c.POINTER(Iterator)], None),
            "RimeSelectCandidate": ([c.c_size_t, c.c_size_t], c.c_int),
            "RimeGetCommit": ([c.c_size_t, c.POINTER(Commit)], c.c_int),
            "RimeFreeCommit": ([c.POINTER(Commit)], c.c_int),
        }
        for name, (args, result) in signatures.items():
            fn = getattr(self.lib, name)
            fn.argtypes, fn.restype = args, result
        self.traits = init_struct(Traits)
        self.traits.shared_data_dir = str(shared.resolve()).encode()
        self.traits.user_data_dir = str(user.resolve()).encode()
        self.traits.app_name = b"rime.bitap_d04"
        self.traits.distribution_code_name = b"bitap-d04"
        self.traits.min_log_level = 2
        self.traits.log_dir = b""
        self.lib.RimeSetup(c.byref(self.traits))
        self.lib.RimeInitialize(c.byref(self.traits))
        self.lib.RimeStartMaintenance(1)
        self.lib.RimeJoinMaintenanceThread()
        self.session = self.lib.RimeCreateSession()
        if not self.session or not self.lib.RimeSelectSchema(self.session, b"bitap_d04"):
            self.close()
            raise RuntimeError("cannot deploy/select bitap_d04; inspect native stderr")
        self.lib.RimeSetOption(self.session, b"ascii_mode", 0)
        self.lib.RimeSetOption(self.session, b"simplification", 0)
        # Learning is disabled in the schema as well as at runtime.
        self.lib.RimeSetOption(self.session, b"_no_learning", 1)

    def _reset(self, raw: str):
        self.lib.RimeClearComposition(self.session)
        commit = init_struct(Commit)
        if self.lib.RimeGetCommit(self.session, c.byref(commit)):
            self.lib.RimeFreeCommit(c.byref(commit))
        # Not key-sequence syntax: apostrophes/spaces are passed literally.
        if not self.lib.RimeSetInput(self.session, raw.encode("utf-8")):
            raise RuntimeError("RimeSetInput failed")

    def _list(self, limit: int):
        iterator = Iterator()
        result = []
        if self.lib.RimeCandidateListBegin(self.session, c.byref(iterator)):
            try:
                while len(result) < limit and self.lib.RimeCandidateListNext(c.byref(iterator)):
                    result.append(iterator.candidate.text.decode("utf-8"))
            finally:
                self.lib.RimeCandidateListEnd(c.byref(iterator))
        return result

    def query(self, raw: str):
        started = time.perf_counter_ns()
        self._reset(raw)
        top = self._list(8)
        elapsed = time.perf_counter_ns() - started
        pool = self._list(64)
        candidates = []
        # C ABI iteration exposes text but not consumed range. Verify every
        # candidate by selecting it in an isolated, non-learning composition.
        # Full candidates commit exactly their text; partials remain composing.
        # This extra diagnostic pass is excluded from latency8Nanos.
        for index, text in enumerate(pool):
            self._reset(raw)
            if not self.lib.RimeSelectCandidate(self.session, index):
                raise RuntimeError("candidate list changed while learning was disabled")
            commit = init_struct(Commit)
            full = False
            if self.lib.RimeGetCommit(self.session, c.byref(commit)):
                try:
                    full = commit.text.decode("utf-8") == text
                finally:
                    self.lib.RimeFreeCommit(c.byref(commit))
            candidates.append({"word": text, "full": full})
        if [item["word"] for item in candidates[:8]] != top:
            raise RuntimeError("top8/64 prefix mismatch")
        return {"candidates": candidates, "latency8Nanos": elapsed}

    def close(self):
        if self.session:
            self.lib.RimeDestroySession(self.session)
            self.session = 0
        self.lib.RimeFinalize()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--shared", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--acceptance", action="store_true")
    args = parser.parse_args()
    if "validation" in args.corpus.name and not args.acceptance:
        parser.error("validation is acceptance-only; freeze configuration before --acceptance")
    # Never reuse a user's Rime directory, or silently carry a learned profile.
    args.work.mkdir(parents=True, exist_ok=True)
    user = args.work / "user"
    user.mkdir(exist_ok=False)
    cases = [line.split("\t") for line in args.corpus.read_text(encoding="utf-8").splitlines()
             if line and not line.startswith("#")]
    if any(len(row) != 6 for row in cases):
        raise ValueError("expected v2 six-column corpus")
    started = time.perf_counter_ns()
    probe = RimeProbe(args.dll, args.shared, user)
    startup = time.perf_counter_ns() - started
    try:
        for row in cases[:24]:
            probe._reset(row[2])
            probe._list(8)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", encoding="utf-8", newline="\n") as output:
            for index, row in enumerate(cases):
                result = probe.query(row[2])
                output.write(json.dumps({"id": row[0], **result}, ensure_ascii=False) + "\n")
                if index % 100 == 0:
                    print(f"processed {index}/{len(cases)}", flush=True)
    finally:
        probe.close()
    metadata = {
        "corpusSha256": digest(args.corpus), "dllSha256": digest(args.dll),
        "startupIncludingDeploymentNanos": startup, "cases": len(cases),
        "schemaSha256": digest(args.shared / "bitap_d04.schema.yaml"),
        "dictionarySha256": digest(args.shared / "pinyin_simp.dict.yaml"),
        "learning": False, "hostOnly": True,
        "coverage": "exact full commit verified by isolated selection; partial offsets not exported",
    }
    args.out.with_suffix(".meta.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
