import unittest

from checker.classifier import classify_file


class ClassifyFileTests(unittest.TestCase):
    """Change 6: six-bucket classification used for the manifest."""

    def test_bucket_a_core_source(self):
        cls = classify_file("src/Main.java")
        self.assertEqual(cls["bucket"], "A")
        self.assertEqual(cls["language"], "JAVA")
        self.assertTrue(cls["included"])
        self.assertIsNone(cls["excludeReason"])

    def test_bucket_b_build_and_scripts(self):
        cls = classify_file("build.gradle")
        self.assertEqual(cls["bucket"], "B")
        self.assertTrue(cls["included"])
        self.assertIsNone(cls["excludeReason"])

    def test_bucket_c_third_party(self):
        cls = classify_file("node_modules/lodash/index.js")
        self.assertEqual(cls["bucket"], "C")
        self.assertFalse(cls["included"])
        self.assertEqual(cls["excludeReason"], "THIRD_PARTY")

    def test_bucket_d_resource(self):
        cls = classify_file("images/logo.png")
        self.assertEqual(cls["bucket"], "D")
        self.assertFalse(cls["included"])
        self.assertEqual(cls["excludeReason"], "RESOURCE")

    def test_bucket_e_data(self):
        cls = classify_file("data/result.csv")
        self.assertEqual(cls["bucket"], "E")
        self.assertFalse(cls["included"])
        self.assertEqual(cls["excludeReason"], "DATA")

    def test_bucket_f_other(self):
        cls = classify_file("archive.xyz")
        self.assertEqual(cls["bucket"], "F")
        self.assertFalse(cls["included"])
        self.assertEqual(cls["excludeReason"], "OTHER")

    def test_unknown_extension_falls_to_other(self):
        cls = classify_file("mystery.blob")
        self.assertEqual(cls["bucket"], "F")
        self.assertEqual(cls["excludeReason"], "OTHER")


if __name__ == "__main__":
    unittest.main()
