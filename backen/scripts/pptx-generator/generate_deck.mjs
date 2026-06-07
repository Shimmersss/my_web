import pptxgen from "pptxgenjs";
import JSZip from "jszip";
import { imageSize } from "image-size";
import { readFile, access, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

const args = parseArgs(process.argv.slice(2));
if (!args.deck || !args.style || !args.images || !args.out) {
  throw new Error("Usage: node generate_deck.mjs --deck deck.json --style style.json --images images.json --manifest image-manifest.json --out output.pptx");
}

const deck = JSON.parse(await readFile(args.deck, "utf8"));
const style = JSON.parse(await readFile(args.style, "utf8"));
const images = JSON.parse(await readFile(args.images, "utf8"));
const manifest = args.manifest ? await readJson(args.manifest, { images: [] }) : { images: [] };
await mkdir(path.dirname(args.out), { recursive: true });

if (args.mode === "template-fill") {
  if (!args.template) throw new Error("template-fill mode requires --template");
  await renderTemplateFill(args.template, args.out, deck);
  console.log(JSON.stringify({ ok: true, out: args.out, mode: "template-fill", slides: Array.isArray(deck.slides) ? deck.slides.length : 0 }));
  process.exit(0);
}

const pptx = new pptxgen();
pptx.defineLayout({ name: "WIDE", width: 13.333, height: 7.5 });
pptx.layout = "WIDE";
pptx.author = "Codex";
pptx.company = "Shimmer";
pptx.subject = deck.subtitle || deck.audience || "";
pptx.title = deck.title || "AI 生成 PPT";
pptx.lang = "zh-CN";
pptx.theme = {
  headFontFace: "Microsoft YaHei",
  bodyFontFace: "Microsoft YaHei",
  lang: "zh-CN",
};
pptx.layout = "WIDE";

const W = 13.333;
const H = 7.5;
const font = "Microsoft YaHei";
const palette = normalizePalette(style.palette);
const C = {
  blue: palette[0] || "005BAC",
  deep: palette[1] || "063A78",
  gold: palette[2] || "D9A441",
  pale: palette[3] || "EFF6FF",
  ink: palette[4] || "1F2937",
  gray: "64748B",
  line: "C9D7E8",
  white: "FFFFFF",
  green: "2F8F5B",
  red: "B42318",
  purple: "7C5FB1",
  sky: "DCEEFF",
};

const templateAssets = await listUsableAssets(style.assetsDir);
const templateFramework = Array.isArray(style.templateFramework) ? style.templateFramework : [];
const mediaCandidates = [...templateAssets, ...images].filter(Boolean);
const imageCache = new Map();
for (const imagePath of mediaCandidates) {
  const image = await loadImageData(imagePath);
  if (image) imageCache.set(imagePath, image);
}
const media = [...imageCache.keys()];
const paperMedia = images.filter((imagePath) => imageCache.has(imagePath));
const imageById = buildImageIndex(paperMedia, manifest);
const useTemplateFramework = Boolean(style.frameworkMode) && (templateFramework.length > 0 || templateAssets.length > 0);
const slides = Array.isArray(deck.slides) ? deck.slides : [];
if (!slides.length) throw new Error("deck.slides is empty");
if (slides.length > 40) throw new Error(`deck.slides exceeds 40-slide limit: ${slides.length}`);

addCover(deck);
const contentSlides = firstSlideLooksLikeCover(slides) ? slides.slice(1) : slides;
contentSlides.forEach((slideData, index) => addDeckSlide(slideData, index + 2));

try {
  await pptx.writeFile({ fileName: args.out });
} catch (error) {
  throw new Error(`PPTX write failed: ${error?.message || error}`);
}
console.log(JSON.stringify({ ok: true, out: args.out, slides: pptx._slides.length }));

function addCover(data) {
  const slide = pptx.addSlide();
  slide.background = { color: C.pale };
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: H, fill: { color: C.pale }, line: { color: C.pale } });
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: 0.16, fill: { color: C.blue }, line: { color: C.blue } });
  slide.addShape(pptx.ShapeType.rect, { x: 10.15, y: 0.8, w: 1.8, h: 5.7, fill: { color: C.blue, transparency: 10 }, line: { color: C.blue, transparency: 100 }, rotate: 12 });
  decorateTemplateFrame(slide, 1, "cover");

  addOptionalLogo(slide, 0.82, 0.58, 2.3, 0.72);
  slide.addText("AI PRESENTATION", { x: 0.86, y: 1.62, w: 3.5, h: 0.26, fontFace: font, fontSize: 12, color: C.gold, bold: true, margin: 0 });
  slide.addText(cleanText(data.title, "AI 生成 PPT"), {
    x: 0.84,
    y: 2.16,
    w: 8.9,
    h: 1.4,
    fontFace: font,
    fontSize: 31,
    bold: true,
    color: C.deep,
    margin: 0,
    fit: "shrink",
    breakLine: false,
  });
  slide.addText(cleanText(data.subtitle || data.audience || data.theme || "根据提示词与论文内容自动生成"), {
    x: 0.88,
    y: 4.0,
    w: 6.8,
    h: 0.34,
    fontFace: font,
    fontSize: 14,
    color: C.ink,
    margin: 0,
    fit: "shrink",
  });
  slide.addText(new Date().toISOString().slice(0, 10), { x: 0.9, y: 4.48, w: 2.5, h: 0.24, fontFace: font, fontSize: 11, color: C.gray, margin: 0 });
  addSpeakerNotes(slide, data.notes || "");
}

