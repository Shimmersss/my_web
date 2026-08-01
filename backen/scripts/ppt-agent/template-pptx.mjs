import fs from 'node:fs/promises';
import path from 'node:path';
import JSZip from 'jszip';
import automizerPackage from 'pptx-automizer';

const { Automizer, ModifyImageHelper, modify } = automizerPackage;
const EMU_PER_INCH = 914400;

function decodeXml(value) {
  return String(value || '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'");
}

function textShapes(xml) {
  const shapes = [];
  for (const match of xml.matchAll(/<p:sp\b[\s\S]*?<\/p:sp>/g)) {
    const shape = match[0];
    const name = decodeXml(shape.match(/<p:cNvPr\b[^>]*\bname="([^"]*)"/)?.[1] || '');
    const id = shape.match(/<p:cNvPr\b[^>]*\bid="([^"]*)"/)?.[1] || '';
    const texts = [...shape.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g)].map(item => decodeXml(item[1])).join('');
    const sizes = [...shape.matchAll(/\bsz="(\d+)"/g)].map(item => Number(item[1]) / 100).filter(Number.isFinite);
    const offset = shape.match(/<a:off\b[^>]*\bx="(\d+)"[^>]*\by="(\d+)"/);
    const extent = shape.match(/<a:ext\b[^>]*\bcx="(\d+)"[^>]*\bcy="(\d+)"/);
    const runProperties = shape.match(/<a:(?:defRPr|rPr)\b[\s\S]*?(?:<\/a:(?:defRPr|rPr)>|\/>)/)?.[0] || '';
    const color = runProperties.match(/<a:srgbClr\b[^>]*\bval="([0-9A-Fa-f]{6})"/)?.[1]
      || shape.match(/<a:srgbClr\b[^>]*\bval="([0-9A-Fa-f]{6})"/)?.[1]
      || 'FFFFFF';
    const alignment = shape.match(/<a:pPr\b[^>]*\balgn="(ctr|l|r|just)"/)?.[1] || 'l';
    const bold = /\bb="1"/.test(runProperties);
    if (name && (texts || shape.includes('<p:ph'))) {
      shapes.push({
        id,
        name,
        text: texts,
        maxFontPt: sizes.length ? Math.max(...sizes) : 0,
        x: Number(offset?.[1] || 0),
        y: Number(offset?.[2] || 0),
        width: Number(extent?.[1] || 0),
        height: Number(extent?.[2] || 0),
        color: color.toUpperCase(),
        align: alignment === 'ctr' ? 'center' : alignment === 'r' ? 'right' : alignment === 'just' ? 'justify' : 'left',
        bold,
        placeholder: shape.includes('<p:ph')
      });
    }
  }
  return shapes;
}

function pictureShapes(xml) {
  const shapes = [];
  for (const match of xml.matchAll(/<p:pic\b[\s\S]*?<\/p:pic>/g)) {
    const picture = match[0];
    const name = decodeXml(picture.match(/<p:cNvPr\b[^>]*\bname="([^"]*)"/)?.[1] || '');
    const id = picture.match(/<p:cNvPr\b[^>]*\bid="([^"]*)"/)?.[1] || '';
    const descr = decodeXml(picture.match(/<p:cNvPr\b[^>]*\bdescr="([^"]*)"/)?.[1] || '');
    const offset = picture.match(/<a:off\b[^>]*\bx="(\d+)"[^>]*\by="(\d+)"/);
    const extent = picture.match(/<a:ext\b[^>]*\bcx="(\d+)"[^>]*\bcy="(\d+)"/);
    if (!name || !offset || !extent) continue;
    shapes.push({
      id,
      name,
      descr,
      x: Number(offset[1]),
      y: Number(offset[2]),
      width: Number(extent[1]),
      height: Number(extent[2])
    });
  }
  return shapes;
}

