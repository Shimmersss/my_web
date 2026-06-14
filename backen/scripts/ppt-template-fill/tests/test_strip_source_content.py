#!/usr/bin/env python3
from __future__ import annotations

import sys
import tempfile
import unittest
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from template_fill_pptx.applier import apply_plan  # noqa: E402
from template_fill_pptx.checker import check_plan  # noqa: E402
from template_fill_pptx.ooxml import REL_NS, _qn  # noqa: E402


P = "http://schemas.openxmlformats.org/presentationml/2006/main"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
REL = "http://schemas.openxmlformats.org/package/2006/relationships"
C = "http://schemas.openxmlformats.org/drawingml/2006/chart"


def _write_minimal_template(path: Path) -> None:
    slide = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="{P}" xmlns:a="{A}" xmlns:r="{R}" xmlns:c="{C}"><p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Old title</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Old body should clear</a:t></a:r></a:p></p:txBody></p:sp>
<p:pic><p:nvPicPr><p:cNvPr id="4" name="Online result screenshot"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdContent"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="1000000" y="1000000"/><a:ext cx="3000000" cy="2000000"/></a:xfrm></p:spPr></p:pic>
<p:pic><p:nvPicPr><p:cNvPr id="5" name="Logo"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdLogo"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="100000" y="100000"/><a:ext cx="300000" cy="300000"/></a:xfrm></p:spPr></p:pic>
<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="Old Chart"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="2000000" y="2000000"/><a:ext cx="3000000" cy="2000000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="rIdChart"/></a:graphicData></a:graphic></p:graphicFrame>
</p:spTree></p:cSld></p:sld>'''
    layout = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:p="{P}" xmlns:a="{A}" xmlns:r="{R}" xmlns:c="{C}"><p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Layout body"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Layout old body should clear</a:t></a:r></a:p></p:txBody></p:sp>
<p:pic><p:nvPicPr><p:cNvPr id="3" name="Layout content image"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdLayoutContent"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="1200000" y="1200000"/><a:ext cx="3000000" cy="2000000"/></a:xfrm></p:spPr></p:pic>
<p:pic><p:nvPicPr><p:cNvPr id="4" name="Layout Logo"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdLayoutLogo"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="100000" y="100000"/><a:ext cx="300000" cy="300000"/></a:xfrm></p:spPr></p:pic>
<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="5" name="Layout Chart"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="2000000" y="2000000"/><a:ext cx="3000000" cy="2000000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="rIdLayoutChart"/></a:graphicData></a:graphic></p:graphicFrame>
</p:spTree></p:cSld></p:sldLayout>'''
    layout_rels = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="{REL}">
<Relationship Id="rIdLayoutContent" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/layout-content.png"/>
<Relationship Id="rIdLayoutLogo" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/layout-logo.png"/>
<Relationship Id="rIdLayoutChart" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart2.xml"/>
</Relationships>'''
    rels = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="{REL}">
<Relationship Id="rIdLayout" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rIdContent" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/content.png"/>
<Relationship Id="rIdLogo" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/logo.png"/>
<Relationship Id="rIdChart" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart1.xml"/>
</Relationships>'''
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        archive.writestr(
            "[Content_Types].xml",
            '''<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>
<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
<Override PartName="/ppt/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>
<Override PartName="/ppt/charts/chart2.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>
</Types>''',
        )
        archive.writestr(
            "_rels/.rels",
            f'''<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>''',
        )
        archive.writestr(
            "ppt/presentation.xml",
            f'''<p:presentation xmlns:p="{P}" xmlns:r="{R}"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>''',
        )
        archive.writestr(
            "ppt/_rels/presentation.xml.rels",
            f'''<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>''',
        )
        archive.writestr("ppt/slides/slide1.xml", slide)
        archive.writestr("ppt/slides/_rels/slide1.xml.rels", rels)
        archive.writestr("ppt/slideLayouts/slideLayout1.xml", layout)
        archive.writestr("ppt/slideLayouts/_rels/slideLayout1.xml.rels", layout_rels)
        archive.writestr("ppt/charts/chart1.xml", f'''<c:chartSpace xmlns:c="{C}"/>''')
        archive.writestr("ppt/charts/chart2.xml", f'''<c:chartSpace xmlns:c="{C}"/>''')
        archive.writestr("ppt/media/content.png", b"content-image")
        archive.writestr("ppt/media/logo.png", b"logo-image")
        archive.writestr("ppt/media/layout-content.png", b"layout-content-image")
        archive.writestr("ppt/media/layout-logo.png", b"layout-logo-image")