function addDeckSlide(item, page) {
  const type = String(item.type || "content").toLowerCase();
  if (type === "section") return addSectionSlide(item, page);
  if (type === "contents") return addContentsSlide(item, page);
  if (type === "thanks") return addThanksSlide(item, page);
  if (type === "image") return addImageFocusSlide(item, page);
  if (type === "conclusion") return addConclusionSlide(item, page);
  return addContentSlide(item, page);
}

function addHeader(slide, section, title, page) {
  slide.background = { color: C.white };
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: H, fill: { color: C.white }, line: { color: C.white } });
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: 0.14, fill: { color: C.blue }, line: { color: C.blue } });
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 7.25, w: W, h: 0.25, fill: { color: C.pale }, line: { color: C.pale } });
  decorateTemplateFrame(slide, page, section === "CONTENTS" ? "contents" : "content");
  slide.addText(section || "PRESENTATION", { x: 0.56, y: 0.42, w: 2.4, h: 0.24, fontFace: font, fontSize: 9, bold: true, color: C.gold, margin: 0 });
  slide.addText(cleanText(title, "内容页"), { x: 0.56, y: 0.7, w: 9.7, h: 0.45, fontFace: font, fontSize: 24, bold: true, color: C.deep, margin: 0, fit: "shrink" });
  slide.addShape(pptx.ShapeType.line, { x: 0.56, y: 1.22, w: 11.95, h: 0, line: { color: C.line, width: 1 } });
  slide.addText(String(page).padStart(2, "0"), { x: 12.0, y: 0.54, w: 0.7, h: 0.28, fontFace: font, fontSize: 12, bold: true, color: C.blue, align: "right", margin: 0 });
  slide.addText(cleanText(deck.title, "AI 生成 PPT"), { x: 8.9, y: 7.31, w: 3.7, h: 0.11, fontFace: font, fontSize: 6.5, color: C.gray, align: "right", margin: 0, fit: "shrink" });
}

function addSectionSlide(item, page) {
  const slide = pptx.addSlide();
  slide.background = { color: C.deep };
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: H, fill: { color: C.deep }, line: { color: C.deep } });
  slide.addShape(pptx.ShapeType.rect, { x: 8.8, y: 0, w: 4.55, h: H, fill: { color: C.blue, transparency: 8 }, line: { color: C.blue, transparency: 100 } });
  decorateTemplateFrame(slide, page, "section");
  addOptionalLogo(slide, 0.84, 0.56, 2.1, 0.66);
  slide.addText(String(page - 1).padStart(2, "0"), { x: 0.84, y: 2.15, w: 1.2, h: 0.52, fontFace: font, fontSize: 32, bold: true, color: C.gold, margin: 0 });
  slide.addText(cleanText(item.title, item.section || "章节"), { x: 0.85, y: 2.9, w: 7.2, h: 0.6, fontFace: font, fontSize: 34, bold: true, color: C.white, margin: 0, fit: "shrink" });
  slide.addText(cleanText(item.headline || item.section || ""), { x: 0.88, y: 3.72, w: 7.1, h: 0.34, fontFace: font, fontSize: 14, color: "DCEBFF", margin: 0, fit: "shrink" });
  addSpeakerNotes(slide, item.notes);
}

function addContentsSlide(item, page) {
  const slide = pptx.addSlide();
  addHeader(slide, "CONTENTS", cleanText(item.title, "目录"), page);
  const bullets = normalizedBullets(item, 6);
  bullets.forEach((text, i) => {
    const y = 1.65 + i * 0.82;
    slide.addText(String(i + 1).padStart(2, "0"), { x: 0.98, y, w: 0.72, h: 0.32, fontFace: font, fontSize: 16, bold: true, color: C.gold, margin: 0 });
    slide.addText(text, { x: 1.9, y: y - 0.02, w: 8.8, h: 0.34, fontFace: font, fontSize: 17, bold: true, color: C.deep, margin: 0, fit: "shrink" });
    slide.addShape(pptx.ShapeType.line, { x: 1.0, y: y + 0.48, w: 10.8, h: 0, line: { color: C.line, width: 1 } });
  });
  addSpeakerNotes(slide, item.notes);
}

