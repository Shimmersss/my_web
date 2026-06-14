"""apply: clone selected source slides into a new PPTX and write replacements.

Orchestrates the per-stage helpers (text / table / chart / transition / notes)
and rebuilds the presentation slide list, relationships, and content types.
"""

from __future__ import annotations

import re
import zipfile
from pathlib import Path
from typing import Any
from xml.etree import ElementTree as ET

from .chart_fill import (
    _apply_chart_edits_to_slide_package,
    _max_chart_part_number,
    _max_embedding_part_number,
)
from .clone import _make_part_allocator, deep_clone_slide_private_parts
from .notes import _find_notes_master_target, _slide_rels_with_notes
from .ooxml import NS, REL_NS, SLIDE_REL_TYPE, _parse_slide_refs, _qn, _xml_bytes
from .ooxml import (
    _chart_containers,
    _container_geometry,
    _picture_containers,
    _shape_identity,
    _table_containers,
    _text_containers,
)
from .package import (
    _add_notes_override,
    _add_slide_override,
    _content_type_root,
    _empty_relationships_root,
    _max_numeric_rid,
    _max_slide_id,
    _max_slide_part_number,
    _prune_unreferenced_parts,
)
from .table_fill import _apply_table_edits_to_slide
from .text_fill import _apply_replacements_to_slide, _set_container_text
from .transitions import (
    DEFAULT_TRANSITION,
    DEFAULT_TRANSITION_DURATION,
    _resolve_slide_transition,
    _set_slide_transition,
)


DECORATIVE_IMAGE_NAME_KEYWORDS = (
    "background",
    "logo",
    "icon",
    "badge",
    "decor",
    "decoration",
    "ornament",
    "shape",
    "line",
    "校徽",
    "徽标",
    "标志",
    "图标",
    "装饰",
    "背景",
)

DECORATIVE_IMAGE_NAME_TOKENS = {
    "background",
    "bg",
    "logo",
    "icon",
    "badge",
    "decor",
    "decoration",
    "ornament",
    "shape",
    "line",
    "lines",
    "separator",
    "divider",
    "frame",
    "border",
    "header",
    "footer",
    "school",
    "emblem",
}

DECORATIVE_IMAGE_CJK_TOKENS = (
    "校徽",
    "徽标",
    "标志",
    "图标",
    "装饰",
    "背景",
    "页眉",
    "页脚",
)


def _parent_map(root: ET.Element) -> dict[ET.Element, ET.Element]:
    return {child: parent for parent in root.iter() for child in list(parent)}


def _shape_slot_id(source_slide: int, container: ET.Element, order: int, prefix: str) -> str:
    shape_id, _shape_name = _shape_identity(container, order)
    return f"s{source_slide:02d}_{prefix}{shape_id}"


def _is_background_like(container: ET.Element) -> bool:
    geometry = _container_geometry(container)
    width = geometry.get("width") or 0
    height = geometry.get("height") or 0
    x = geometry.get("x") or 0
    y = geometry.get("y") or 0
    return x <= 90 and y <= 90 and width >= 900 and height >= 520


def _is_decorative_picture(container: ET.Element, order: int) -> bool:
    _shape_id, shape_name = _shape_identity(container, order)
    normalized = shape_name.lower()
    tokens = set(re.findall(r"[a-z]+|\d+|[\u4e00-\u9fff]+", normalized))
    if tokens.intersection(DECORATIVE_IMAGE_NAME_TOKENS):
        return True
    if any(token in normalized for token in DECORATIVE_IMAGE_CJK_TOKENS):
        return True
    return _is_background_like(container)


def _remove_container(root: ET.Element, container: ET.Element) -> None:
    parent = _parent_map(root).get(container)
    if parent is not None:
        parent.remove(container)


def _relationship_ids(container: ET.Element) -> set[str]:
    ids: set[str] = set()
    for attr_name in (
        _qn(NS["r"], "embed"),
        _qn(NS["r"], "link"),
        _qn(NS["r"], "id"),
    ):
        for node in container.iter():
            rel_id = node.attrib.get(attr_name)
            if rel_id:
                ids.add(rel_id)
    return ids