class StripSourceContentTest(unittest.TestCase):
    def test_strip_removes_source_content_relationships_and_keeps_logo(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "template.pptx"
            output = root / "output.pptx"
            _write_minimal_template(source)

            apply_plan(
                source,
                {
                    "slides": [
                        {
                            "source_slide": 1,
                            "replacements": [{"slot_id": "s01_sh2", "text": "New title"}],
                            "table_edits": [],
                            "chart_edits": [],
                        }
                    ]
                },
                output,
                transition="keep",
                strip_source_content=True,
            )

            with ZipFile(output) as archive:
                names = set(archive.namelist())
                slide_name = next(name for name in names if name.startswith("ppt/slides/slide") and name.endswith(".xml"))
                rels_name = f"ppt/slides/_rels/{Path(slide_name).name}.rels"
                slide_xml = archive.read(slide_name).decode("utf-8")
                rels_root = ET.fromstring(archive.read(rels_name))
                rel_ids = {rel.attrib.get("Id") for rel in rels_root.findall(_qn(REL_NS, "Relationship"))}
                layout_name = next(name for name in names if name.startswith("ppt/slideLayouts/slideLayout"))
                layout_xml = archive.read(layout_name).decode("utf-8")
                layout_rels_name = f"ppt/slideLayouts/_rels/{Path(layout_name).name}.rels"
                layout_rels_root = ET.fromstring(archive.read(layout_rels_name))
                layout_rel_ids = {
                    rel.attrib.get("Id")
                    for rel in layout_rels_root.findall(_qn(REL_NS, "Relationship"))
                }

            self.assertIn("New title", slide_xml)
            self.assertNotIn("Old body should clear", slide_xml)
            self.assertNotIn("rIdContent", rel_ids)
            self.assertNotIn("rIdChart", rel_ids)
            self.assertIn("rIdLogo", rel_ids)
            self.assertNotIn("ppt/media/content.png", names)
            self.assertNotIn("ppt/charts/chart1.xml", names)
            self.assertIn("ppt/media/logo.png", names)
            self.assertNotIn("ppt/media/layout-content.png", names)
            self.assertNotIn("ppt/charts/chart2.xml", names)
            self.assertIn("ppt/media/layout-logo.png", names)
            self.assertNotIn("Layout old body should clear", layout_xml)
            self.assertNotIn("rIdLayoutContent", layout_rel_ids)
            self.assertNotIn("rIdLayoutChart", layout_rel_ids)
            self.assertIn("rIdLayoutLogo", layout_rel_ids)

    def test_check_report_excludes_old_text_and_rejects_unfillable_image_region(self) -> None:
        library = {
            "slides": [
                {
                    "slide_index": 1,
                    "slots": [
                        {
                            "slot_id": "s01_sh2",
                            "shape_id": "2",
                            "role": "body_candidate",
                            "text": "SECRET TEMPLATE BODY",
                            "paragraph_count": 1,
                            "geometry": {"width": 400, "height": 120},
                            "text_metrics": {"font_size_px": 16},
                        }
                    ],
                    "image_regions": [
                        {
                            "region_id": "s01_img5",
                            "role": "logo",
                            "fillable": False,
                            "rejectReason": "decorative",
                        }
                    ],
                }
            ]
        }
        plan = {
            "slides": [
                {
                    "source_slide": 1,
                    "replacements": [{"slot_id": "s01_sh2", "text": "New concise body"}],
                    "image_edits": [{"region_id": "s01_img5", "image_id": "paper-image-1"}],
                }
            ]
        }

        report = check_plan(library, plan)
        report_json = json.dumps(report, ensure_ascii=False)

        self.assertNotIn("SECRET TEMPLATE BODY", report_json)
        self.assertEqual(1, report["summary"]["error"])
        self.assertIn("image region is not fillable", report_json)


if __name__ == "__main__":
    unittest.main()