function addContentSlide(item, page) {
  const slide = pptx.addSlide();
  addHeader(slide, headerLabel(item), cleanText(item.title, "内容页"), page);

  const selectedImage = selectImage(item, page);
  const layout = resolveLayout(item, selectedImage);
  const metrics = normalizedMetrics(item, 4);
  if (layout === "full-image" && selectedImage) return addImageFocusBody(slide, item, selectedImage);
  if (layout === "metrics" || metrics.length >= 2) return addMetricsBody(slide, item, selectedImage, metrics);
  if (layout === "comparison") return addComparisonBody(slide, item, selectedImage);

  const hasImage = shouldUseImage(item, selectedImage, layout);
  const imageOnLeft = hasImage && layout === "image-left";
  const textX = imageOnLeft ? 6.55 : 0.74;
  const textW = hasImage ? 5.9 : 10.9;
  if (item.headline) {
    slide.addText(cleanText(item.headline, ""), { x: textX, y: 1.52, w: textW, h: 0.7, fontFace: font, fontSize: 23, bold: true, color: C.deep, margin: 0, fit: "shrink" });
  }

  const bullets = normalizedBullets(item, 5);
  bullets.forEach((text, i) => {
    const y = 2.48 + i * 0.72;
    slide.addShape(pptx.ShapeType.ellipse, { x: textX + 0.12, y: y + 0.09, w: 0.12, h: 0.12, fill: { color: i % 2 ? C.gold : C.blue }, line: { color: i % 2 ? C.gold : C.blue } });
    slide.addText(text, { x: textX + 0.38, y, w: textW - 0.4, h: 0.42, fontFace: font, fontSize: 14.5, color: C.ink, margin: 0, fit: "shrink", breakLine: false });
  });

  if (hasImage) {
    addImagePanel(slide, selectedImage, layout, item.imageHint || selectedImage.title);
  } else {
    addAccentPanel(slide, item);
  }
  addSpeakerNotes(slide, item.notes);
}

function addImageFocusSlide(item, page) {
  const slide = pptx.addSlide();
  addHeader(slide, headerLabel(item, "FIGURE"), cleanText(item.title, "图示分析"), page);
  const selectedImage = selectImage(item, page);
  addImageFocusBody(slide, item, selectedImage);
}

function addImageFocusBody(slide, item, selectedImage) {
  const headline = cleanText(item.headline, "");
  if (headline) slide.addText(headline, { x: 0.78, y: 1.42, w: 11.1, h: 0.44, fontFace: font, fontSize: 24, bold: true, color: C.deep, margin: 0, fit: "shrink" });
  if (selectedImage) {
    addFramedImage(slide, selectedImage, 0.78, headline ? 2.02 : 1.55, 11.7, headline ? 4.52 : 5.0);
    const caption = cleanText(item.imageHint || selectedImage.summary || selectedImage.title, "");
    if (caption) slide.addText(caption, { x: 0.86, y: 6.63, w: 10.9, h: 0.28, fontFace: font, fontSize: 12, color: C.gray, margin: 0, fit: "shrink" });
  } else {
    addAccentPanel(slide, item);
  }
  addSpeakerNotes(slide, item.notes);
}

function addMetricsBody(slide, item, selectedImage, metrics) {
  slide.addText(cleanText(item.headline, item.title), { x: 0.78, y: 1.42, w: 5.8, h: 0.62, fontFace: font, fontSize: 25, bold: true, color: C.deep, margin: 0, fit: "shrink" });
  normalizedBullets(item, 3).forEach((text, i) => {
    slide.addText(text, { x: 0.86, y: 2.35 + i * 0.48, w: 5.2, h: 0.32, fontFace: font, fontSize: 13.5, color: C.ink, margin: 0.02, fit: "shrink" });
  });
  const data = metrics.length ? metrics : inferMetrics(item);
  data.slice(0, 4).forEach((metricItem, i) => {
    const x = 0.86 + (i % 2) * 2.74;
    const y = 4.05 + Math.floor(i / 2) * 1.35;
    addMetricCard(slide, metricItem.value, metricItem.label, x, y, [C.blue, C.green, C.purple, C.gold][i % 4]);
  });
  if (selectedImage) addFramedImage(slide, selectedImage, 7.0, 1.65, 5.15, 4.55);
  addSpeakerNotes(slide, item.notes);
}

function addComparisonBody(slide, item, selectedImage) {
  slide.addText(cleanText(item.headline, item.title), { x: 0.78, y: 1.45, w: 11.0, h: 0.48, fontFace: font, fontSize: 24, bold: true, color: C.deep, margin: 0, fit: "shrink" });
  const bullets = normalizedBullets(item, 4);
  const left = bullets.slice(0, Math.ceil(bullets.length / 2));
  const right = bullets.slice(Math.ceil(bullets.length / 2));
  addCompareColumn(slide, cleanText(item.leftTitle, "传统路径"), left, 0.82, 2.25, "F8FAFC");
  addCompareColumn(slide, cleanText(item.rightTitle, "改进方案"), right.length ? right : bullets, 6.85, 2.25, "EEF6FF");
  if (selectedImage) addFramedImage(slide, selectedImage, 4.98, 5.48, 3.35, 0.85);
  addSpeakerNotes(slide, item.notes);
}

