<template>
  <article class="tarot-report">
    <header class="report-hero tarot-panel">
      <router-link class="tarot-text-button back-link" to="/tarot-reading"><span aria-hidden="true">←</span> 返回月下占卜</router-link>
      <p class="tarot-eyebrow">MOONLIT DIVINATION · {{ report.spread?.name }}</p>
      <h1>{{ reading.title || "占卜报告" }}</h1>
      <blockquote>「{{ report.question }}」</blockquote>
      <p class="report-meta">{{ report.spread?.name }} · {{ report.topic?.label }} · {{ formatDate(report.createdAt) }}<template v-if="report.expiresAt"> · 保存至 {{ formatDate(report.expiresAt, false) }}</template></p>
      <button v-if="report.viewerCanDelete" class="tarot-text-button danger delete-button" type="button" @click="$emit('delete')">删除本报告</button>
    </header>

    <section v-if="spread" class="tarot-panel report-board" aria-label="牌阵">
      <SpreadBoard :spread="spread" :cards="boardCards" :filled="spread.cardCount" :revealed="boardCards.map(() => true)" complete />
    </section>

    <section v-if="reading.overview" class="tarot-panel report-section" aria-labelledby="report-overview">
      <p class="tarot-eyebrow">OVERVIEW</p>
      <h2 id="report-overview">整体意象</h2>
      <p class="lead">{{ reading.overview }}</p>
      <p v-if="resonance" class="resonance" :style="{ '--aura': resonance.family.aura }"><strong>系列共鸣</strong>{{ resonance.text }}</p>
    </section>

    <section class="report-cards" aria-labelledby="report-cards-title">
      <h2 id="report-cards-title" class="sr-only">逐张解读</h2>
      <article v-for="(item, index) in cardReadings" :key="item.position" class="tarot-panel report-card" :style="{ '--i': index, '--aura': item.theme?.aura || '#e0be78' }">
        <div class="report-card-art">
          <ThemedCardFace :theme="item.theme" :fallback-src="cardImage(item.cardId)" :reversed="item.orientation === 'reversed'" :alt="`${item.name}${item.theme ? ` · ${item.theme.themeName}` : ''}牌面`" :title="item.name" :subtitle="item.theme ? `${item.theme.themeName} · ${orientationLabel(item.orientation)}` : orientationLabel(item.orientation)" />
        </div>
        <div class="report-card-copy">
          <p class="tarot-eyebrow">{{ index + 1 }} · {{ item.positionLabel }}</p>
          <h3>{{ item.name }} <span class="orientation-chip" :class="item.orientation">{{ orientationLabel(item.orientation) }}</span></h3>
          <p v-if="item.theme" class="theme-badge">{{ item.theme.family.label }} · {{ item.theme.themeName }}</p>
          <ul v-if="item.keywords?.length" class="keyword-list"><li v-for="keyword in item.keywords" :key="keyword">{{ keyword }}</li></ul>
          <p>{{ item.interpretation }}</p>
          <p v-if="item.advice" class="advice"><strong>建议</strong>{{ item.advice }}</p>
          <p v-if="item.themeMeaning" class="theme-meaning"><strong>主题寓意</strong>{{ item.themeMeaning }}</p>
        </div>
      </article>
    </section>

    <section v-if="reading.synthesis" class="tarot-panel report-section" aria-labelledby="report-synthesis">
      <p class="tarot-eyebrow">SYNTHESIS</p>
      <h2 id="report-synthesis">牌与牌之间</h2>
      <p class="lead">{{ reading.synthesis }}</p>
    </section>

    <section v-if="reading.comparison" class="tarot-panel report-section" aria-labelledby="report-comparison">
      <p class="tarot-eyebrow">TWO PATHS</p>
      <h2 id="report-comparison">两条路的对照</h2>
      <div class="comparison-grid">
        <article><h3>{{ report.options?.optionA || "选择一" }}</h3><p>{{ reading.comparison.optionA }}</p></article>
        <article><h3>{{ report.options?.optionB || "选择二" }}</h3><p>{{ reading.comparison.optionB }}</p></article>
      </div>
      <p class="lead">{{ reading.comparison.contrast }}</p>
    </section>

    <section v-if="reading.guidance" class="tarot-panel report-section" aria-labelledby="report-guidance">
      <p class="tarot-eyebrow">GUIDANCE</p>
      <h2 id="report-guidance">接下来可以做的事</h2>
      <ol class="action-list"><li v-for="action in reading.guidance.actions || []" :key="action">{{ action }}</li></ol>
      <p v-if="reading.guidance.reflection" class="reflection"><strong>留给自己的问题</strong>{{ reading.guidance.reflection }}</p>
    </section>

    <section class="tarot-panel report-section caution" aria-label="提醒">
      <p v-if="reading.caution">{{ reading.caution }}</p>
      <p class="tarot-disclaimer">{{ catalogue.disclaimer }}</p>
    </section>
  </article>
</template>

<script setup>
import { computed } from "vue";
import catalogue from "@/data/tarotReading.json";
import SpreadBoard from "@/components/tarot/SpreadBoard.vue";
import ThemedCardFace from "@/components/tarot/ThemedCardFace.vue";
import { resolveTheme } from "@/data/tarotThemeLibrary";
import { cardImage, findSpread, orientationLabel } from "@/utils/tarotReading";
import { themeResonance } from "@/utils/tarotThemes";

const props = defineProps({ report: { type: Object, required: true } });
defineEmits(["delete"]);

