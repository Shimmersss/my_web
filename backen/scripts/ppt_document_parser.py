#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


def sanitize_name(name: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", name or "input")
    return safe


def sha1_file(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def try_docling_convert(input_path: Path) -> dict[str, Any] | None:
    try:
        from docling.document_converter import DocumentConverter
    except Exception:
        return None

    try:
        converter = DocumentConverter()
        result = converter.convert(str(input_path))
        markdown = result.document.export_to_markdown(compact_tables=True)
        json_data: dict[str, Any] = {}
        try:
            exported_json = result.document.export_to_json()
            if isinstance(exported_json, str):
                json_data = json.loads(exported_json)
            elif isinstance(exported_json, dict):
                json_data = exported_json
        except Exception:
            json_data = {}
        return {
            "source": "docling",
            "markdown": markdown or "",
            "json": json_data,
        }
    except Exception:
        return None


def try_markitdown_convert(input_path: Path) -> dict[str, Any] | None:
    try:
        from markitdown import MarkItDown
    except Exception:
        return None

    try:
        md = MarkItDown()
        result = md.convert(str(input_path))
        return {
            "source": "markitdown",
            "markdown": getattr(result, "markdown", "") or getattr(result, "text_content", ""),
            "json": {},
        }
    except Exception:
        return None


def copy_images_from_docx(path: Path, images_dir: Path, max_images: int = 40, max_bytes: int = 64 * 1024 * 1024) -> None:
    import zipfile

    images_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    total_bytes = 0
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            name = info.filename
            lower = name.lower()
            if not lower.startswith("word/media/"):
                continue
            if not (lower.endswith(".png") or lower.endswith(".jpg") or lower.endswith(".jpeg")):
                continue
            if copied >= max_images or total_bytes >= max_bytes:
                break
            if info.file_size < 0 or info.file_size > 8 * 1024 * 1024:
                continue
            if total_bytes + info.file_size > max_bytes:
                continue
            data = zf.read(info)
            out_name = sanitize_name(Path(name).name)
            (images_dir / out_name).write_bytes(data)
            copied += 1
            total_bytes += len(data)


def copy_media_from_office(path: Path, images_dir: Path, prefix: str,
                           max_images: int = 40, max_bytes: int = 64 * 1024 * 1024) -> None:
    """Copy embedded media from PPTX/XLSX without loading the whole archive."""
    import zipfile

    images_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    total_bytes = 0
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            lower = info.filename.lower()
            if not lower.startswith(prefix):
                continue
            if not lower.endswith((".png", ".jpg", ".jpeg")):
                continue
            if copied >= max_images or total_bytes >= max_bytes:
                break
            if info.file_size < 0 or info.file_size > 8 * 1024 * 1024:
                continue
            if total_bytes + info.file_size > max_bytes:
                continue
            data = zf.read(info)
            out_name = sanitize_name(Path(info.filename).name)
            (images_dir / f"{prefix.replace('/', '-')}{copied + 1}-{out_name}").write_bytes(data)
            copied += 1
            total_bytes += len(data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    input_path = Path(args.input).resolve()
    output_dir = Path(args.output).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    markdown = ""
    source = "fallback"
    json_data: dict[str, Any] = {}

    result = try_docling_convert(input_path)
    if result:
        source = result["source"]
        markdown = result["markdown"]
        json_data = result["json"]
    else:
        result = try_markitdown_convert(input_path)
        if result:
            source = result["source"]
            markdown = result["markdown"]
            json_data = result["json"]

    if not markdown:
        markdown = input_path.read_text(encoding="utf-8", errors="ignore") if input_path.suffix.lower() in {".md", ".txt"} else ""

    suffix = input_path.suffix.lower()
    if suffix == ".docx":
        copy_images_from_docx(input_path, output_dir / "images")
    elif suffix == ".pptx":
        copy_media_from_office(input_path, output_dir / "images", "ppt/media/")
    elif suffix == ".xlsx":
        copy_media_from_office(input_path, output_dir / "images", "xl/media/")
    manifest = {
        "source": source,
        "fileName": input_path.name,
        "fileSha1": sha1_file(input_path),
        "markdown": markdown,
        "json": json_data,
    }
    (output_dir / "parse-result.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"ok": True, "source": source, "out": str(output_dir / "parse-result.json")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