function addConclusionSlide(item, page) {
  const slide = pptx.addSlide();
  addHeader(slide, headerLabel(item, "CONCLUSION"), cleanText(item.title, "主要结论"), page);
  const bullets = normalizedBullets(item, 4);
  bullets.slice(0, 4).forEach((text, i) => {
    const x = 0.78 + i * 3.05;
    slide.addShape(pptx.ShapeType.roundRect, { x, y: 1.82, w: 2.55, h: 3.62, rectRadius: 0.08, fill: { color: i % 2 ? "EEF6FF" : "F8FAFC" }, line: { color: i % 2 ? "9BC9FF" : "CBD5E1" } });
    slide.addText(String(i + 1).padStart(2, "0"), { x: x + 0.25, y: 2.14, w: 0.56, h: 0.28, fontFace: font, fontSize: 13, bold: true, color: C.gold, margin: 0 });
    slide.addText(text, { x: x + 0.25, y: 2.74, w: 2.05, h: 1.28, fontFace: font, fontSize: 15, bold: i === 0, color: C.ink, align: "center", valign: "mid", margin: 0.02, fit: "shrink" });
  });
  if (item.headline) slide.addText(cleanText(item.headline, ""), { x: 1.0, y: 6.18, w: 10.9, h: 0.34, fontFace: font, fontSize: 16, bold: true, color: C.blue, align: "center", margin: 0, fit: "shrink" });
  addSpeakerNotes(slide, item.notes);
}

function addThanksSlide(item, page) {
  const slide = pptx.addSlide();
  slide.background = { color: C.deep };
  slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: H, fill: { color: C.deep }, line: { color: C.deep } });
  decorateTemplateFrame(slide, page, "thanks");
  slide.addText(cleanText(item.title, "谢谢聆听"), { x: 2.2, y: 2.55, w: 8.9, h: 0.8, fontFace: font, fontSize: 42, bold: true, color: C.white, align: "center", margin: 0, fit: "shrink" });
  slide.addText(cleanText(item.headline || item.notes || "欢迎交流与指正", ""), { x: 2.6, y: 3.65, w: 8.1, h: 0.32, fontFace: font, fontSize: 15, color: "DCEBFF", align: "center", margin: 0 });
  slide.addText(String(page).padStart(2, "0"), { x: 11.9, y: 6.8, w: 0.7, h: 0.3, fontFace: font, fontSize: 13, color: C.gold, bold: true, align: "right", margin: 0 });
  addSpeakerNotes(slide, item.notes);
}

function addImagePanel(slide, selectedImage, layout, hint) {
  const x = layout === "image-left" ? 0.74 : 7.3;
  const y = 1.55;
  const w = 4.95;
  const h = 4.85;
  slide.addShape(pptx.ShapeType.roundRect, { x: x - 0.06, y: y - 0.06, w: w + 0.12, h: h + 0.12, rectRadius: 0.06, fill: { color: "F8FAFC" }, line: { color: C.line, width: 1 }, shadow: { type: "outer", color: "CBD5E1", opacity: 0.14, blur: 1, angle: 45, distance: 1 } });
  const image = imageCache.get(selectedImage.path);
  if (image) {
    slide.addImage({ data: image.data, ...containBox(image, x, y, w, h) });
  } else {
    slide.addText("图片素材不可用", { x, y: y + 2.1, w, h: 0.28, fontFace: font, fontSize: 12, color: C.gray, align: "center", margin: 0 });
  }
  if (hint) {
    slide.addText(cleanText(hint, ""), { x, y: 6.55, w, h: 0.28, fontFace: font, fontSize: 9.5, color: C.gray, align: "center", margin: 0, fit: "shrink" });
  }
}

function addFramedImage(slide, selectedImage, x, y, w, h) {
  slide.addShape(pptx.ShapeType.roundRect, { x: x - 0.04, y: y - 0.04, w: w + 0.08, h: h + 0.08, rectRadius: 0.06, fill: { color: C.white }, line: { color: C.line, width: 1 }, shadow: { type: "outer", color: "CBD5E1", opacity: 0.14, blur: 1, angle: 45, distance: 1 } });
  const image = imageCache.get(selectedImage.path);
  if (image) slide.addImage({ data: image.data, ...containBox(image, x, y, w, h) });
}

function addMetricCard(slide, value, label, x, y, color) {
  slide.addShape(pptx.ShapeType.roundRect, { x, y, w: 2.45, h: 1.12, rectRadius: 0.06, fill: { color: "F8FAFC" }, line: { color: "CBD5E1", width: 1 } });
  slide.addText(cleanText(value, "--"), { x: x + 0.18, y: y + 0.14, w: 2.05, h: 0.38, fontFace: font, fontSize: 21, bold: true, color, margin: 0, fit: "shrink" });
  slide.addText(cleanText(label, "关键指标"), { x: x + 0.18, y: y + 0.66, w: 2.0, h: 0.25, fontFace: font, fontSize: 10.2, color: C.gray, margin: 0, fit: "shrink" });
}

