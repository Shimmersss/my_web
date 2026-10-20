// DEV-ONLY mock of the 月下占卜 contract (docs/plans/2026-10-13-moonlit-divination.md).
// Loaded only when `import.meta.env.DEV && VITE_TAROT_MOCK=1`; never part of a production build.
import catalogue from "@/data/tarotReading.json";
import { buildInstantReading, findSpread, findTopic, TAROT_READING_VERSION, validatePicks } from "@/utils/tarotReading";
import { resolveTheme } from "@/data/tarotThemeLibrary";
import { THEME_FAMILIES } from "@/utils/tarotThemes";

const STORE_KEY = "tarot-reading-mock-v1";
const STAGES = [["queued", 900], ["preparing", 1800], ["writing", 3600], ["saving", 4400]];

function load() {
  try { return JSON.parse(sessionStorage.getItem(STORE_KEY)) || fresh(); }
  catch { return fresh(); }
}
function fresh() { return { draw: null, hiddenOrder: [], tasks: [], reports: [] }; }
function save(state) {
  try { sessionStorage.setItem(STORE_KEY, JSON.stringify(state)); }
  catch { /* private mode: the mock simply forgets on reload */ }
}
const wait = (ms = 240 + Math.random() * 260) => new Promise(resolve => setTimeout(resolve, ms));
const ok = data => ({ code: 200, data: structuredClone(data) });
function fail(status, message) { const error = new Error(message); error.status = status; throw error; }
const uuid = () => crypto.randomUUID();

export async function getMatchmakingStatus() {
  await wait(120);
  // No `credits`: the mock must not overwrite the real balance shown in the header.
  return ok({ creditCost: 2, retentionDays: 30, tarotAiEnabled: true, drawsPerHour: 12 });
}

export async function getCurrentMatchmakingTarot() {
  await wait(120);
  return ok({ draw: load().draw });
}

export async function createTarotDraw({ spreadId, topicId, question, optionA = "", optionB = "" }) {
  await wait();
  const state = load();
  const spread = findSpread(catalogue, spreadId);
  if (!spread) fail(400, "牌阵无效");
  if (state.tasks.some(task => ["queued", "running"].includes(task.status))) fail(409, "当前牌阵正在生成解读，请等待完成后再抽牌");
  const hiddenOrder = catalogue.cards.map(card => ({
    cardId: card.cardId,
    orientation: Math.random() < 0.5 ? "upright" : "reversed",
    themeId: THEME_FAMILIES[Math.floor(Math.random() * THEME_FAMILIES.length)].themeId,
  }));
  for (let i = hiddenOrder.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [hiddenOrder[i], hiddenOrder[j]] = [hiddenOrder[j], hiddenOrder[i]];
  }
  const now = new Date();
  state.hiddenOrder = hiddenOrder;
  state.draw = {
    drawId: uuid(), catalogueVersion: catalogue.version, deckVersion: catalogue.deckVersion,
    spreadId, topicId: findTopic(catalogue, topicId).id, question: question.trim(),
    options: spread.requiresOptions ? { optionA: optionA.trim(), optionB: optionB.trim() } : null,
    state: "shuffled", fanSize: 22, picksRequired: spread.cardCount, picks: null, cards: [],
    drawnAt: now.toISOString(), expiresAt: new Date(now.getTime() + 24 * 3600e3).toISOString(),
  };
  save(state);
  return ok(state.draw);
}

export async function pickTarotCards(drawId, requested) {
  await wait();
  const positions = Array.from(requested || []);
  const state = load();
  const draw = state.draw;
  if (!draw || draw.drawId !== drawId) fail(404, "牌阵不存在或已过期");
  if (!validatePicks(positions, draw.picksRequired, draw.fanSize)) fail(400, "选牌位置无效");
  if (draw.picks) {
    if (JSON.stringify(draw.picks) !== JSON.stringify(positions)) fail(409, "这组牌已经选定");
    return ok(draw);
  }
  const spread = findSpread(catalogue, draw.spreadId);
  draw.picks = positions;
  draw.state = "picked";
  draw.cards = positions.map((fanIndex, order) => {
    const hidden = state.hiddenOrder[fanIndex];
    const card = catalogue.cards.find(item => item.cardId === hidden.cardId);
    const position = spread.positions[order];
    const theme = resolveTheme({ cardId: card.cardId, themeId: hidden.themeId });
    return {
      order, position: position.id, positionLabel: position.label, fanIndex, cardId: card.cardId, sourceId: card.sourceId,
      name: card.name, orientation: hidden.orientation, themeId: theme.themeId, variantId: theme.variantId, themeName: theme.themeName,
    };
  });
  save(state);
  return ok(draw);
}

export async function createTarotReadingReport(drawId) {
  await wait();
  const state = load();
  if (!state.draw || state.draw.drawId !== drawId || state.draw.state !== "picked") fail(409, "请先完成选牌");
  state.draw.state = "consumed";
  const task = { id: uuid(), status: "queued", stage: "queued", reportVersion: TAROT_READING_VERSION, reportId: "", error: "", compensationPending: false, createdAt: new Date().toISOString(), drawId };
  state.tasks.unshift(task);
  save(state);
  return ok({ taskId: task.id, reportVersion: TAROT_READING_VERSION });
}

