import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import JSZip from 'jszip';
import automizerPackage from 'pptx-automizer';
import { imageSize } from 'image-size';
import { isTemplatePlaceholder } from './plan-utils.mjs';

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
  const raw = lines * charsPerLine;
  // Large inherited headings need more breathing room than the mathematical
  // glyph count suggests, especially for mixed CJK/Latin text in LibreOffice.
  const adjusted = shape.maxFontPt >= 28 ? Math.floor(raw * 0.72) : raw;
  return Math.max(4, Math.min(320, adjusted));
}

function textRole(shape, index) {
  const nameHint = String(shape.name || '').toLowerCase();
  const sample = String(shape.text || '').replace(/\s+/g, ' ').trim();
  const hint = `${nameHint} ${sample}`.toLowerCase();
  // Instructional body placeholders often contain the word “标题” inside a
  // sentence such as “建议与标题相关”. Treating that substring as a title
  // made every paragraph/card body receive a short repeated heading.
  if (/详细文本描述|字数控制|语言描述|正文|body|content text/i.test(sample)) return 'body';
  if (/footer|页脚|date|日期/.test(hint)) return 'footer';
  if (/sub|副标题|subtitle/.test(nameHint) || (index === 1 && shape.maxFontPt >= 16)) return 'headline';
  if (/title|标题|题目/.test(nameHint)
    || /^(?:单击此处添加标题文本|click here to add title text|添加标题(?:文本)?)$/i.test(sample)
    || shape.maxFontPt >= 28) return 'title';
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

export function imageFillability(shape, slideSize = {}, textShapes = []) {
  const hint = `${shape.name} ${shape.descr}`.toLowerCase();
  if (/logo|icon|avatar|badge|watermark|qr|二维码|校标|徽标|图标|页脚|装饰/.test(hint)) {
    return { fillable: false, fillableReason: 'decorative-or-brand-element' };
  }
  const slideWidth = Number(slideSize.width || 12_192_000);
  const slideHeight = Number(slideSize.height || 6_858_000);
  const slideArea = slideWidth * slideHeight;
  const areaRatio = shape.width * shape.height / slideArea;
  const reachesSlideEdges = shape.x <= 120_000 && shape.y <= 120_000
    && shape.x + shape.width >= slideWidth - 120_000
    && shape.y + shape.height >= slideHeight - 120_000;
  const outsideSlide = shape.x < 0 || shape.y < 0
    || shape.x + shape.width > slideWidth
    || shape.y + shape.height > slideHeight;
  if (areaRatio >= 0.55 || reachesSlideEdges || outsideSlide) {
    return { fillable: false, fillableReason: 'background-or-outside-slide-picture' };
  }
  if (areaRatio < 0.06 || shape.width < 1_800_000 || shape.height < 1_200_000) {
    return { fillable: false, fillableReason: 'too-small-for-content-image' };
  }
  const overlapsVisibleText = textShapes.some(text => {
    if (text.furniture || !text.width || !text.height) return false;
    const overlapWidth = Math.max(0,
      Math.min(shape.x + shape.width, text.x + text.width) - Math.max(shape.x, text.x));
    const overlapHeight = Math.max(0,
      Math.min(shape.y + shape.height, text.y + text.height) - Math.max(shape.y, text.y));
    return overlapWidth * overlapHeight / (text.width * text.height) > 0.1;
  });
  if (overlapsVisibleText) {
    return { fillable: false, fillableReason: 'overlaps-visible-text' };
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
  const presentationXml = await zip.file('ppt/presentation.xml').async('string');
  const slideSize = {
    width: Number(presentationXml.match(/<p:sldSz\b[^>]*\bcx="(\d+)"/)?.[1] || 12_192_000),
    height: Number(presentationXml.match(/<p:sldSz\b[^>]*\bcy="(\d+)"/)?.[1] || 6_858_000)
  };
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
      ...imageFillability(shape, slideSize, shapes)
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

function setFontFace(fontFace = 'Microsoft YaHei') {
  const value = String(fontFace || 'Microsoft YaHei').trim().slice(0, 120) || 'Microsoft YaHei';
  return element => {
    for (const tagName of ['a:defRPr', 'a:rPr', 'a:latin', 'a:ea', 'a:cs']) {
      for (const node of element.getElementsByTagName(tagName)) {
        node.setAttribute('typeface', value);
      }
    }
  };
}

function setRelationTargetContain(filename, imagePath, slot, presentation) {
  let dimensions = null;
  try {
    dimensions = imageSize(fsSync.readFileSync(imagePath));
  } catch {
    // A malformed or unsupported image still uses Automizer's normal cover
    // replacement and will be caught by real rendering and visual QA.
  }
  if (!dimensions?.width || !dimensions?.height || !slot?.width || !slot?.height) {
    return [ModifyImageHelper.setRelationTargetCover(filename, presentation)];
  }
  const imageRatio = dimensions.width / dimensions.height;
  const slotRatio = slot.width / slot.height;
  const width = imageRatio >= slotRatio ? slot.width : Math.round(slot.height * imageRatio);
  const height = imageRatio >= slotRatio ? Math.round(slot.width / imageRatio) : slot.height;
  const x = Math.round(slot.x + (slot.width - width) / 2);
  const y = Math.round(slot.y + (slot.height - height) / 2);
  return [
    ModifyImageHelper.setRelationTarget(filename),
    element => {
      const srcRect = element.getElementsByTagName('a:srcRect')[0];
      if (srcRect) {
        for (const attr of ['l', 't', 'r', 'b']) srcRect.setAttribute(attr, '0');
      }
      const transform = element.getElementsByTagName('a:xfrm')[0];
      const offset = transform?.getElementsByTagName('a:off')[0];
      const extent = transform?.getElementsByTagName('a:ext')[0];
      if (offset && extent) {
        offset.setAttribute('x', String(x));
        offset.setAttribute('y', String(y));
        extent.setAttribute('cx', String(width));
        extent.setAttribute('cy', String(height));
      }
    }
  ];
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

function elementSpans(xml, elementNames = ['grpSp', 'pic', 'sp']) {
  const spans = [];
  const stack = [];
  const token = /<\/?p:(grpSp|pic|sp)(?:\s[^>]*)?>/g;
  for (const match of String(xml).matchAll(token)) {
    const tag = match[1];
    const source = match[0];
    if (!elementNames.includes(tag)) continue;
    if (source.startsWith('</')) {
      const index = stack.map(item => item.tag).lastIndexOf(tag);
      if (index < 0) continue;
      const item = stack.splice(index, 1)[0];
      spans.push({ ...item, end: match.index + source.length });
    } else if (!source.endsWith('/>')) {
      stack.push({ tag, start: match.index, depth: stack.length });
    }
  }
  return spans.sort((left, right) => left.start - right.start);
}

function relationshipTargets(xml) {
  return new Map([...String(xml).matchAll(/<Relationship\b[^>]*\/>/g)].map(match => [
    xmlAttribute(match[0], 'Id'),
    xmlAttribute(match[0], 'Target')
  ]));
}

function embeddedRelationshipIds(element) {
  return [...String(element).matchAll(/\br:embed=(['"])(.*?)\1/g)].map(match => match[2]);
}

function pictureKey(element, indexedNames) {
  const tag = String(element).match(/<p:cNvPr\b[^>]*>/)?.[0] || '';
  const id = xmlAttribute(tag, 'id');
  const name = decodeXml(xmlAttribute(tag, 'name'));
  if (!id || !name) return '';
  const nameIdx = indexedNames.get(`${id}\u0000${name}`) ?? 0;
  return `${name}\u0000${nameIdx}`;
}

function isUserImageTarget(target, sourceImageNames) {
  const normalized = path.posix.basename(String(target || '').replaceAll('\\\\', '/'));
  return sourceImageNames.has(normalized);
}

function isExplicitImagePlaceholder(element) {
  const metadata = String(element).match(/<p:cNvPr\b[^>]*\b(?:name|descr)="([^"]*)"/g)?.join(' ') || '';
  return /placeholder|sample|示例图片|图片框|请上传|待补充|替换图片|image frame/i.test(metadata);
}

function stripStaticPictures(xml, relsXml, sourceImageNames, removablePictureKeys = new Set()) {
  const targets = relationshipTargets(relsXml);
  const indexedNames = elementNameIndexes(xml);
  const spans = elementSpans(xml, ['pic']).sort((left, right) => right.start - left.start);
  let output = String(xml);
  for (const span of spans) {
    const element = output.slice(span.start, span.end);
    const keep = embeddedRelationshipIds(element).some(id => isUserImageTarget(targets.get(id), sourceImageNames));
    const isStaticContentPicture = removablePictureKeys.has(pictureKey(element, indexedNames));
    if (!keep && (isExplicitImagePlaceholder(element) || isStaticContentPicture)) {
      output = `${output.slice(0, span.start)}${output.slice(span.end)}`;
    }
  }
  return output;
}

/** Remove source-deck content photos unless the Agent explicitly replaced them. */
export function stripStaticTemplateArtwork(xml, relsXml, sourceImageNames = [], removablePictureKeys = new Set()) {
  const names = new Set((sourceImageNames || []).map(name =>
    path.posix.basename(String(name).replaceAll('\\\\', '/'))));
  return stripStaticPictures(String(xml), String(relsXml), names, removablePictureKeys);
}

/** Remove wide, empty callout bars accidentally inherited by closing pages. */
export function stripEmptyClosingPlaceholderGroups(xml) {
  let output = String(xml);
  const spans = elementSpans(output, ['grpSp'])
    .filter(span => span.depth === 0)
    .sort((left, right) => right.start - left.start);
  for (const span of spans) {
    const element = output.slice(span.start, span.end);
    if (/<a:t>[\s\S]*?\S[\s\S]*?<\/a:t>/.test(element) || /<p:pic\b/.test(element)) continue;
    const groupProperties = element.match(/<p:grpSpPr\b[\s\S]*?<\/p:grpSpPr>/)?.[0] || '';
    const offsetTag = groupProperties.match(/<a:off\b[^>]*>/)?.[0] || '';
    const extentTag = groupProperties.match(/<a:ext\b[^>]*>/)?.[0] || '';
    const width = Number(xmlAttribute(extentTag, 'cx'));
    const height = Number(xmlAttribute(extentTag, 'cy'));
    if (width > 0 && height >= 300000 && width / height >= 6) {
      output = `${output.slice(0, span.start)}${output.slice(span.end)}`;
    }
  }
  return output;
}

/** Remove source-template mascots/logos from delivery closing pages while
 * preserving any image explicitly supplied by the current task. */
export function stripNonUserClosingPictures(xml, relsXml, sourceImageNames = []) {
  const targets = relationshipTargets(relsXml);
  const names = new Set((sourceImageNames || []).map(name =>
    path.posix.basename(String(name).replaceAll('\\', '/'))));
  const spans = elementSpans(String(xml), ['pic']).sort((left, right) => right.start - left.start);
  let output = String(xml);
  for (const span of spans) {
    const element = output.slice(span.start, span.end);
    const keep = embeddedRelationshipIds(element).some(id => isUserImageTarget(targets.get(id), names));
    if (!keep) output = `${output.slice(0, span.start)}${output.slice(span.end)}`;
  }
  return output;
}

async function stripStaticTemplateArtworkFromPackage(outputFile, sourceImages = [], plan = null, manifest = null) {
  const zip = await JSZip.loadAsync(await fs.readFile(outputFile));
  const sourceImageNames = sourceImages.map(item => path.basename(item.path || item.fileName || ''));
  const orderedSlides = await presentationSlideParts(zip);
  for (const [outputIndex, name] of orderedSlides.entries()) {
    if (!/^ppt\/slides\/slide\d+\.xml$/.test(name)) continue;
    const relName = name.replace('ppt/slides/', 'ppt/slides/_rels/') + '.rels';
    const relFile = zip.file(relName);
    if (!relFile) continue;
    const slidePlan = plan?.slides?.[outputIndex];
    const sourceInfo = manifest?.slides?.find(item => Number(item.slide) === Number(slidePlan?.sourceSlide));
    const removablePictureKeys = new Set((sourceInfo?.imageSlots || [])
      .filter(item => item.fillable === true)
      .map(item => `${item.name}\u0000${item.nameIdx ?? 0}`));
    const [xml, relsXml] = await Promise.all([
      zip.file(name).async('string'),
      relFile.async('string')
    ]);
    let cleanedXml = stripStaticTemplateArtwork(xml, relsXml, sourceImageNames, removablePictureKeys);
    if (String(slidePlan?.type || '').toLowerCase() === 'closing') {
      cleanedXml = stripEmptyClosingPlaceholderGroups(cleanedXml);
      cleanedXml = stripNonUserClosingPictures(cleanedXml, relsXml, sourceImageNames);
    }
    zip.file(name, cleanedXml);
  }
  await fs.writeFile(outputFile, await zip.generateAsync({
    type: 'nodebuffer',
    compression: 'DEFLATE',
    compressionOptions: { level: 6 }
  }));
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

export async function composeTemplatePptx({ templateFile, outputFile, plan, manifest, sources = [], sourceImages = [], fontFamily = 'Microsoft YaHei' }) {
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
      for (const shape of sourceInfo.textShapes) {
        const value = edits.get(shape.slotId);
        const removableInheritedText = isTemplatePlaceholder(value || shape.text, shape.text);
        if (shape.furniture && !removableInheritedText) continue;
        if (!value) {
          slide.removeElement({ name: shape.name, nameIdx: shape.nameIdx });
          continue;
        }
        if (removableInheritedText) {
          slide.removeElement({ name: shape.name, nameIdx: shape.nameIdx });
          continue;
        }
        // Edit the inherited text body in place. This keeps the source deck's
        // geometry, paragraph styling, z-order, master and background intact;
        // the selectable face is applied to existing runs instead of adding a
        // new topmost text box that can make the page look unrelated to the template.
        slide.modifyElement({ name: shape.name, nameIdx: shape.nameIdx }, [
          modify.setText(value),
          setFontFace(fontFamily)
        ]);
      }
      for (const edit of slidePlan.imageEdits || []) {
        const slot = (sourceInfo.imageSlots || []).find(item => item.slotId === edit.slotId);
        const image = imageById.get(String(edit.imageId));
        if (slot?.fillable && image) {
          slide.modifyElement({ name: slot.name, nameIdx: slot.nameIdx }, [
            ...setRelationTargetContain(path.basename(image.path), image.path, slot, presentation)
          ]);
        }
      }
    });
  }

  await presentation.write(path.basename(outputFile));
  await stripStaticTemplateArtworkFromPackage(outputFile, sourceImages, plan, manifest);
  await sanitizeGeneratedPptx(outputFile);
  await attachSpeakerNotes(outputFile, plan, sources);
  return frameMap;
}