function addCompareColumn(slide, title, bullets, x, y, fill) {
  slide.addShape(pptx.ShapeType.roundRect, { x, y, w: 5.35, h: 3.1, rectRadius: 0.08, fill: { color: fill }, line: { color: C.line, width: 1 } });
  slide.addText(title, { x: x + 0.3, y: y + 0.28, w: 4.6, h: 0.32, fontFace: font, fontSize: 18, bold: true, color: C.deep, margin: 0 });
  bullets.slice(0, 3).forEach((text, i) => {
    slide.addText(text, { x: x + 0.36, y: y + 0.92 + i * 0.58, w: 4.55, h: 0.34, fontFace: font, fontSize: 13, color: C.ink, margin: 0.02, fit: "shrink" });
  });
}

function addAccentPanel(slide, item) {
  slide.addShape(pptx.ShapeType.roundRect, { x: 7.3, y: 1.72, w: 4.9, h: 4.65, rectRadius: 0.08, fill: { color: C.pale }, line: { color: C.line, width: 1 } });
  slide.addText("KEY MESSAGE", { x: 7.72, y: 2.12, w: 2.2, h: 0.22, fontFace: font, fontSize: 10, bold: true, color: C.gold, margin: 0 });
  slide.addText(cleanText(item.imageHint || item.headline || item.title, "核心观点"), { x: 7.72, y: 2.68, w: 4.05, h: 1.45, fontFace: font, fontSize: 25, bold: true, color: C.deep, margin: 0, fit: "shrink" });
  slide.addShape(pptx.ShapeType.line, { x: 7.72, y: 4.52, w: 3.7, h: 0, line: { color: C.blue, width: 2 } });
  slide.addText(cleanText(deck.theme || deck.audience || "自动生成可编辑演示文稿"), { x: 7.72, y: 4.9, w: 3.9, h: 0.42, fontFace: font, fontSize: 12, color: C.gray, margin: 0, fit: "shrink" });
}

function addOptionalLogo(slide, x, y, w, h) {
  const logo = templateAssets[0];
  if (!logo) return;
  const image = imageCache.get(logo);
  if (image) slide.addImage({ data: image.data, x, y, w, h, sizingCrop: false });
}

function decorateTemplateFrame(slide, page, role) {
  if (!useTemplateFramework) return;
  const frame = templateFrameFor(page, role);
  const asset = templateAssetForFrame(frame, page);
  const image = asset ? imageCache.get(asset) : null;
  const isMajor = ["cover", "section", "thanks"].includes(role);
  const frameRole = String(frame?.role || role || "content");

  if (isMajor) {
    if (image && isWideImage(image)) {
      slide.addImage({ data: image.data, ...coverBox(image, 0, 0, W, H) });
      slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: H, fill: { color: C.deep, transparency: role === "cover" ? 18 : 8 }, line: { color: C.deep, transparency: 100 } });
    } else if (image) {
      slide.addImage({ data: image.data, ...containBox(image, 9.25, 0.72, 2.45, 1.15) });
    }
    slide.addShape(pptx.ShapeType.rect, { x: 0, y: H - 0.32, w: W, h: 0.32, fill: { color: C.gold, transparency: 16 }, line: { color: C.gold, transparency: 100 } });
    return;
  }

  const leftRail = frameRole === "image-content" || frameRole === "image";
  if (leftRail) {
    slide.addShape(pptx.ShapeType.rect, { x: 0, y: 1.28, w: 0.18, h: 5.7, fill: { color: C.blue, transparency: 8 }, line: { color: C.blue, transparency: 100 } });
  } else {
    slide.addShape(pptx.ShapeType.rect, { x: 12.82, y: 1.18, w: 0.16, h: 5.85, fill: { color: C.gold, transparency: 18 }, line: { color: C.gold, transparency: 100 } });
  }
  if (frameRole === "comparison") {
    slide.addShape(pptx.ShapeType.line, { x: 6.55, y: 1.65, w: 0, h: 4.95, line: { color: C.line, width: 1 } });
  }
  if (image) {
    const box = isWideImage(image)
      ? containBox(image, 9.5, 6.62, 2.2, 0.32)
      : containBox(image, 11.12, 0.28, 1.2, 0.42);
    slide.addImage({ data: image.data, ...box });
  }
}

function templateFrameFor(page, role) {
  if (!templateFramework.length) return { role };
  const wanted = String(role || "content");
  const exact = templateFramework.find((item) => String(item.role || "") === wanted);
  if (exact) return exact;
  if (wanted === "image") {
    const imageFrame = templateFramework.find((item) => ["image", "image-content"].includes(String(item.role || "")));
    if (imageFrame) return imageFrame;
  }
  const contentFrame = templateFramework.find((item) => String(item.role || "") === "content");
  if (contentFrame && !["cover", "section", "thanks"].includes(wanted)) return contentFrame;
  return templateFramework[(Math.max(1, page) - 1) % templateFramework.length] || { role };
}

function templateAssetForFrame(frame, page) {
  if (!templateAssets.length) return "";
  const index = Number(frame?.index || page || 1);
  return templateAssets[(Math.max(1, index) - 1) % templateAssets.length];
}

function isWideImage(image) {
  return image && image.width / Math.max(1, image.height) >= 1.45;
}