function advance(state, task) {
  if (task.status === "done" || task.status === "error") return task;
  const elapsed = Date.now() - new Date(task.createdAt).getTime();
  const stage = STAGES.find(([, until]) => elapsed < until);
  if (stage) {
    task.stage = stage[0];
    task.status = stage[0] === "queued" ? "queued" : "running";
    return task;
  }
  const report = mockReport(state.draw);
  state.reports.unshift(report);
  state.draw = null;
  Object.assign(task, { status: "done", stage: "done", reportId: report.id });
  return task;
}

export async function getMatchmakingTasks() {
  await wait(120);
  const state = load();
  state.tasks.forEach(task => advance(state, task));
  save(state);
  return ok(state.tasks.slice(0, 10).map(({ drawId, ...task }) => task));
}

export async function getMatchmakingTask(taskId) {
  await wait(160);
  const state = load();
  const task = state.tasks.find(item => item.id === taskId);
  if (!task) fail(404, "任务不存在");
  advance(state, task);
  save(state);
  const { drawId, ...view } = task;
  return ok(view);
}

export async function getMatchmakingReportArchive(page = 1, pageSize = 10) {
  await wait(140);
  const { reports } = load();
  const total = reports.length, totalPages = total ? Math.ceil(total / pageSize) : 0;
  const current = Math.min(Math.max(1, page), Math.max(1, totalPages));
  const items = reports.slice((current - 1) * pageSize, current * pageSize).map(report => ({
    id: report.id, reportVersion: report.reportVersion, title: report.reading.title,
    personalityLabel: `${report.spread.name} · ${report.topic.label}`, subtitle: `${report.spread.name} · ${report.topic.label}`,
    ownerLabel: "本地模拟", viewerCanDelete: true, createdAt: report.createdAt,
  }));
  return ok({ items, page: current, pageSize, total, totalPages });
}

export async function getMatchmakingReport(reportId) {
  await wait(160);
  const report = load().reports.find(item => item.id === reportId);
  if (!report) fail(404, "报告不存在或已过期");
  return ok(report);
}

export async function deleteMatchmakingReport(reportId) {
  await wait(160);
  const state = load();
  state.reports = state.reports.filter(item => item.id !== reportId);
  state.tasks = state.tasks.filter(item => item.reportId !== reportId);
  save(state);
  return ok({ deleted: true });
}

export async function deleteMatchmakingData() {
  await wait(160);
  save(fresh());
  return ok({ deleted: true });
}

function mockReport(draw) {
  const reading = buildInstantReading({ catalogue, draw, resolveTheme });
  const now = new Date();
  const cards = reading.cards.map(item => ({
    position: item.position.id, positionLabel: item.position.label, cardId: item.card.cardId, name: item.card.name,
    orientation: item.orientation, keywords: item.keywords, meaning: item.meaning, advice: item.advice,
    themeId: item.theme.themeId, variantId: item.theme.variantId, themeName: item.theme.themeName, themeMeaning: item.theme.meaning,
  }));
  return {
    id: uuid(), reportVersion: TAROT_READING_VERSION, catalogueVersion: catalogue.version, deckVersion: catalogue.deckVersion,
    spread: { id: reading.spread.id, name: reading.spread.name, positions: reading.spread.positions },
    topic: { id: reading.topic.id, label: reading.topic.label }, question: draw.question, options: draw.options, cards,
    reading: {
      title: `【模拟】${reading.focus.card.name}的${reading.tone.label || "指引"}`,
      overview: `这是本地开发模拟生成的占位解读，用于检查页面结构。${reading.summary}`,
      cards: reading.cards.map(item => ({
        position: item.position.id, cardId: item.card.cardId, orientation: item.orientation,
        interpretation: `在「${item.position.label}」的位置上，${item.card.name}以「${item.theme.themeName}」主题${item.orientationLabel}显现：${item.meaning}`,
        advice: item.advice,
      })),
      synthesis: `${reading.tone.text}把这些牌连起来看，可以先从「${reading.focus.position.label}」这张牌给出的提示着手。`,
      ...(reading.comparison ? { comparison: {
        optionA: `${reading.comparison.optionA.label}：${reading.comparison.optionA.now.card.name}${reading.comparison.optionA.now.orientationLabel}走向${reading.comparison.optionA.future.card.name}${reading.comparison.optionA.future.orientationLabel}。`,
        optionB: `${reading.comparison.optionB.label}：${reading.comparison.optionB.now.card.name}${reading.comparison.optionB.now.orientationLabel}走向${reading.comparison.optionB.future.card.name}${reading.comparison.optionB.future.orientationLabel}。`,
        contrast: "两条路各有可借的力与需要留意的结，决定时请结合实际条件。",
      } } : {}),
      guidance: { actions: reading.cards.slice(0, 3).map(item => item.advice).concat(["写下此刻的感受，过一段时间再回看。"]).slice(0, 3), reflection: "这次占卜里，哪一句话让你停顿得最久？" },
      caution: catalogue.disclaimer,
    },
    createdAt: now.toISOString(), expiresAt: new Date(now.getTime() + 30 * 24 * 3600e3).toISOString(),
    ownerLabel: "本地模拟", viewerCanDelete: true,
  };
}