def _strip_source_content_from_slide(slide_root: ET.Element, source_slide: int, item: dict[str, Any]) -> set[str]:
    replacement_targets = {
        str(replacement.get("slot_id") or "")
        for replacement in item.get("replacements", []) or []
        if isinstance(replacement, dict)
    }
    table_targets = {
        str(edit.get("table_id") or "")
        for edit in item.get("table_edits", []) or []
        if isinstance(edit, dict)
    }
    chart_targets = {
        str(edit.get("chart_id") or "")
        for edit in item.get("chart_edits", []) or []
        if isinstance(edit, dict)
    }
    removed_rel_ids: set[str] = set()

    for order, container in enumerate(_text_containers(slide_root), start=1):
        slot_id = _shape_slot_id(source_slide, container, order, "sh")
        if slot_id not in replacement_targets:
            _set_container_text(container, "")

    for order, container in enumerate(list(_picture_containers(slide_root)), start=1):
        if _is_decorative_picture(container, order):
            continue
        removed_rel_ids.update(_relationship_ids(container))
        _remove_container(slide_root, container)

    for order, container in enumerate(list(_table_containers(slide_root)), start=1):
        table_id = _shape_slot_id(source_slide, container, order, "tbl")
        if table_id not in table_targets:
            _remove_container(slide_root, container)

    for order, container in enumerate(list(_chart_containers(slide_root)), start=1):
        chart_id = _shape_slot_id(source_slide, container, order, "ch")
        if chart_id not in chart_targets:
            removed_rel_ids.update(_relationship_ids(container))
            _remove_container(slide_root, container)
    return removed_rel_ids


def _strip_source_content_from_visual_part(root: ET.Element) -> set[str]:
    removed_rel_ids: set[str] = set()
    for container in _text_containers(root):
        _set_container_text(container, "")
    for order, container in enumerate(list(_picture_containers(root)), start=1):
        if _is_decorative_picture(container, order):
            continue
        removed_rel_ids.update(_relationship_ids(container))
        _remove_container(root, container)
    for container in list(_table_containers(root)):
        _remove_container(root, container)
    for container in list(_chart_containers(root)):
        removed_rel_ids.update(_relationship_ids(container))
        _remove_container(root, container)
    return removed_rel_ids


def _strip_source_content_from_shared_visual_parts(entries: dict[str, bytes]) -> None:
    for part_name in list(entries):
        if not (
            re.fullmatch(r"ppt/slideLayouts/slideLayout\d+\.xml", part_name)
            or re.fullmatch(r"ppt/slideMasters/slideMaster\d+\.xml", part_name)
        ):
            continue
        try:
            root = ET.fromstring(entries[part_name])
        except ET.ParseError:
            continue
        removed_rel_ids = _strip_source_content_from_visual_part(root)
        entries[part_name] = _xml_bytes(root)
        rels_name = f"{Path(part_name).parent}/_rels/{Path(part_name).name}.rels"
        if removed_rel_ids and rels_name in entries:
            rels_root = ET.fromstring(entries[rels_name])
            _remove_relationships(rels_root, removed_rel_ids)
            entries[rels_name] = _xml_bytes(rels_root)


def _remove_relationships(rels_root: ET.Element, rel_ids: set[str]) -> None:
    if not rel_ids:
        return
    for rel in list(rels_root.findall(_qn(REL_NS, "Relationship"))):
        if rel.attrib.get("Id") in rel_ids:
            rels_root.remove(rel)