function addSpeakerNotes(slide, notes) {
  const text = cleanText(notes, "");
  if (text) slide.addNotes(text);
}

function normalizedBullets(item, limit) {
  const raw = Array.isArray(item.bullets) ? item.bullets : [];
  const bullets = raw.map((value) => cleanText(value, "")).filter(Boolean).slice(0, limit);
  if (bullets.length) return bullets;
  if (item.headline) return [cleanText(item.headline, "")];
  return ["围绕提示词梳理核心观点", "结合论文内容提炼关键信息", "保持可编辑、便于二次修改"];
}

function normalizedMetrics(item, limit) {
  const raw = Array.isArray(item.metrics) ? item.metrics : [];
  return raw.map((value) => ({
    value: cleanText(value?.value, ""),
    label: cleanText(value?.label, ""),
  })).filter((value) => value.value || value.label).slice(0, limit);
}

function inferMetrics(item) {
  const text = normalizedBullets(item, 5).join(" ");
  const matches = [...text.matchAll(/(\d+(?:\.\d+)?%?)/g)].slice(0, 4);
  return matches.map((match, index) => ({ value: match[1], label: index === 0 ? "关键结果" : "辅助指标" }));
}

function normalizeLayout(value) {
  const layout = String(value || "auto").toLowerCase();
  return ["full-image", "image-left", "image-right", "metrics", "comparison", "conclusion"].includes(layout) ? layout : "auto";
}

function resolveLayout(item, selectedImage) {
  const explicit = normalizeLayout(item.layout);
  if (!selectedImage || explicit !== "auto") return explicit;
  const hint = normalizeLayout(selectedImage.layoutHint);
  if (hint !== "auto") return hint;
  const image = imageCache.get(selectedImage.path);
  if (!image) return "image-right";
  const ratio = image.width / Math.max(1, image.height);
  if (ratio > 1.55 || selectedImage.importance >= 4 || ["chart", "table", "workflow", "architecture"].includes(selectedImage.kind)) {
    return "full-image";
  }
  return ratio < 0.85 ? "image-left" : "image-right";
}

function headerLabel(item, fallback = "SECTION") {
  const raw = cleanText(item.section, "");
  if (!raw || /^\d+(?:\.\d+)*$/.test(raw)) return typeLabel(item.type, fallback);
  return raw;
}

function typeLabel(type, fallback) {
  const value = String(type || "").toLowerCase();
  if (value === "image") return "FIGURE";
  if (value === "conclusion") return "CONCLUSION";
  if (value === "contents") return "CONTENTS";
  return fallback;
}

function shouldUseImage(item, selectedImage, layout) {
  if (!selectedImage) return false;
  if (selectedImage.useful === false || selectedImage.importance < 3) return false;
  const imageIntent = cleanText(item.imageHint || item.imageId, "");
  const type = String(item.type || "").toLowerCase();
  if (String(item.imageHint || "").toLowerCase() === "none") return false;
  if (layout === "full-image" || layout === "image-left" || layout === "image-right") return true;
  if (type === "image") return true;
  if (imageIntent) return true;
  return false;
}

function selectImage(item, page) {
  const imageId = cleanText(item.imageId, "");
  if (imageId && imageById.has(imageId)) return usefulImage(imageById.get(imageId));
  return null;
}

function usefulImage(entry) {
  if (!entry || entry.useful === false || entry.importance < 3) return null;
  return entry;
}

function containBox(image, x, y, w, h) {
  const ratio = image.width / Math.max(1, image.height);
  const boxRatio = w / h;
  let drawW = w;
  let drawH = h;
  if (ratio > boxRatio) {
    drawH = w / ratio;
  } else {
    drawW = h * ratio;
  }
  return {
    x: x + (w - drawW) / 2,
    y: y + (h - drawH) / 2,
    w: drawW,
    h: drawH,
  };
}

function coverBox(image, x, y, w, h) {
  const ratio = image.width / Math.max(1, image.height);
  const boxRatio = w / h;
  let drawW = w;
  let drawH = h;
  if (ratio > boxRatio) {
    drawW = h * ratio;
  } else {
    drawH = w / ratio;
  }
  return {
    x: x + (w - drawW) / 2,
    y: y + (h - drawH) / 2,
    w: drawW,
    h: drawH,
  };
}

function firstSlideLooksLikeCover(items) {
  const first = items[0] || {};
  return String(first.type || "").toLowerCase() === "cover";
}

function cleanText(value, fallback) {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  return text || fallback;
}

