"""Contract fixtures: partial matches, top5 cap and export identity checks."""
import json
import tempfile
import unittest
from pathlib import Path

from compare import interaction, load_results, matches, summarize


class ScoringContractTest(unittest.TestCase):
    def test_partial_equal_text_is_not_a_whole_input_hit(self):
        case = ["fixture", "natural_short", "nihaoma", "你好"]
        self.assertFalse(matches({"word": "你好", "full": False}, case))
        self.assertTrue(matches({"word": "你好", "full": True}, case))

    def test_rank_six_is_a_top5_miss_cost_nine(self):
        row = {"rank": 5, "oracle": True, "full": True, "latency8Nanos": 10}
        result = summarize([row])
        self.assertEqual(result["top5"], 0)
        self.assertEqual(result["rankCost"], 9)
        self.assertEqual(result["oracle64"], 1)

    def test_interaction_denominator_is_separate(self):
        self.assertTrue(interaction(["x", "mixed_en", "nihaoapi"]))
        self.assertTrue(interaction(["x", "number_punct", "3dian"]))
        self.assertFalse(interaction(["x", "number_punct", "sandian"]))

    def test_duplicate_and_missing_exports_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "export.jsonl"
            path.write_text(json.dumps({"id": "wrong"}) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "IDs"):
                load_results(path, [["expected", "natural_short", "nihao", "你好"]])

    def test_explicit_top8_must_drive_ranking_instead_of_oracle_pool(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "export.jsonl"
            candidate = {"word": "你好", "full": True}
            record = {"id": "x", "candidates": [candidate], "top8": [], "latency8Nanos": 1}
            path.write_text(json.dumps(record) + "\n", encoding="utf-8")
            path.with_suffix(".meta.json").write_text("{}", encoding="utf-8")
            report, _ = load_results(path, [["x", "natural_short", "nihao", "你好"]])
            self.assertEqual(report["engineScored"]["top1"], 0)
            self.assertEqual(report["engineScored"]["oracle64"], 1)


if __name__ == "__main__":
    unittest.main()
