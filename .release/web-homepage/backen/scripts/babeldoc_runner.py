#!/usr/bin/env python3
import argparse
import asyncio
import json
import os

from babeldoc.docvision.doclayout import DocLayoutModel
from babeldoc.format.pdf import high_level
from babeldoc.format.pdf.translation_config import TranslationConfig
from babeldoc.format.pdf.translation_config import WatermarkOutputMode
from babeldoc.translator.translator import OpenAITranslator
from babeldoc.translator.translator import set_translate_rate_limiter


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


async def run(args):
    high_level.init()
    translator = OpenAITranslator(
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