const reading = computed(() => props.report.reading || {});
const spread = computed(() => props.report.spread?.positions ? props.report.spread : findSpread(catalogue, props.report.spread?.id));
// Older payloads may lack themeId; the resolver then picks a stable theme from the report id.
const boardCards = computed(() => (props.report.cards || []).map(card => ({ ...card, theme: resolveTheme(card, props.report.id) })));
const resonance = computed(() => themeResonance(boardCards.value.map(card => card.theme)));
// The report embeds card snapshots, so it still reads correctly if the catalogue text changes later.
const cardReadings = computed(() => boardCards.value.map((card, index) => {
  const written = (reading.value.cards || []).find(item => item.position === card.position) || reading.value.cards?.[index] || {};
  return {
    ...card,
    interpretation: written.interpretation || card.meaning,
    advice: written.advice || card.advice,
    themeMeaning: card.themeMeaning || card.theme?.meaning || "",
  };
}));

function formatDate(value, withTime = true) {
  if (!value) return "—";
  const options = withTime ? { dateStyle: "long", timeStyle: "short" } : { dateStyle: "long" };
  return new Intl.DateTimeFormat("zh-CN", options).format(new Date(value));
}
</script>

<style scoped>
.tarot-report { display: grid; gap: 18px; }
.report-hero { position: relative; display: grid; gap: 4px; padding: 34px 38px; background: radial-gradient(circle at 85% 20%, #6e5aa640, transparent 45%), linear-gradient(125deg, #0f1631e6, #2a2142e6); }
.back-link { justify-self: start; margin-bottom: 14px; text-decoration: none; }
.report-hero h1 { margin: 0; color: #fff6e0; font: 500 clamp(30px, 4.6vw, 46px)/1.2 "Moonlit Serif", serif; }
.report-hero blockquote { margin: 14px 0 0; padding: 12px 16px; border-left: 2px solid #e0be78; background: #ffffff08; color: #f1e3c2; font: 500 18px/1.7 "Moonlit Serif", serif; }
.report-meta { margin: 12px 0 0; color: #9ea4b8; font-size: 13px; }
.delete-button { position: absolute; top: 34px; right: 38px; }
.report-board { padding-bottom: 20px; }

.report-section h2 { margin: 0 0 12px; color: #fff4dc; font: 500 24px/1.3 "Moonlit Serif", serif; }
.report-section .lead { margin: 0; color: #d4d6e0; line-height: 1.95; }

.report-cards { display: grid; gap: 14px; }
.report-card { display: grid; grid-template-columns: 180px minmax(0, 1fr); gap: 26px; align-items: start; animation: report-rise .7s cubic-bezier(.2, .8, .2, 1) both; animation-delay: calc(var(--i) * 110ms); }
@keyframes report-rise { from { opacity: 0; transform: translateY(14px); } }
.report-card-art { position: relative; isolation: isolate; width: 180px; aspect-ratio: 2 / 3; }
.report-card-art::before { content: ""; position: absolute; inset: -14%; z-index: -1; border-radius: 50%; background: radial-gradient(circle, var(--aura), transparent 66%); opacity: .28; }
.theme-badge { display: inline-block; margin: 10px 0 0; padding: 2px 10px; border: 1px solid var(--aura); border-radius: 99px; color: var(--aura); font-size: 12px; letter-spacing: .06em; }
.theme-meaning { padding: 10px 12px; border-left: 2px solid var(--aura); background: #ffffff06; color: #d9d4ea !important; }
.theme-meaning strong, .resonance strong { margin-right: 8px; color: var(--aura); }
.resonance { margin: 14px 0 0; padding: 12px 16px; border: 1px solid var(--aura); border-radius: 14px; color: #e9e3f4; line-height: 1.7; }
.report-card-copy h3 { display: flex; align-items: center; gap: 10px; margin: 0; color: #fff4dc; font: 500 26px/1.3 "Moonlit Serif", serif; }
.orientation-chip { padding: 2px 9px; border-radius: 99px; background: #e0be7826; color: #f6dc9c; font: 700 12px/1.6 system-ui, sans-serif; }
.orientation-chip.reversed { background: #b7a8ff26; color: #d6ccff; }
.keyword-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0 0; padding: 0; list-style: none; }
.keyword-list li { padding: 3px 9px; border: 1px solid #ffffff1f; border-radius: 99px; color: #e3dccb; font-size: 12px; }
.report-card-copy > p { margin: 12px 0 0; color: #d4d6e0; line-height: 1.9; }
.advice { padding: 11px 13px; border-radius: 10px; background: #e0be7812; color: #f1e3c2 !important; }
.advice strong, .reflection strong { margin-right: 8px; color: #e0be78; }

.comparison-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.comparison-grid article { padding: 16px 18px; border: 1px solid #8d7a5866; border-radius: 14px; background: #ffffff07; }
.comparison-grid h3 { margin: 0 0 8px; color: #fff4dc; font: 500 19px/1.4 "Moonlit Serif", serif; }
.comparison-grid p { margin: 0; color: #d4d6e0; line-height: 1.85; }
.action-list { margin: 0; padding-left: 22px; color: #e9e0cc; line-height: 2; }
.reflection { margin: 14px 0 0; padding: 12px 14px; border-radius: 10px; background: #b7a8ff14; color: #e3defa; line-height: 1.8; }
.caution p:not(.tarot-disclaimer) { margin: 0 0 8px; color: #e7d3a5; line-height: 1.8; }

@media (max-width: 760px) {
  .report-hero { padding: 26px 20px; }
  .delete-button { position: static; justify-self: start; margin-top: 12px; }
  .report-card { grid-template-columns: 1fr; gap: 16px; }
  .report-card-art { width: min(180px, 60vw); margin: 0 auto; }
  .comparison-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) { .report-card { animation: none; } }
</style>
