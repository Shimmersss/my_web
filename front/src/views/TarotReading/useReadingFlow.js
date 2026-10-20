import { computed, reactive, ref } from "vue";
import catalogue from "@/data/tarotReading.json";
import { createTarotDraw, getCurrentMatchmakingTarot, pickTarotCards } from "@/api/tarotReading";
import { buildInstantReading, findSpread, findTopic, questionProblem, spreadProblem } from "@/utils/tarotReading";
import { preloadThemes, resolveTheme } from "@/data/tarotThemeLibrary";

export const RITUAL_STEPS = [
  { key: "question", label: "提问", eyebrow: "I · THE QUESTION", title: "静下心，写下你的问题", hint: "一个清楚的问题，会让牌面更容易回应你。" },
  { key: "spread", label: "牌阵", eyebrow: "II · THE SPREAD", title: "选择承载问题的牌阵", hint: "牌阵决定每张牌说话的位置。" },
  { key: "shuffling", label: "洗牌", eyebrow: "III · THE SHUFFLE", title: "洗牌与切牌", hint: "服务器正在为你洗一副新牌，牌序在你选牌前不会公开。" },
  { key: "picking", label: "抽牌", eyebrow: "IV · THE DRAW", title: "凭直觉，从牌堆中抽牌", hint: "依次点选牌背，第一张落在牌阵的第一个位置。" },
  { key: "revealing", label: "翻牌", eyebrow: "V · THE REVEAL", title: "依次翻开你的牌", hint: "每张牌会放大到眼前揭晓；翻开后也可点选牌面再次细看。" },
  { key: "reading", label: "解读", eyebrow: "VI · THE READING", title: "牌面的回声", hint: "先看即时牌义，需要时再请 AI 做一份深度解读。" },
];