function textCapacity(shape) {
  const fontPt = Math.max(10, shape.maxFontPt || 18);
  const widthPt = shape.width / EMU_PER_INCH * 72;
  const heightPt = shape.height / EMU_PER_INCH * 72;
  const lines = Math.max(1, Math.floor(heightPt / (fontPt * 1.25)));
  const charsPerLine = Math.max(2, Math.floor(widthPt / (fontPt * 0.9)));
  return Math.max(4, Math.min(320, lines * charsPerLine));
}

function textRole(shape, index) {
  const hint = `${shape.name} ${shape.text}`.toLowerCase();
  if (/title|标题|题目/.test(hint) || shape.maxFontPt >= 28) return 'title';
  if (/sub|副标题|subtitle/.test(hint) || (index === 1 && shape.maxFontPt >= 16)) return 'headline';
  if (/footer|页脚|date|日期/.test(hint)) return 'footer';
  return 'body';
}

function addNameIndexes(shapes, indexedNames = new Map()) {
  const counts = new Map();
  return shapes.map(shape => {
    const fallback = counts.get(shape.name) || 0;
    const nameIdx = indexedNames.get(`${shape.id}\u0000${shape.name}`) ?? fallback;
    counts.set(shape.name, nameIdx + 1);
    return { ...shape, nameIdx };
  });
}

function elementNameIndexes(xml) {
  const counts = new Map();
  const indexes = new Map();
  for (const match of xml.matchAll(/<p:cNvPr\b[^>]*>/g)) {
    const tag = match[0];
    const id = xmlAttribute(tag, 'id');
    const name = decodeXml(xmlAttribute(tag, 'name'));
    if (!name) continue;
    const nameIdx = counts.get(name) || 0;
    counts.set(name, nameIdx + 1);
    indexes.set(`${id}\u0000${name}`, nameIdx);
  }
  return indexes;
}

export function imageFillability(shape) {
  const hint = `${shape.name} ${shape.descr}`.toLowerCase();
  if (/logo|icon|avatar|badge|watermark|qr|二维码|校标|徽标|图标|页脚|装饰/.test(hint)) {
    return { fillable: false, fillableReason: 'decorative-or-brand-element' };
  }
  const slideArea = 12_192_000 * 6_858_000;
  const areaRatio = shape.width * shape.height / slideArea;
  const reachesSlideEdges = shape.x <= 120_000 && shape.y <= 120_000
    && shape.x + shape.width >= 12_072_000
    && shape.y + shape.height >= 6_738_000;
  const outsideSlide = shape.x < 0 || shape.y < 0
    || shape.x + shape.width > 12_192_000
    || shape.y + shape.height > 6_858_000;
  if (areaRatio >= 0.55 || reachesSlideEdges || outsideSlide) {
    return { fillable: false, fillableReason: 'background-or-outside-slide-picture' };
  }
  if (areaRatio < 0.06 || shape.width < 1_800_000 || shape.height < 1_200_000) {
    return { fillable: false, fillableReason: 'too-small-for-content-image' };
  }
  return { fillable: true, fillableReason: 'content-sized-picture-frame' };
}

export async function presentationSlideParts(zip) {
  const presentationFile = zip.file('ppt/presentation.xml');
  const relationshipsFile = zip.file('ppt/_rels/presentation.xml.rels');
  if (!presentationFile || !relationshipsFile) throw new Error('PPTX 缺少 presentation 主部件');
  const [presentationXml, relationshipsXml] = await Promise.all([
    presentationFile.async('string'),
    relationshipsFile.async('string')
  ]);
  const relationshipTargets = new Map(
    [...relationshipsXml.matchAll(/<Relationship\b[^>]*\/>/g)]
      .filter(match => xmlAttribute(match[0], 'Type').endsWith('/slide'))
      .map(match => {
        const target = xmlAttribute(match[0], 'Target').replace(/^\/+/, '');
        return [
          xmlAttribute(match[0], 'Id'),
          path.posix.normalize(target.startsWith('ppt/') ? target : path.posix.join('ppt', target))
        ];
      })
  );
  const parts = [...presentationXml.matchAll(/<p:sldId\b[^>]*>/g)].map(match => {
    const relationshipId = xmlAttribute(match[0], 'r:id');
    const part = relationshipTargets.get(relationshipId);
    if (!part || !/^ppt\/slides\/slide\d+\.xml$/.test(part) || !zip.file(part)) {
      throw new Error(`PPTX 演示顺序包含无效幻灯片关系: ${relationshipId}`);
    }
    return part;
  });
  if (!parts.length || new Set(parts).size !== parts.length) throw new Error('PPTX 演示顺序为空或包含重复页');
  return parts;
}

