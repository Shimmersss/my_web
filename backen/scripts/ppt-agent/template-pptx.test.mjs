import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import JSZip from 'jszip';
import { imageFillability, inspectTemplate, physicalSlideNumber, sanitizeGeneratedPptx } from './template-pptx.mjs';

test('full-slide and out-of-bounds pictures cannot become fillable content slots', () => {
  assert.equal(imageFillability({ name: 'Picture 3', descr: '', x: 0, y: 0, width: 15294988, height: 7967314 }).fillable, false);
  assert.equal(imageFillability({ name: 'photo', descr: '', x: 2500000, y: 1500000, width: 4000000, height: 3000000 }).fillable, true);
});

test('inspectTemplate follows presentation order and records duplicate-name selectors', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-inspect-'));
  const templateFile = path.join(directory, 'template.pptx');
  try {
    const zip = new JSZip();
    zip.file('ppt/presentation.xml',
      '<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId1"/></p:sldIdLst></p:presentation>');
    zip.file('ppt/_rels/presentation.xml.rels',
      '<Relationships><Relationship Id="rId1" Type="x/slide" Target="slides/slide1.xml"/><Relationship Id="rId2" Type="x/slide" Target="slides/slide2.xml"/></Relationships>');
    zip.file('ppt/slides/slide1.xml',
      '<p:sld xmlns:p="p" xmlns:a="a"><p:sp><p:nvSpPr><p:cNvPr id="2" name="Only"/></p:nvSpPr><p:spPr><a:xfrm><a:off x="1" y="1"/><a:ext cx="3000000" cy="1000000"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:rPr sz="1800"/><a:t>physical-one</a:t></a:r></a:p></p:txBody></p:sp></p:sld>');
    zip.file('ppt/slides/slide2.xml',
      '<p:sld xmlns:p="p" xmlns:a="a"><p:sp><p:nvSpPr><p:cNvPr id="2" name="Body"/></p:nvSpPr><p:spPr><a:xfrm><a:off x="1" y="1"/><a:ext cx="3000000" cy="1000000"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:rPr sz="1800"/><a:t>first</a:t></a:r></a:p></p:txBody></p:sp><p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/></p:nvSpPr><p:spPr><a:xfrm><a:off x="1" y="2"/><a:ext cx="3000000" cy="1000000"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:rPr sz="1800"/><a:t>second</a:t></a:r></a:p></p:txBody></p:sp><p:pic><p:nvPicPr><p:cNvPr id="4" name="Company Logo"/></p:nvPicPr><p:spPr><a:xfrm><a:off x="1" y="3"/><a:ext cx="4000000" cy="3000000"/></a:xfrm></p:spPr></p:pic></p:sld>');
    await fs.writeFile(templateFile, await zip.generateAsync({ type: 'nodebuffer' }));

    const manifest = await inspectTemplate(templateFile);
    assert.equal(manifest.slides[0].sourcePart, 'ppt/slides/slide2.xml');
    assert.equal(physicalSlideNumber(manifest.slides[0]), 2);
    assert.deepEqual(manifest.slides[0].textShapes.map(shape => shape.nameIdx), [0, 1]);
    assert.equal(manifest.slides[0].imageSlots[0].fillable, false);
    assert.equal(manifest.slides[1].sampleText, 'physical-one');
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('sanitizeGeneratedPptx removes source slide relationships and orphan rel parts', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-sanitize-'));
  const outputFile = path.join(directory, 'output.pptx');
  try {
    const zip = new JSZip();
    zip.file('ppt/presentation.xml',
      '<?xml version="1.0"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId31-created"/></p:sldIdLst></p:presentation>');
    zip.file('ppt/_rels/presentation.xml.rels',
      '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/><Relationship Id="rId31-created" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide21.xml"/><Relationship Id="rId32-created" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="slideLayouts/slideLayout12.xml"/></Relationships>');
    zip.file('ppt/slides/slide21.xml',
      '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:pic><a:blip xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" r:embed="rId4-created"/></p:pic></p:sld>');
    zip.file('ppt/slides/_rels/slide21.xml.rels',
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/missing-source.png"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout12.xml"/><Relationship Id="rId4-created" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/media23.png"/></Relationships>');
    zip.file('ppt/slides/_rels/slide1.xml.rels', '<Relationships/>');
    zip.file('ppt/slideLayouts/slideLayout12.xml', '<p:sldLayout/>');
    zip.file('ppt/media/media23.png', Buffer.from([0x89, 0x50, 0x4e, 0x47]));
    zip.file('[Content_Types].xml',
      '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/slides/slide21.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/></Types>');
    await fs.writeFile(outputFile, await zip.generateAsync({ type: 'nodebuffer' }));

    await sanitizeGeneratedPptx(outputFile);

    const cleaned = await JSZip.loadAsync(await fs.readFile(outputFile));
    const relationships = await cleaned.file('ppt/_rels/presentation.xml.rels').async('string');
    const contentTypes = await cleaned.file('[Content_Types].xml').async('string');
    assert.doesNotMatch(relationships, /slides\/slide1\.xml/);
    assert.match(relationships, /slides\/slide21\.xml/);
    assert.equal(cleaned.file('ppt/slides/_rels/slide1.xml.rels'), null);
    assert.ok(cleaned.file('ppt/slides/slide21.xml'));
    const slideRelationships = await cleaned.file('ppt/slides/_rels/slide21.xml.rels').async('string');
    assert.doesNotMatch(slideRelationships, /missing-source\.png/);
    assert.match(slideRelationships, /media23\.png/);
    assert.match(slideRelationships, /slideLayout12\.xml/);
    assert.doesNotMatch(contentTypes, /slides\/slide1\.xml/);
    assert.match(contentTypes, /slides\/slide21\.xml/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});
