import JSZip from "jszip";
import { execFile } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const base = "/tmp/web-pptx-generator-smoke.pptx";
const deckPath = "/tmp/web-pptx-template-fill-deck.json";
const output = "/tmp/web-pptx-template-fill-smoke.pptx";

await execFileAsync("node", [
  "generate_deck.mjs",
  "--deck", "fixtures/sample-deck.json",
  "--style", "fixtures/sample-style.json",
  "--images", "fixtures/sample-images.json",
  "--out", base,
]);

const deck = JSON.parse(await readFile("fixtures/sample-deck.json", "utf8"));
while (deck.slides.length < 7) {
  deck.slides.push({ ...deck.slides.at(-1), title: `扩展页 ${deck.slides.length + 1}` });
}
await writeFile(deckPath, JSON.stringify(deck));
await execFileAsync("node", [
  "generate_deck.mjs",
  "--deck", deckPath,
  "--style", "fixtures/sample-style.json",
  "--images", "fixtures/sample-images.json",
  "--mode", "template-fill",
  "--template", base,
  "--out", output,
]);

const zip = await JSZip.loadAsync(await readFile(output));
const slideFiles = Object.keys(zip.files).filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name));
if (slideFiles.length < 7) throw new Error(`expected 7 slides, got ${slideFiles.length}`);
for (const number of [5, 6, 7]) {
  const rel = zip.file(`ppt/slides/_rels/slide${number}.xml.rels`);
  if (!rel) continue;
  const xml = await rel.async("string");
  if (/\/notesSlide|\/comments?|\/commentAuthors/.test(xml)) {
    throw new Error(`cloned slide ${number} retained unique-part relationships`);
  }
}
console.log(JSON.stringify({ ok: true, output, slides: slideFiles.length }));
