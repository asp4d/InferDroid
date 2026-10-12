"""Keep native verification from accepting a wrong or unfinished final answer."""

import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "thinking_api", Path(__file__).resolve().parents[1] / "test-thinking-api.py")
thinking = importlib.util.module_from_spec(spec)
spec.loader.exec_module(thinking)


class FinalAnswerTests(unittest.TestCase):
    def result(self, text, finish="stop"):
        return {"name": "verification regression", "text": text, "finish_reason": finish}

    def test_accepts_final_answer_and_numeric_history_reply(self):
        for text in ("17 times 23 is 391.\nANSWER: 391", "391", "ANSWER: 391.0"):
            with self.subTest(text=text):
                thinking.correct(self.result(text))

    def test_rejects_numbers_containing_the_expected_answer(self):
        for text in ("ANSWER: 3910", "ANSWER: 1391", "391 is not the answer"):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                thinking.correct(self.result(text))

    def test_checks_the_final_answer_instead_of_intermediate_work(self):
        for text in ("391 is an intermediate value.\nANSWER: 392", "ANSWER: 391\nANSWER: 392"):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                thinking.correct(self.result(text))

    def test_rejects_truncated_and_empty_answers(self):
        for result in (self.result("ANSWER: 391", "length"), self.result("")):
            with self.subTest(result=result), self.assertRaises(AssertionError):
                thinking.correct(result)


if __name__ == "__main__":
    unittest.main()
