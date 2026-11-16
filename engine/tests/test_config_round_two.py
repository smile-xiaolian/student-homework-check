import unittest

from checker.config import CheckConfig, normalize_config


class NormalizeConfigTests(unittest.TestCase):
    """Change 1 (R19): config snapshot key-name normalisation.

    CamelCase, snake_case and nested historical layouts all normalise to the
    single canonical structure; missing keys receive their defaults.
    """

    def _target(self) -> dict:
        return {
            "mode": "TEXT",
            "ngram_size": 3,
            "simhash_bits": 64,
            "thresholds": {
                "text": {"warning": 22.0, "severe": 55.0},
                "code": {"warning": 33.0, "severe": 66.0},
            },
            "libraries": ["CLASS", "WHITELIST"],
            "exclude": {"references": False},
            "max_sources": 200,
            "text_score_weights": {"sentence": 0.5, "full": 0.5},
            "lsh": {"enabled": True, "num_hashes": 64, "num_bands": 8,
                    "candidate_threshold": 0.6, "max_candidates": 100},
            "chinese_seg": {"enabled": True},
            "code": {"function_level": {"enabled": True}},
        }

    def _defaults(self) -> dict:
        return normalize_config({})

    def test_camel_case_normalises_to_target(self):
        raw = {
            "mode": "TEXT",
            "ngramSize": 3,
            "simhashBits": 64,
            "textWarningThreshold": 22,
            "textSevereThreshold": 55,
            "structureWarningThreshold": 33,
            "structureSevereThreshold": 66,
            "libraries": ["CLASS", "WHITELIST"],
            "exclude": {"references": False},
            "maxSources": 200,
            "textScoreWeights": {"sentence": 0.5, "full": 0.5},
            "lsh": {"enabled": True, "numHashes": 64, "numBands": 8,
                    "candidateThreshold": 0.6, "maxCandidates": 100},
            "chineseSeg": {"enabled": True},
            "code": {"functionLevel": {"enabled": True}},
        }
        self.assertEqual(normalize_config(raw), self._target())

    def test_snake_case_with_historical_groups_normalises_to_target(self):
        raw = {
            "mode": "TEXT",
            "ngram_size": 3,
            "simhash_bits": 64,
            "text_thresholds": {"warning": 22, "severe": 55},
            "struct_thresholds": {"warning": 33, "severe": 66},
            "libraries": ["CLASS", "WHITELIST"],
            "exclude": {"references": False},
            "max_sources": 200,
            "text_score_weights": {"sentence": 0.5, "full": 0.5},
            "lsh": {"enabled": True, "num_hashes": 64, "num_bands": 8,
                    "candidate_threshold": 0.6, "max_candidates": 100},
            "chinese_seg": {"enabled": True},
            "code": {"function_level": {"enabled": True}},
        }
        self.assertEqual(normalize_config(raw), self._target())

    def test_nested_thresholds_normalises_to_target(self):
        raw = {
            "mode": "TEXT",
            "ngram_size": 3,
            "simhash_bits": 64,
            "thresholds": {
                "text": {"warning": 22, "severe": 55},
                "code": {"warning": 33, "severe": 66},
            },
            "libraries": ["CLASS", "WHITELIST"],
            "exclude": {"references": False},
            "max_sources": 200,
            "text_score_weights": {"sentence": 0.5, "full": 0.5},
            "lsh": {"enabled": True, "num_hashes": 64, "num_bands": 8,
                    "candidate_threshold": 0.6, "max_candidates": 100},
            "chinese_seg": {"enabled": True},
            "code": {"function_level": {"enabled": True}},
        }
        self.assertEqual(normalize_config(raw), self._target())

    def test_missing_keys_get_defaults(self):
        defaults = self._defaults()
        self.assertEqual(defaults["mode"], "TEXT")
        self.assertEqual(defaults["ngram_size"], 3)
        self.assertEqual(defaults["simhash_bits"], 64)
        self.assertEqual(defaults["thresholds"], {
            "text": {"warning": 20.0, "severe": 50.0},
            "code": {"warning": 30.0, "severe": 60.0},
        })
        self.assertEqual(defaults["libraries"], ["CLASS"])
        self.assertEqual(defaults["exclude"], {})
        self.assertEqual(defaults["max_sources"], 300)
        self.assertEqual(defaults["text_score_weights"], {"sentence": 0.6, "full": 0.4})
        self.assertEqual(defaults["lsh"], {
            "enabled": False, "num_hashes": 128, "num_bands": 16,
            "candidate_threshold": 0.7, "max_candidates": 200,
        })
        self.assertEqual(defaults["chinese_seg"], {"enabled": False})
        self.assertEqual(defaults["code"], {"function_level": {"enabled": False}})

    def test_partial_config_fills_remaining_defaults(self):
        normalized = normalize_config({"thresholds": {"text": {"warning": 12}}})
        self.assertEqual(normalized["thresholds"]["text"]["warning"], 12.0)
        # The untouched threshold keeps its default rather than becoming None.
        self.assertEqual(normalized["thresholds"]["text"]["severe"], 50.0)
        self.assertEqual(normalized["lsh"]["enabled"], False)


class CheckConfigFromSnapshotTests(unittest.TestCase):
    def test_switch_fields_flow_through(self):
        config = CheckConfig.from_snapshot({
            "mode": "TEXT",
            "lsh": {"enabled": True, "num_hashes": 64, "num_bands": 8},
            "chinese_seg": {"enabled": True},
            "code": {"function_level": {"enabled": True}},
            "text_score_weights": {"sentence": 0.4, "full": 0.6},
        })
        self.assertTrue(config.lsh_enabled)
        self.assertEqual(config.lsh_num_hashes, 64)
        self.assertEqual(config.lsh_num_bands, 8)
        self.assertTrue(config.chinese_seg_enabled)
        self.assertTrue(config.function_level_enabled)
        self.assertEqual(config.text_score_weights, {"sentence": 0.4, "full": 0.6})

    def test_defaults_keep_switches_off(self):
        config = CheckConfig.from_snapshot({})
        self.assertFalse(config.lsh_enabled)
        self.assertFalse(config.chinese_seg_enabled)
        self.assertFalse(config.function_level_enabled)


if __name__ == "__main__":
    unittest.main()