/** State machine for one divination: question → spread → shuffle → pick → reveal → reading. */
export function useReadingFlow() {
  const phase = ref("question");
  const form = reactive({ topicId: "general", question: "", spreadId: "triangle", optionA: "", optionB: "" });
  const draw = ref(null);
  const picks = ref([]);
  const revealed = ref([]);
  const flyFrom = ref(null);
  const problem = ref("");
  const error = ref("");
  const drawReady = ref(false);
  const busy = ref(false);
  let generation = 0;

  const spread = computed(() => findSpread(catalogue, draw.value?.spreadId || form.spreadId));
  const topic = computed(() => findTopic(catalogue, draw.value?.topicId || form.topicId));
  const question = computed(() => draw.value?.question || form.question.trim());
  const boardCards = computed(() => {
    const cards = draw.value?.cards || [];
    if (!cards.length || !spread.value) return [];
    return spread.value.positions.map((position, index) => {
      const card = cards.find(item => item.position === position.id) || cards[index];
      return card ? { ...card, theme: resolveTheme({ ...card, position: position.id }, draw.value.drawId) } : null;
    });
  });
  const reading = computed(() => (draw.value?.cards?.length ? buildInstantReading({ catalogue, draw: draw.value, resolveTheme }) : null));
  /** Colour of the theme the sky should lean towards: the shared series if any, else the latest revealed card. */
  const aura = computed(() => {
    if (reading.value?.resonance && revealed.value.every(Boolean)) return reading.value.resonance.family.aura;
    const last = revealed.value.lastIndexOf(true);
    return last >= 0 ? boardCards.value[last]?.theme?.aura || "" : "";
  });
  const stepIndex = computed(() => RITUAL_STEPS.findIndex(step => step.key === phase.value));

  function toSpread() {
    problem.value = questionProblem(catalogue, form);
    if (!problem.value) phase.value = "spread";
  }

  function backToQuestion() {
    problem.value = "";
    phase.value = "question";
  }

  async function beginShuffle() {
    problem.value = spreadProblem(catalogue, form);
    if (problem.value || busy.value) return;
    const current = ++generation;
    error.value = "";
    draw.value = null;
    picks.value = [];
    revealed.value = [];
    drawReady.value = false;
    busy.value = true;
    phase.value = "shuffling";
    try {
      const response = await createTarotDraw({
        spreadId: form.spreadId,
        topicId: form.topicId,
        question: form.question.trim(),
        optionA: spread.value?.requiresOptions ? form.optionA.trim() : "",
        optionB: spread.value?.requiresOptions ? form.optionB.trim() : "",
      });
      if (current !== generation) return;
      draw.value = response.data;
      drawReady.value = true;
    } catch (e) {
      if (current !== generation) return;
      error.value = e.message || "洗牌没有成功，请稍后再试";
      phase.value = "spread";
    } finally {
      if (current === generation) busy.value = false;
    }
  }

  function finishShuffle() {
    if (draw.value) phase.value = "picking";
  }

  async function pick({ index, rect }) {
    const required = spread.value?.cardCount || 0;
    if (busy.value || !draw.value || picks.value.includes(index) || picks.value.length >= required) return;
    flyFrom.value = rect;
    picks.value = [...picks.value, index];
    if (picks.value.length < required) return;
    const current = generation;
    busy.value = true;
    error.value = "";
    try {
      const response = await pickTarotCards(draw.value.drawId, [...picks.value]);
      if (current !== generation) return;
      draw.value = response.data;
      preloadThemes(boardCards.value.map(card => card?.theme));
      revealed.value = spread.value.positions.map(() => false);
      phase.value = "revealing";
    } catch (e) {
      if (current !== generation) return;
      error.value = e.message || "选牌没有成功，请再选一次最后一张";
      busy.value = false;
      // A conflict means the server already holds a different state for this draw; trust it.
      if (e.status === 409 || e.status === 404) await restore().catch(() => {});
      else picks.value = picks.value.slice(0, -1);
    } finally {
      if (current === generation) busy.value = false;
    }
  }

  function reveal(order) {
    if (!boardCards.value[order] || revealed.value[order]) return;
    const next = [...revealed.value];
    next[order] = true;
    revealed.value = next;
    if (next.every(Boolean)) phase.value = "reading";
  }

  function revealNext() {
    const order = revealed.value.findIndex(value => !value);
    if (order >= 0) reveal(order);
  }

  function revealAll() {
    revealed.value = revealed.value.map(() => true);
    phase.value = "reading";
  }

  /** Starts over; a new question is expected, so the old wording and options are cleared. */
  function restart() {
    generation += 1;
    Object.assign(form, { question: "", optionA: "", optionB: "" });
    draw.value = null;
    picks.value = [];
    revealed.value = [];
    flyFrom.value = null;
    problem.value = "";
    error.value = "";
    busy.value = false;
    phase.value = "question";
  }

  /** Resumes an unfinished draw after a refresh or on another device. */
  async function restore() {
    const current = ++generation;
    const response = await getCurrentMatchmakingTarot();
    if (current !== generation) return;
    const saved = response.data?.draw;
    if (!saved?.spreadId || !findSpread(catalogue, saved.spreadId)) return;
    draw.value = saved;
    Object.assign(form, {
      topicId: saved.topicId || form.topicId,
      question: saved.question || "",
      spreadId: saved.spreadId,
      optionA: saved.options?.optionA || "",
      optionB: saved.options?.optionB || "",
    });
    const positions = findSpread(catalogue, saved.spreadId).positions;
    picks.value = saved.picks || (saved.cards || []).map(card => card.fanIndex);
    if (saved.state === "shuffled") {
      picks.value = [];
      phase.value = "picking";
    } else if (saved.state === "picked") {
      preloadThemes(boardCards.value.map(card => card?.theme));
      revealed.value = positions.map(() => false);
      phase.value = "revealing";
    } else {
      revealed.value = positions.map(() => true);
      phase.value = "reading";
    }
  }

  function markConsumed() {
    if (draw.value) draw.value = { ...draw.value, state: "consumed" };
  }

  return {
    catalogue, phase, form, draw, picks, revealed, flyFrom, problem, error, drawReady, busy,
    spread, topic, question, boardCards, reading, aura, stepIndex,
    toSpread, backToQuestion, beginShuffle, finishShuffle, pick, reveal, revealNext, revealAll, restart, restore, markConsumed,
  };
}
