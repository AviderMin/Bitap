import unittest

from audit_training_overlap import audit


class TrainingOverlapTest(unittest.TestCase):
    def test_checks_training_sentence_outside_existing_evaluation_sets(self):
        result = audit(["甲乙丙丁。"], [("short", "甲乙丙丁！")])
        self.assertEqual(result["counts"]["exactTrainingOverlap"], 1)

    def test_detects_near_duplicate_without_counting_it_as_exact(self):
        result = audit(["甲乙丙丁戊己庚辛壬癸"], [("medium", "甲乙丙丁戊己庚辛壬癸子")])
        self.assertEqual(result["counts"]["exactTrainingOverlap"], 0)
        self.assertEqual(result["counts"]["nearTrainingOverlapIncludingExact"], 1)

    def test_shared_training_bigrams_alone_do_not_mean_sentence_overlap(self):
        result = audit(["甲乙丙丁", "丙丁戊己"], [("short", "甲乙戊己")])
        self.assertEqual(result["counts"]["nearTrainingOverlapIncludingExact"], 0)

    def test_duplicate_training_rows_count_each_acceptance_case_once(self):
        result = audit(["甲乙丙丁", "甲乙丙丁"], [("short", "甲乙丙丁")])
        self.assertEqual(result["counts"]["rows"], 1)
        self.assertEqual(result["byCategory"]["short"]["exactTrainingOverlap"], 1)


if __name__ == "__main__":
    unittest.main()
