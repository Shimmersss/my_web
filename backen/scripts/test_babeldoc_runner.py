import unittest

from reference_layout import (
    is_numbered_reference,
    is_reference_paragraph,
    reference_number,
    reference_section_flags,
    reference_split_points,
)


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

    def test_preserves_reference_headings_and_entries(self):
        self.assertTrue(is_reference_paragraph("References"))
        self.assertTrue(is_reference_paragraph("BIBLIOGRAPHY"))
        self.assertTrue(is_reference_paragraph("Literature Cited"))
        self.assertFalse(is_reference_paragraph("Reference"))
        self.assertTrue(
            is_reference_paragraph(
                "Smith, J. (2022). Reliable systems. Journal of Testing, 4(2), 1-9."
            )
        )
        self.assertTrue(is_reference_paragraph("[8] Doe et al. Proceedings, 2023."))

    def test_does_not_skip_normal_academic_prose(self):
        self.assertFalse(
            is_reference_paragraph(
                "Smith et al. (2022) showed that the proposed method improves accuracy."
            )
        )
        self.assertFalse(is_reference_paragraph("1. Install the package in 2024."))

    def test_preserves_entire_reference_section_until_appendix(self):
        self.assertEqual(
            [False, True, True, True, False, False],
            reference_section_flags(
                [
                    "Conclusion",
                    "References",
                    (
                        "Vaswani, A., Shazeer, N., Parmar, N. (2017). "
                        "Attention is all you need."
                    ),
                    "A source without machine-readable publication metadata.",
                    "Appendix A",
                    "Additional experiments should still be translated.",
                ]
            ),
        )
        self.assertEqual(
            [False, False, False],
            reference_section_flags(["Reference", "BPA", "3.4 Risk predictions"]),
        )

    def test_continues_reference_section_across_page_chunks(self):
        self.assertEqual(
            [True, True, False, False],
            reference_section_flags(
                [
                    "A source without machine-readable publication metadata.",
                    "Another weak reference entry.",
                    "Supplementary Material",
                    "Additional experiments should still be translated.",
                ],
                in_reference_section=True,
            ),
        )

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
