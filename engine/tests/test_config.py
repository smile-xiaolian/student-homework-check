import unittest

from checker.config import CheckConfig


class ConfigTests(unittest.TestCase):
    def test_snake_case_thresholds_are_loaded(self):
        config = CheckConfig.from_snapshot({
            "mode": "TEXT",
            "text_thresholds": {"warning": 12, "severe": 42},
            "struct_thresholds": {"warning": 25, "severe": 55},
            "libraries": ["CLASS", "WHITELIST"],
        })

        self.assertEqual(config.text_warning_threshold, 12)
        self.assertEqual(config.text_severe_threshold, 42)
        self.assertEqual(config.structure_warning_threshold, 25)
        self.assertEqual(config.structure_severe_threshold, 55)
        self.assertEqual(config.libraries, ["CLASS", "WHITELIST"])


if __name__ == "__main__":
    unittest.main()