function normalizePalette(input) {
  if (!Array.isArray(input)) return [];
  return input
    .map((value) => String(value || "").replace(/^#/, "").trim().toUpperCase())
    .filter((value) => /^[0-9A-F]{6}$/.test(value))
    .slice(0, 8);
}

async function listUsableAssets(dir) {
  if (!dir) return [];
  const assets = [];
  for (let index = 1; index <= 24; index += 1) {
    for (const ext of [".png", ".jpg", ".jpeg"]) {
      const file = path.join(dir, `template-asset-${index}${ext}`);
      try {
        await access(file);
        assets.push(file);
      } catch {
        // Try next extension.
      }
    }
  }
  return assets;
}

async function loadImageData(imagePath) {
  try {
    const bytes = await readFile(imagePath);
    const dimensions = imageSize(bytes);
    const mimeType = imageMimeType(imagePath, dimensions.type);
    if (!mimeType) throw new Error(`unsupported image type: ${dimensions.type || path.extname(imagePath)}`);
    return {
      data: `data:${mimeType};base64,${bytes.toString("base64")}`,
      width: dimensions.width,
      height: dimensions.height,
    };
  } catch (error) {
    console.warn(JSON.stringify({
      level: "warn",
      message: "Skipping unusable PPT image asset",
      imagePath,
      error: error?.message || String(error),
    }));
    return null;
  }
}

async function readJson(file, fallback) {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch {
    return fallback;
  }
}

function buildImageIndex(imagePaths, manifestValue) {
  const byId = new Map();
  const manifestImages = Array.isArray(manifestValue?.images) ? manifestValue.images : [];
  const byManifestId = new Map(manifestImages.map((item) => [String(item.id || ""), item]));
  imagePaths.forEach((imagePath, index) => {
    const id = path.basename(imagePath, path.extname(imagePath));
    const item = byManifestId.get(id) || manifestImages.find((entry) => Number(entry.index) === index + 1) || {};
    byId.set(id, {
      id,
      path: imagePath,
      title: cleanText(item.title, id),
      summary: cleanText(item.summary, ""),
      bestUse: cleanText(item.bestUse, ""),
      kind: cleanText(item.kind, "other"),
      importance: Number(item.importance || 3),
      useful: item.useful !== false,
      layoutHint: cleanText(item.layoutHint, "auto"),
    });
  });
  return byId;
}

function imageMimeType(imagePath, detectedType) {
  const type = String(detectedType || "").toLowerCase();
  if (type === "png") return "image/png";
  if (type === "jpg" || type === "jpeg") return "image/jpeg";
  const ext = path.extname(imagePath).toLowerCase();
  if (ext === ".png") return "image/png";
  if (ext === ".jpg" || ext === ".jpeg") return "image/jpeg";
  return "";
}

function parseArgs(values) {
  const parsed = {};
  for (let i = 0; i < values.length; i += 1) {
    const value = values[i];
    if (!value.startsWith("--")) continue;
    parsed[value.slice(2)] = values[i + 1];
    i += 1;
  }
  return parsed;
}

async function renderTemplateFill(templatePath, outPath, deckValue) {
  const zip = await JSZip.loadAsync(await readFile(templatePath));
  const slides = Array.isArray(deckValue.slides) && deckValue.slides.length ? deckValue.slides : [deckValue];
  const slideFiles = Object.keys(zip.files)
    .filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name))
    .sort((a, b) => slideNumber(a) - slideNumber(b));
  if (!slideFiles.length) throw new Error("template pptx has no slides");

  await ensureTemplateSlideCount(zip, slideFiles, slides.length);
  const finalSlideFiles = Object.keys(zip.files)
    .filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name))
    .sort((a, b) => slideNumber(a) - slideNumber(b))
    .slice(0, slides.length);

  for (let i = 0; i < finalSlideFiles.length; i += 1) {
    const file = finalSlideFiles[i];
    const xml = await zip.file(file).async("string");
    zip.file(file, replaceSlideText(xml, slideTextValues(slides[i], i, deckValue)));
  }
  await trimPresentationToSlides(zip, finalSlideFiles);
  await writeFile(outPath, await zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE" }));
}

async function ensureTemplateSlideCount(zip, slideFiles, requiredCount) {
  let current = slideFiles.length;
  if (current >= requiredCount) return;
  const source = slideFiles[slideFiles.length - 1];
  const sourceNumber = slideNumber(source);
  let nextNumber = Math.max(...slideFiles.map(slideNumber)) + 1;
  const sourceXml = await zip.file(source).async("string");
  const sourceRelsPath = `ppt/slides/_rels/slide${sourceNumber}.xml.rels`;
  const sourceRels = zip.file(sourceRelsPath) ? await zip.file(sourceRelsPath).async("string") : null;
  while (current < requiredCount) {
    current += 1;
    const targetNumber = nextNumber++;
    const target = `ppt/slides/slide${targetNumber}.xml`;
    zip.file(target, sourceXml);
    if (sourceRels) {
      zip.file(`ppt/slides/_rels/slide${targetNumber}.xml.rels`, sanitizeClonedSlideRelationships(sourceRels));
    }
    await ensureContentType(zip, targetNumber);
  }
}

function sanitizeClonedSlideRelationships(xml) {
  return xml.replace(
    /<Relationship\b[^>]*Type="[^"]*\/(?:notesSlide|comments?|commentAuthors)"[^>]*\/>/g,
    "",
  );
}

