import unittest

from reference_layout import is_numbered_reference
from reference_layout import reference_number
from reference_layout import reference_split_points


class ReferenceLayoutTest(unittest.TestCase):
    def test_recognizes_supported_reference_numbers(self):
        self.assertEqual(1, reference_number("[1] Author, 2024. Title."))
        self.assertEqual(23, reference_number("(23) Author, 2024. Title."))
        self.assertEqual(7, reference_number("7. Author, 2024. Title."))

    def test_requires_bibliographic_signal(self):
        self.assertTrue(is_numbered_reference("[1] Author et al. Journal, 2024."))
        self.assertTrue(is_numbered_reference("2. https://example.com/paper"))
        self.assertFalse(is_numbered_reference("1. 安装依赖"))
        self.assertFalse(is_numbered_reference("普通正文 2024"))

    def test_only_splits_sequential_bibliographic_entries(self):
        self.assertEqual(
            [0, 2],
            reference_split_points(
                ["[1] Author, 2023.", "continued", "[2] Author, 2024."]
            ),
        )
        self.assertEqual([], reference_split_points(["1. 安装依赖", "2. 启动服务"]))
        self.assertEqual([], reference_split_points(["[1] Author, 2023.", "[3] Other, 2024."]))


if __name__ == "__main__":
    unittest.main()