export function physicalSlideNumber(sourceInfo) {
  const number = Number(sourceInfo?.sourcePart?.match(/slide(\d+)\.xml$/)?.[1]);
  if (!Number.isInteger(number) || number < 1) {
    throw new Error(`模板源页缺少有效 OOXML 部件映射: ${sourceInfo?.sourcePart || 'unknown'}`);
  }
  return number;
}

export async function inspectTemplate(templateFile) {
  const zip = await JSZip.loadAsync(await fs.readFile(templateFile));
  const slideNames = await presentationSlideParts(zip);
  const slides = [];
  for (const [index, name] of slideNames.entries()) {
    const xml = await zip.file(name).async('string');
    const indexedNames = elementNameIndexes(xml);
    const shapes = addNameIndexes(textShapes(xml), indexedNames).map((shape, shapeIndex) => ({
      ...shape,
      slotId: `s${index + 1}-t${shapeIndex + 1}`,
      roleHint: textRole(shape, shapeIndex),
      capacityChars: textCapacity(shape),
      furniture: isFurniture(shape)
    }));
    const images = addNameIndexes(pictureShapes(xml), indexedNames).map((shape, shapeIndex) => ({
      ...shape,
      slotId: `s${index + 1}-i${shapeIndex + 1}`,
      roleHint: shape.width * shape.height > 10_000_000_000_000 ? 'hero' : 'supporting',
      ...imageFillability(shape)
    }));
    slides.push({
      slide: index + 1,
      sourcePart: name,
      textShapes: shapes,
      imageSlots: images,
      sampleText: shapes.map(item => item.text).filter(Boolean).join(' | ').slice(0, 600),
      editableTextCount: shapes.length,
      editableImageCount: images.length
    });
  }
  return { slideCount: slides.length, slides };
}

function isFurniture(shape) {
  const value = shape.text.trim();
  return /^(?:\d{1,2}\s*[/|]\s*\d{1,2}|\d{1,2}|BJTU|北京交通大学)$/i.test(value)
    || (shape.maxFontPt > 0 && shape.maxFontPt < 9 && value.length < 18);
}

function generatedTextOptions(shape) {
  // Use the same LibreOffice-visible font on development and production.
  // macOS UI fonts such as PingFang can be listed by CoreText yet still lose
  // CJK glyphs in headless LibreOffice exports.
  const fontFace = process.env.PPT_AGENT_FONT_FACE || 'Noto Sans CJK SC';
  return {
    x: shape.x / EMU_PER_INCH,
    y: shape.y / EMU_PER_INCH,
    w: shape.width / EMU_PER_INCH,
    h: shape.height / EMU_PER_INCH,
    fontFace,
    lang: 'zh-CN',
    fontSize: Math.max(10, shape.maxFontPt || 18),
    color: shape.color,
    bold: shape.bold,
    align: shape.align,
    valign: 'mid',
    margin: 0,
    breakLine: false
  };
}

function xmlEscape(value) {
  return String(value || '').replace(/[<>&'"]/g, char => ({
    '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;'
  })[char]);
}

function nextRelationshipId(xml) {
  const values = [...String(xml).matchAll(/\bId="rId(\d+)"/g)].map(item => Number(item[1]));
  return `rId${(values.length ? Math.max(...values) : 0) + 1}`;
}

