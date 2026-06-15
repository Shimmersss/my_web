#!/usr/bin/env python3
import argparse
import asyncio
import json
import os
import re

from babeldoc.docvision.doclayout import DocLayoutModel
from babeldoc.format.pdf import high_level
from babeldoc.format.pdf.translation_config import TranslationConfig
from babeldoc.format.pdf.translation_config import WatermarkOutputMode
from babeldoc.translator.translator import OpenAITranslator
from babeldoc.translator.translator import set_translate_rate_limiter


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
