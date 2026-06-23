#!/usr/bin/env python3
import argparse
import asyncio
import copy
import json
import os
import re
import statistics

from babeldoc.docvision.doclayout import DocLayoutModel
from babeldoc.format.pdf import high_level
from babeldoc.format.pdf.document_il import Box
from babeldoc.format.pdf.document_il import PdfParagraph
from babeldoc.format.pdf.document_il.midend.paragraph_finder import ParagraphFinder
from babeldoc.format.pdf.document_il.midend.paragraph_finder import generate_base58_id
from babeldoc.format.pdf.document_il.midend.typesetting import Typesetting
from babeldoc.format.pdf.translation_config import TranslationConfig
from babeldoc.format.pdf.translation_config import WatermarkOutputMode
from babeldoc.translator.translator import OpenAITranslator
from babeldoc.translator.translator import set_translate_rate_limiter
from reference_layout import is_numbered_reference
from reference_layout import reference_split_points


COMMON_ENGLISH_WORDS = {
    "the",
    "and",
    "for",
    "with",
    "that",
    "this",
    "from",
    "between",
    "system",
    "systems",
    "model",
    "models",
    "data",
    "method",
    "methods",
    "results",
    "study",
    "studies",
    "reaction",
    "reactions",
    "chemical",
    "using",
    "used",
    "were",
    "was",
    "are",
    "is",
    "as",
    "in",
    "of",
    "to",
    "by",
    "on",
    "an",
    "be",
    "we",
    "it",
    "can",
}


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def line_text(composition):
    line = getattr(composition, "pdf_line", None)
    if not line:
        return ""
    return "".join(char.char_unicode or "" for char in line.pdf_character).strip()


def split_merged_reference_paragraphs(paragraph_finder, paragraphs):
    """Split BabelDOC paragraphs when source reference entries have been merged."""
    index = 0
    while index < len(paragraphs):
        paragraph = paragraphs[index]
        compositions = paragraph.pdf_paragraph_composition or []
        split_points = reference_split_points(
            [line_text(composition) for composition in compositions]
        )
        if not split_points:
            index += 1
            continue

        if split_points[0] != 0:
            split_points.insert(0, 0)
        split_points.append(len(compositions))
        replacement = []
        for start, end in zip(split_points, split_points[1:]):
            if start == end:
                continue
            item = PdfParagraph(
                box=Box(0, 0, 0, 0),
                pdf_paragraph_composition=compositions[start:end],
                unicode="",
                debug_id=generate_base58_id(),
                layout_label=paragraph.layout_label,
                layout_id=paragraph.layout_id,
            )
            paragraph_finder.update_paragraph_data(item)
            replacement.append(item)
        paragraphs[index : index + 1] = replacement
        index += len(replacement)


def install_reference_layout_patches():
    """Add reference splitting and a two-CJK-character hanging indent to BabelDOC."""
    if getattr(ParagraphFinder, "_web_reference_layout_patch", False):
        return

    original_process = ParagraphFinder.process_independent_paragraphs
    original_layout = Typesetting._layout_typesetting_units

    def process_independent_paragraphs(self, paragraphs, median_width):
        split_merged_reference_paragraphs(self, paragraphs)
        return original_process(self, paragraphs, median_width)

    def layout_typesetting_units(
        self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break=True
    ):
        if not is_numbered_reference(paragraph.unicode or "") or not typesetting_units:
            return original_layout(
                self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break
            )

        font_sizes = [unit.font_size for unit in typesetting_units if unit.font_size]
        if not font_sizes:
            return original_layout(
                self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break
            )
        hanging_width = statistics.mode(font_sizes) * scale * 2
        if box.x + hanging_width >= box.x2:
            return original_layout(
                self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break
            )

        hanging_box = copy.deepcopy(box)
        hanging_box.x += hanging_width
        laid_out, all_fit = original_layout(
            self,
            typesetting_units,
            hanging_box,
            scale,
            line_skip,
            paragraph,
            use_english_line_break,
        )
        if not laid_out:
            return laid_out, all_fit

        first_line_y = max(unit.box.y for unit in laid_out)
        laid_out = [
            unit.relocate(unit.box.x - hanging_width, unit.box.y, 1)
            if abs(unit.box.y - first_line_y) < 0.1
            else unit
            for unit in laid_out
        ]
        return laid_out, all_fit

    ParagraphFinder.process_independent_paragraphs = process_independent_paragraphs
    Typesetting._layout_typesetting_units = layout_typesetting_units
    ParagraphFinder._web_reference_layout_patch = True