function xmlAttribute(tag, name) {
  const match = String(tag).match(new RegExp(`\\b${name}=(["'])(.*?)\\1`));
  return match?.[2] || '';
}

export async function sanitizeGeneratedPptx(outputFile) {
  const zip = await JSZip.loadAsync(await fs.readFile(outputFile));
  const presentationName = 'ppt/presentation.xml';
  const relationshipsName = 'ppt/_rels/presentation.xml.rels';
  const presentationFile = zip.file(presentationName);
  const relationshipsFile = zip.file(relationshipsName);
  if (!presentationFile || !relationshipsFile) throw new Error('PPTX 缺少 presentation 主部件');

  const presentationXml = await presentationFile.async('string');
  const activeRelationshipIds = new Set(
    [...presentationXml.matchAll(/<p:sldId\b[^>]*>/g)]
      .map(match => xmlAttribute(match[0], 'r:id'))
      .filter(Boolean)
  );
  const relationshipsXml = await relationshipsFile.async('string');
  const activeSlideParts = new Set();
  const cleanedRelationships = relationshipsXml.replace(/<Relationship\b[^>]*\/>/g, relationship => {
    const type = xmlAttribute(relationship, 'Type');
    if (!type.endsWith('/slide')) return relationship;
    const id = xmlAttribute(relationship, 'Id');
    if (!activeRelationshipIds.has(id)) return '';
    const target = xmlAttribute(relationship, 'Target').replace(/^\/+/, '');
    const part = path.posix.normalize(target.startsWith('ppt/') ? target : `ppt/${target}`);
    if (!/^ppt\/slides\/slide\d+\.xml$/.test(part)) {
      throw new Error(`PPTX 幻灯片关系目标无效: ${target}`);
    }
    activeSlideParts.add(part);
    return relationship;
  });
  if (activeSlideParts.size !== activeRelationshipIds.size) {
    throw new Error('PPTX 活动幻灯片关系不完整');
  }
  zip.file(relationshipsName, cleanedRelationships);

  for (const name of Object.keys(zip.files)) {
    if (/^ppt\/slides\/slide\d+\.xml$/.test(name) && !activeSlideParts.has(name)) {
      zip.remove(name);
      continue;
    }
    const relMatch = name.match(/^ppt\/slides\/_rels\/(slide\d+\.xml)\.rels$/);
    if (relMatch && !activeSlideParts.has(`ppt/slides/${relMatch[1]}`)) zip.remove(name);
  }

  for (const slidePart of activeSlideParts) {
    const slideXml = await zip.file(slidePart).async('string');
    const referencedRelationshipIds = new Set(
      [...slideXml.matchAll(/\br:(?:id|embed|link)=(["'])(.*?)\1/g)].map(match => match[2])
    );
    const slideFile = path.posix.basename(slidePart);
    const slideRelsName = `ppt/slides/_rels/${slideFile}.rels`;
    const slideRelsFile = zip.file(slideRelsName);
    if (!slideRelsFile) throw new Error(`PPTX 缺少幻灯片关系: ${slideRelsName}`);
    const slideRelsXml = await slideRelsFile.async('string');
    zip.file(slideRelsName, slideRelsXml.replace(/<Relationship\b[^>]*\/>/g, relationship => {
      const type = xmlAttribute(relationship, 'Type');
      const id = xmlAttribute(relationship, 'Id');
      // slideLayout is the only implicit structural relationship required by a slide.
      // Media, chart, hyperlink, and other content relationships must be referenced
      // from the slide XML; pptx-automizer can otherwise leave stale source rels.
      return type.endsWith('/slideLayout') || referencedRelationshipIds.has(id)
        ? relationship
        : '';
    }));
  }

  const contentTypesFile = zip.file('[Content_Types].xml');
  if (!contentTypesFile) throw new Error('PPTX 缺少 Content Types');
  const contentTypesXml = await contentTypesFile.async('string');
  zip.file('[Content_Types].xml', contentTypesXml.replace(/<Override\b[^>]*\/>/g, override => {
    const partName = xmlAttribute(override, 'PartName').replace(/^\/+/, '');
    return /^ppt\/slides\/slide\d+\.xml$/.test(partName) && !activeSlideParts.has(partName)
      ? ''
      : override;
  }));

  await fs.writeFile(outputFile, await zip.generateAsync({
    type: 'nodebuffer',
    compression: 'DEFLATE',
    compressionOptions: { level: 6 }
  }));
}

export async function attachSpeakerNotes(outputFile, plan, sources) {
  const zip = await JSZip.loadAsync(await fs.readFile(outputFile));
  for (const name of Object.keys(zip.files).filter(name => /^ppt\/notes(?:Masters|Slides)\//.test(name))) zip.remove(name);
  const sourceById = new Map(sources.map(item => [String(item.id), item]));
  const slideNames = await presentationSlideParts(zip);
  const presentationRelsName = 'ppt/_rels/presentation.xml.rels';
  const presentationRels = (await zip.file(presentationRelsName).async('string'))
    .replace(/<Relationship\b[^>]*Type="[^"]*\/notesMaster"[^>]*\/>/g, '');
  const notesMasterRid = nextRelationshipId(presentationRels);
  zip.file(presentationRelsName, presentationRels.replace('</Relationships>',
    `<Relationship Id="${notesMasterRid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="notesMasters/notesMaster1.xml"/></Relationships>`));
  const presentationXml = (await zip.file('ppt/presentation.xml').async('string'))
    .replace(/<p:notesMasterIdLst>[\s\S]*?<\/p:notesMasterIdLst>/g, '');
  zip.file('ppt/presentation.xml', presentationXml.replace('</p:presentation>',
    `<p:notesMasterIdLst><p:notesMasterId r:id="${notesMasterRid}"/></p:notesMasterIdLst></p:presentation>`));
  zip.file('ppt/notesMasters/notesMaster1.xml',
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:notesMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:notesStyle><a:lvl1pPr><a:defRPr sz="1200"/></a:lvl1pPr></p:notesStyle></p:notesMaster>`);
  zip.file('ppt/notesMasters/_rels/notesMaster1.xml.rels',
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>`);

  for (const [index, slideName] of slideNames.entries()) {
    const slideNumber = Number(slideName.match(/\d+/)?.[0]);
    const relName = `ppt/slides/_rels/slide${slideNumber}.xml.rels`;
    const relXml = (await zip.file(relName).async('string'))
      .replace(/<Relationship\b[^>]*Type="[^"]*\/notesSlide"[^>]*\/>/g, '');
    const notesRid = nextRelationshipId(relXml);
    zip.file(relName, relXml.replace('</Relationships>',
      `<Relationship Id="${notesRid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide" Target="../notesSlides/notesSlide${index + 1}.xml"/></Relationships>`));
    const slidePlan = plan.slides[index] || {};
    const notes = (slidePlan.sourceIds || []).map(id => {
      const item = sourceById.get(String(id));
      return item ? `${id} | ${item.title || ''} | ${item.url || ''}` : String(id);
    });
    const noteText = xmlEscape(`[Sources]\n${notes.join('\n')}`);
    zip.file(`ppt/notesSlides/notesSlide${index + 1}.xml`,
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr><p:sp><p:nvSpPr><p:cNvPr id="2" name="Notes Placeholder"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" dirty="0"/><a:t>${noteText}</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>`);
    zip.file(`ppt/notesSlides/_rels/notesSlide${index + 1}.xml.rels`,
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="../notesMasters/notesMaster1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="../slides/slide${slideNumber}.xml"/></Relationships>`);
  }
  let contentTypes = (await zip.file('[Content_Types].xml').async('string'))
    .replace(/<Override\b[^>]*ContentType="[^"]*\.notes(?:Master|Slide)\+xml"[^>]*\/>/g, '');
  const overrides = [
    '<Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>',
    ...slideNames.map((_, index) => `<Override PartName="/ppt/notesSlides/notesSlide${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"/>`)
  ].join('');
  contentTypes = contentTypes.replace('</Types>', `${overrides}</Types>`);
  zip.file('[Content_Types].xml', contentTypes);
  await fs.writeFile(outputFile, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE', compressionOptions: { level: 6 } }));
}

export async function composeTemplatePptx({ templateFile, outputFile, plan, manifest, sources = [], sourceImages = [] }) {
  const templateDir = path.dirname(templateFile);
  const outputDir = path.dirname(outputFile);
  const templateName = path.basename(templateFile);
  const automizer = new Automizer({
    templateDir,
    outputDir,
    autoImportSlideMasters: true,
    removeExistingSlides: true,
    cleanup: true,
    cleanupPlaceholders: true,
    mediaDir: sourceImages.length ? path.dirname(sourceImages[0].path) : undefined,
    compression: 6,
    verbosity: 0
  });
  let presentation = automizer.loadRoot(templateName).load(templateName, 'source');
  if (sourceImages.length) {
    presentation = presentation.loadMedia(sourceImages.map(item => path.basename(item.path)));
  }
  const imageById = new Map(sourceImages.map(item => [String(item.id), item]));
  const frameMap = [];

  for (const [outputIndex, slidePlan] of plan.slides.entries()) {
    const requested = Number(slidePlan.sourceSlide || outputIndex + 1);
    const sourceSlide = Math.max(1, Math.min(manifest.slideCount, requested));
    const sourceInfo = manifest.slides[sourceSlide - 1];
    const sourcePhysicalSlide = physicalSlideNumber(sourceInfo);
    frameMap.push({
      outputSlide: outputIndex + 1,
      sourceSlide,
      sourcePart: sourceInfo.sourcePart,
      narrativeRole: slidePlan.type || 'content',
      reuseMode: 'duplicate-slide',
      textSlots: (slidePlan.textEdits || []).map(item => item.slotId),
      imageSlots: (slidePlan.imageEdits || []).map(item => item.slotId)
    });
    presentation.addSlide('source', sourcePhysicalSlide, slide => {
      const edits = new Map((slidePlan.textEdits || []).map(item => [String(item.slotId), String(item.text || '')]));
      for (const shape of sourceInfo.textShapes.filter(shape => !isFurniture(shape))) {
        const value = edits.get(shape.slotId);
        if (!value) {
          slide.removeElement({ name: shape.name, nameIdx: shape.nameIdx });
          continue;
        }
        // Existing source decks commonly embed font subsets. Replacing their XML text
        // directly makes newly introduced CJK glyphs disappear in LibreOffice. Clear the
        // inherited text run, then write into the exact inherited frame with a portable
        // system font. Geometry, background, master, images, and z-order stay inherited.
        slide.modifyElement({ name: shape.name, nameIdx: shape.nameIdx }, [modify.setText('')]);
        slide.generate(pptxSlide => {
          pptxSlide.addText(value, generatedTextOptions(shape));
        }, `Agent text ${outputIndex + 1}-${shape.slotId}`);
      }
      for (const edit of slidePlan.imageEdits || []) {
        const slot = (sourceInfo.imageSlots || []).find(item => item.slotId === edit.slotId);
        const image = imageById.get(String(edit.imageId));
        if (slot?.fillable && image) {
          slide.modifyElement({ name: slot.name, nameIdx: slot.nameIdx }, [
            ModifyImageHelper.setRelationTargetCover(path.basename(image.path), presentation)
          ]);
        }
      }
    });
  }

  await presentation.write(path.basename(outputFile));
  await sanitizeGeneratedPptx(outputFile);
  await attachSpeakerNotes(outputFile, plan, sources);
  return frameMap;
}