def apply_plan(
    pptx_path: Path,
    plan: dict[str, Any],
    output_path: Path,
    *,
    transition: str | None = DEFAULT_TRANSITION,
    transition_duration: float = DEFAULT_TRANSITION_DURATION,
    strip_source_content: bool = False,
) -> None:
    """Create a filled PPTX by cloning selected source slides and replacing text."""
    plan_slides = plan.get("slides")
    if not isinstance(plan_slides, list) or not plan_slides:
        raise RuntimeError("Plan must contain a non-empty 'slides' list")

    with zipfile.ZipFile(pptx_path) as zf:
        entries = {info.filename: zf.read(info.filename) for info in zf.infolist() if not info.is_dir()}
        slide_refs = {slide.index: slide for slide in _parse_slide_refs(zf)}
    notes_master_target = _find_notes_master_target(entries)

    pres_root = ET.fromstring(entries["ppt/presentation.xml"])
    pres_rels_root = ET.fromstring(entries["ppt/_rels/presentation.xml.rels"])
    content_root = _content_type_root(ET.fromstring(entries["[Content_Types].xml"]))
    sld_id_lst = pres_root.find("p:sldIdLst", NS)
    if sld_id_lst is None:
        sld_id_lst = ET.SubElement(pres_root, _qn(NS["p"], "sldIdLst"))

    for child in list(sld_id_lst):
        sld_id_lst.remove(child)
    for rel in list(pres_rels_root.findall(_qn(REL_NS, "Relationship"))):
        if rel.attrib.get("Type") == SLIDE_REL_TYPE:
            pres_rels_root.remove(rel)

    next_slide_number = _max_slide_part_number(entries) + 1
    next_slide_id = _max_slide_id(sld_id_lst) + 1
    next_rel_number = _max_numeric_rid(pres_rels_root) + 1
    next_chart_number = _max_chart_part_number(entries)
    next_embedding_number = _max_embedding_part_number(entries)
    allocate_part = _make_part_allocator(entries)

    for offset, item in enumerate(plan_slides):
        source_slide = int(item.get("source_slide", 0))
        if source_slide not in slide_refs:
            raise RuntimeError(f"Plan references a missing source slide: {source_slide}")
        source_ref = slide_refs[source_slide]
        new_slide_number = next_slide_number + offset
        new_part = f"ppt/slides/slide{new_slide_number}.xml"
        new_rels = f"ppt/slides/_rels/slide{new_slide_number}.xml.rels"
        new_rid = f"rId{next_rel_number + offset}"

        slide_root = ET.fromstring(entries[source_ref.part_name])
        replacements = item.get("replacements", [])
        if not isinstance(replacements, list):
            raise RuntimeError(f"Slide {source_slide} replacements must be a list")
        removed_rel_ids: set[str] = set()
        if strip_source_content:
            removed_rel_ids = _strip_source_content_from_slide(slide_root, source_slide, item)
        _apply_replacements_to_slide(
            slide_root,
            source_slide=source_slide,
            replacements=replacements,
        )
        table_edits = item.get("table_edits", [])
        if not isinstance(table_edits, list):
            raise RuntimeError(f"Slide {source_slide} table_edits must be a list")
        _apply_table_edits_to_slide(
            slide_root,
            source_slide=source_slide,
            table_edits=table_edits,
        )
        slide_effect, slide_duration, slide_advance = _resolve_slide_transition(
            item,
            default_effect=transition,
            default_duration=transition_duration,
        )
        _set_slide_transition(
            slide_root,
            effect=slide_effect,
            duration=slide_duration,
            advance_after=slide_advance,
        )

        source_rels = entries.get(source_ref.rels_name)
        slide_rels_root = ET.fromstring(source_rels) if source_rels else _empty_relationships_root()
        _remove_relationships(slide_rels_root, removed_rel_ids)
        deep_clone_slide_private_parts(
            slide_rels_root,
            new_slide_part=new_part,
            entries=entries,
            content_root=content_root,
            allocate=allocate_part,
        )
        chart_edits = item.get("chart_edits", [])
        if not isinstance(chart_edits, list):
            raise RuntimeError(f"Slide {source_slide} chart_edits must be a list")
        next_chart_number, next_embedding_number = _apply_chart_edits_to_slide_package(
            slide_root,
            slide_rels_root,
            entries,
            content_root,
            source_slide=source_slide,
            new_slide_part=new_part,
            chart_edits=chart_edits,
            next_chart_number=next_chart_number,
            next_embedding_number=next_embedding_number,
        )
        entries[new_part] = _xml_bytes(slide_root)
        notes_text = str(item.get("notes") or item.get("speaker_notes") or "")
        entries[new_rels], note_entries = _slide_rels_with_notes(
            _xml_bytes(slide_rels_root),
            slide_number=new_slide_number,
            notes_text=notes_text,
            notes_master_target=notes_master_target,
        )
        entries.update(note_entries)
        _add_slide_override(content_root, new_part)
        if note_entries:
            _add_notes_override(content_root, f"ppt/notesSlides/notesSlide{new_slide_number}.xml")

        ET.SubElement(
            pres_rels_root,
            _qn(REL_NS, "Relationship"),
            {
                "Id": new_rid,
                "Type": SLIDE_REL_TYPE,
                "Target": f"slides/slide{new_slide_number}.xml",
            },
        )
        ET.SubElement(
            sld_id_lst,
            _qn(NS["p"], "sldId"),
            {"id": str(next_slide_id + offset), _qn(NS["r"], "id"): new_rid},
        )

    if strip_source_content:
        _strip_source_content_from_shared_visual_parts(entries)

    entries["ppt/presentation.xml"] = _xml_bytes(pres_root)
    entries["ppt/_rels/presentation.xml.rels"] = _xml_bytes(pres_rels_root)
    _prune_unreferenced_parts(entries, content_root)
    entries["[Content_Types].xml"] = _xml_bytes(content_root)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as out:
        for name, data in entries.items():
            out.writestr(name, data)
