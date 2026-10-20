// Pure helpers for the 月下占卜 flow; kept framework-free so node scripts can test them.
import { themeResonance } from "./tarotThemes.js";

export const TAROT_READING_VERSION = "tarot-reading-v1";

export function orientationLabel(orientation) {
  return orientation === "reversed" ? "逆位" : "正位";
}

export function cardImage(cardId) {
  return `/tarot/${cardId}.webp`;
}

export function findSpread(catalogue, spreadId) {
  return catalogue.spreads.find(spread => spread.id === spreadId) || null;
}

export function findTopic(catalogue, topicId) {
  return catalogue.topics.find(topic => topic.id === topicId) || catalogue.topics[0];
}

/** Random, topic-specific suggestions; prefer unseen examples when refreshing a group. */
export function recommendedQuestions(catalogue, topicId, previous = [], count = 3, random = Math.random) {
  const pool = [...new Set(findTopic(catalogue, topicId)?.examples || [])];
  for (let index = pool.length - 1; index > 0; index--) {
    const next = Math.floor(random() * (index + 1));
    [pool[index], pool[next]] = [pool[next], pool[index]];
  }
  return [...pool.filter(example => !previous.includes(example)), ...pool.filter(example => previous.includes(example))].slice(0, count);
}

export function findCard(catalogue, cardId) {
  return catalogue.cards.find(card => card.cardId === cardId) || null;
}

/** Returns an error message, or "" when the question form can be submitted. */
export function questionProblem(catalogue, { question = "", topicId = "" } = {}) {
  const { questionMin, questionMax } = catalogue.limits;
  const text = question.trim();
  if (!catalogue.topics.some(topic => topic.id === topicId)) return "先选一个想问的方向。";
  if (text.length < questionMin) return `把问题写得再具体一点（至少 ${questionMin} 个字）。`;
  if (text.length > questionMax) return `问题请控制在 ${questionMax} 字以内。`;
  return "";
}

export function spreadProblem(catalogue, { spreadId = "", optionA = "", optionB = "" } = {}) {
  const spread = findSpread(catalogue, spreadId);
  if (!spread) return "先选择一个牌阵。";
  if (!spread.requiresOptions) return "";
  const max = catalogue.limits.optionMax;
  const a = optionA.trim(), b = optionB.trim();
  if (!a || !b) return "二择一需要写下两个选项。";
  if (a.length > max || b.length > max) return `每个选项请控制在 ${max} 字以内。`;
  if (a === b) return "两个选项需要有所不同。";
  return "";
}

export function validatePicks(positions, picksRequired, fanSize = 22) {
  if (!Array.isArray(positions) || positions.length !== picksRequired) return false;
  if (new Set(positions).size !== positions.length) return false;
  return positions.every(value => Number.isInteger(value) && value >= 0 && value < fanSize);
}

export function readingTone(cards) {
  const reversed = cards.filter(card => card.orientation === "reversed").length;
  const total = cards.length;
  if (!total) return { key: "empty", label: "", text: "" };
  if (reversed === 0) return { key: "flowing", label: "顺流", text: "牌面全部正位，能量流动顺畅，适合顺势推进。" };
  if (reversed === total) return { key: "inward", label: "内省", text: total === 1 ? "这张牌以逆位出现，提示阻滞或需要向内调整。" : "牌面全部逆位，事情可能卡在内在的结上，先整理自己再向外行动。" };
  if (reversed * 2 > total) return { key: "tangled", label: "迂回", text: `${total} 张牌中有 ${reversed} 张逆位，阻力多于助力，宜放慢脚步、先解开卡住的地方。` };
  return { key: "mixed", label: "交织", text: `${total} 张牌中有 ${reversed} 张逆位，顺逆交织：有可借的力，也有需要留意的结。` };
}

/**
 * Combines a server draw with the local meaning catalogue and, when a resolver is given,
 * the moonlit theme each card manifested in.
 * draw.cards: [{ order, position, fanIndex, cardId, orientation, themeId? }]
 */
export function buildInstantReading({ catalogue, draw, resolveTheme = null }) {
  const spread = findSpread(catalogue, draw?.spreadId);
  if (!spread) return null;
  const topic = findTopic(catalogue, draw.topicId);
  const byPosition = new Map((draw.cards || []).map(card => [card.position, card]));
  const cards = spread.positions.map((position, index) => {
    const drawn = byPosition.get(position.id) || draw.cards?.[index];
    const card = drawn && findCard(catalogue, drawn.cardId);
    if (!card) return null;
    const orientation = drawn.orientation === "reversed" ? "reversed" : "upright";
    const face = card[orientation];
    const theme = resolveTheme ? resolveTheme({ ...drawn, position: position.id }, draw.drawId || "") : null;
    return {
      order: index,
      position,
      card,
      orientation,
      orientationLabel: orientationLabel(orientation),
      keywords: face.keywords,
      meaning: face.meaning,
      advice: face.advice,
      theme,
      image: theme?.cover || cardImage(card.cardId),
    };
  });
  if (cards.some(item => !item)) return null;
  const tone = readingTone(cards);
  const byId = Object.fromEntries(cards.map(item => [item.position.id, item]));
  const comparison = spread.requiresOptions
    ? {
        optionA: { label: draw.options?.optionA || "选择一", now: byId["optionA-now"], future: byId["optionA-future"] },
        optionB: { label: draw.options?.optionB || "选择二", now: byId["optionB-now"], future: byId["optionB-future"] },
      }
    : null;
  return {
    spread,
    topic,
    question: draw.question || "",
    options: draw.options || null,
    cards,
    tone,
    focus: focusCard(spread, byId),
    comparison,
    resonance: themeResonance(cards.map(item => item.theme)),
    summary: summarize(spread, cards, tone),
  };
}

function focusCard(spread, byId) {
  if (spread.id === "single") return byId.focus;
  if (spread.id === "triangle") return byId.future;
  return byId.now;
}

function summarize(spread, cards, tone) {
  const names = cards.map(item => `${item.position.label}「${item.card.name}·${item.orientationLabel}」`).join("，");
  if (spread.id === "single") {
    const only = cards[0];
    return `${only.card.name}以${only.orientationLabel}出现，关键词是${only.keywords.slice(0, 2).join("与")}。${tone.text}`;
  }
  return `${names}。${tone.text}`;
}