async function trimPresentationToSlides(zip, slideFiles) {
  const presentation = zip.file("ppt/presentation.xml");
  const rels = zip.file("ppt/_rels/presentation.xml.rels");
  if (!presentation || !rels) return;
  const slideIds = slideFiles.map((file, index) => ({
    file,
    id: 256 + index,
    rid: `rIdTemplateFill${index + 1}`,
    target: `slides/${path.basename(file)}`,
  }));
  const presentationXml = await presentation.async("string");
  const list = slideIds.map((item) => `<p:sldId id="${item.id}" r:id="${item.rid}"/>`).join("");
  zip.file("ppt/presentation.xml", presentationXml.replace(/<p:sldIdLst>[\s\S]*?<\/p:sldIdLst>/, `<p:sldIdLst>${list}</p:sldIdLst>`));

  const relsXml = await rels.async("string");
  const keptNonSlide = relsXml.replace(/<Relationship[^>]+Type="[^"]*\/slide"[^>]*\/>/g, "");
  const slideRels = slideIds.map((item) => `<Relationship Id="${item.rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="${item.target}"/>`).join("");
  zip.file("ppt/_rels/presentation.xml.rels", keptNonSlide.replace("</Relationships>", `${slideRels}</Relationships>`));
}

async function ensureContentType(zip, slideNumberValue) {
  const file = zip.file("[Content_Types].xml");
  if (!file) return;
  const xml = await file.async("string");
  const partName = `/ppt/slides/slide${slideNumberValue}.xml`;
  if (xml.includes(partName)) return;
  const override = `<Override PartName="${partName}" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>`;
  zip.file("[Content_Types].xml", xml.replace("</Types>", `${override}</Types>`));
}

function replaceSlideText(xml, values) {
  let index = 0;
  const withRuns = xml.replace(/<a:r\b[\s\S]*?<\/a:r>/g, (runXml) => {
    if (!/<a:t>[\s\S]*?<\/a:t>/.test(runXml)) return runXml;
    const value = index < values.length ? values[index] : "";
    index += 1;
    return replaceRunText(runXml, value);
  });
  if (index > 0) return withRuns;
  return xml.replace(/<a:t>([\s\S]*?)<\/a:t>/g, (match, originalText) => {
    const value = index < values.length ? values[index] : "";
    index += 1;
    return `<a:t>${escapeXml(fitTemplateText(value, unescapeXml(originalText)).text)}</a:t>`;
  });
}

function replaceRunText(runXml, value) {
  const match = /<a:t>([\s\S]*?)<\/a:t>/.exec(runXml);
  const originalText = match ? unescapeXml(match[1]) : "";
  const fitted = fitTemplateText(value, originalText);
  let output = runXml.replace(/<a:t>[\s\S]*?<\/a:t>/, `<a:t>${escapeXml(fitted.text)}</a:t>`);
  if (fitted.scale < 1) output = shrinkRunFont(output, fitted.scale);
  return output;
}

function fitTemplateText(value, originalText) {
  const text = cleanText(value, "");
  const original = cleanText(originalText, "");
  if (!text || !original) return { text, scale: 1 };
  const ratio = text.length / Math.max(1, original.length);
  let scale = 1;
  if (ratio > 3.2) scale = 0.55;
  else if (ratio > 2.4) scale = 0.64;
  else if (ratio > 1.8) scale = 0.74;
  else if (ratio > 1.35) scale = 0.84;
  return { text, scale };
}

function shrinkRunFont(runXml, scale) {
  return runXml.replace(/<a:rPr\b([^>]*)>/, (match, attrs) => {
    if (/sz="\d+"/.test(attrs)) {
      return `<a:rPr${attrs.replace(/sz="(\d+)"/, (_, size) => `sz="${Math.max(900, Math.round(Number(size) * scale))}"`)}>`;
    }
    const selfClosing = /\/\s*$/.test(attrs);
    const cleanAttrs = attrs.replace(/\s*\/\s*$/, "");
    const size = Math.max(900, Math.round(1800 * scale));
    return `<a:rPr${cleanAttrs} sz="${size}"${selfClosing ? "/" : ""}>`;
  });
}

function slideTextValues(slide, index, deckValue) {
  const values = [];
  values.push(cleanText(slide?.title, index === 0 ? cleanText(deckValue.title, "AI 生成 PPT") : `第 ${index + 1} 页`));
  const headline = cleanText(slide?.headline || slide?.section || "", "");
  if (headline) values.push(headline);
  const bullets = Array.isArray(slide?.bullets) ? slide.bullets.map((item) => cleanText(item, "")).filter(Boolean) : [];
  values.push(...bullets.slice(0, 8));
  const metrics = Array.isArray(slide?.metrics) ? slide.metrics : [];
  for (const metric of metrics.slice(0, 4)) {
    values.push(cleanText(`${metric?.value || ""} ${metric?.label || ""}`, ""));
  }
  return values.filter((value) => value !== "");
}

function slideNumber(file) {
  const match = /slide(\d+)\.xml$/.exec(file);
  return match ? Number(match[1]) : 0;
}

function escapeXml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function unescapeXml(value) {
  return String(value ?? "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}