def repair_pdf_font_text(text):
    if not text:
        return text
    if len(re.findall(r"[A-Za-z]", text)) < 20:
        return text

    candidate = "".join(repair_pdf_font_char(char) for char in text)
    original_score = english_score(text)
    candidate_score = english_score(candidate)
    if (
        candidate_score["common_ratio"] >= 0.06
        and candidate_score["vowel_ratio"] > original_score["vowel_ratio"] + 0.08
        and candidate_score["score"] > original_score["score"] + 0.18
    ):
        return candidate
    return text


def repair_pdf_font_char(char):
    if "a" <= char <= "y":
        return chr(ord(char) + 1)
    if "A" <= char <= "V":
        return chr(ord(char) + 1)
    if char in {"W", "X", "Z"}:
        return "a"
    if "0" <= char <= "8":
        return chr(ord(char) + 1)
    return {
        "/": "0",
        "+": ",",
        "-": ".",
        ":": "A",
        ";": "A",
    }.get(char, char)


def english_score(text):
    letters = re.findall(r"[A-Za-z]", text)
    if not letters:
        return {"score": 0.0, "vowel_ratio": 0.0, "common_ratio": 0.0}
    vowels = sum(1 for char in letters if char.lower() in "aeiou")
    words = [word for word in re.split(r"[^A-Za-z]+", text.lower()) if len(word) >= 2]
    common = sum(1 for word in words if word in COMMON_ENGLISH_WORDS)
    vowel_ratio = vowels / len(letters)
    common_ratio = common / len(words) if words else 0.0
    return {
        "score": common_ratio * 3 + min(vowel_ratio, 0.45),
        "vowel_ratio": vowel_ratio,
        "common_ratio": common_ratio,
    }


class RepairingOpenAITranslator(OpenAITranslator):
    name = "openai-fontfix"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.add_cache_impact_parameters("pdf_font_text_repair", "shift-v1")

    def do_translate(self, text, rate_limit_params=None):
        return super().do_translate(repair_pdf_font_text(text), rate_limit_params)

    def do_llm_translate(self, text, rate_limit_params=None):
        return super().do_llm_translate(repair_pdf_font_text(text), rate_limit_params)


async def run(args):
    install_reference_layout_patches()
    high_level.init()
    translator = RepairingOpenAITranslator(
        lang_in="en",
        lang_out="zh",
        model=args.model,
        base_url=args.base_url,
        api_key=args.api_key,
    )
    set_translate_rate_limiter(args.qps)
    doc_layout_model = DocLayoutModel.load_onnx()
    config = TranslationConfig(
        translator=translator,
        input_file=args.input,
        lang_in="en",
        lang_out="zh",
        doc_layout_model=doc_layout_model,
        pages=args.pages,
        output_dir=args.output,
        qps=args.qps,
        pool_max_workers=args.qps,
        term_pool_max_workers=args.qps,
        auto_extract_glossary=False,
        primary_font_family=None if args.font_family == "auto" else args.font_family,
        only_include_translated_page=True,
        watermark_output_mode=WatermarkOutputMode.NoWatermark,
        report_interval=0.2,
    )
    getattr(doc_layout_model, "init_font_mapper", lambda _config: None)(config)
    async for event in high_level.async_translate(config):
        event_type = event.get("type")
        if event_type in {"progress_start", "progress_update", "progress_end"}:
            emit(
                {
                    "type": "progress",
                    "stage": event.get("stage", ""),
                    "overallProgress": round(float(event.get("overall_progress", 0)), 1),
                    "stageCurrent": event.get("stage_current", 0),
                    "stageTotal": event.get("stage_total", 0),
                }
            )
        elif event_type == "error":
            emit({"type": "error", "message": str(event.get("error", "BabelDOC 翻译失败"))})
            raise RuntimeError(event.get("error", "BabelDOC 翻译失败"))
        elif event_type == "finish":
            emit({"type": "finish"})
            break


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--pages", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--qps", type=int, required=True)
    parser.add_argument("--font-family", default="auto")
    args = parser.parse_args()
    args.api_key = os.environ["BABELDOC_OPENAI_API_KEY"]
    asyncio.run(run(args))
    # ONNX/CoreML may retain native worker threads after BabelDOC finishes.
    # The PDFs and the final JSON event are already flushed at this point.
    os._exit(0)


if __name__ == "__main__":
    main()
