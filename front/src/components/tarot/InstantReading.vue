<template>
  <section class="instant-reading" aria-labelledby="instant-reading-title">
    <header class="reading-header">
      <p class="tarot-eyebrow">THE CARDS HAVE SPOKEN</p>
      <h3 id="instant-reading-title">{{ reading.spread.name }} · {{ reading.topic.label }}</h3>
      <blockquote>「{{ reading.question }}」</blockquote>
      <p class="tone"><span class="tone-badge" :class="reading.tone.key">{{ reading.tone.label }}</span>{{ reading.summary }}</p>
      <p v-if="reading.resonance" class="resonance" :style="{ '--aura': reading.resonance.family.aura }">
        <span class="resonance-mark" aria-hidden="true">✧</span><strong>系列共鸣</strong>{{ reading.resonance.text }}
      </p>
    </header>

    <div v-if="reading.comparison" class="comparison-grid">
      <article v-for="side in [reading.comparison.optionA, reading.comparison.optionB]" :key="side.label" class="comparison-card">
        <h4>{{ side.label }}</h4>
        <p><span>现状</span>{{ side.now.card.name }} · {{ side.now.orientationLabel }} —— {{ side.now.keywords.slice(0, 2).join("、") }}</p>
        <p><span>发展</span>{{ side.future.card.name }} · {{ side.future.orientationLabel }} —— {{ side.future.keywords.slice(0, 2).join("、") }}</p>
      </article>
    </div>

    <ol class="reading-cards">
      <li v-for="(item, index) in reading.cards" :key="item.position.id" :style="{ '--i': index, '--aura': item.theme?.aura || '#e0be78' }">
        <div class="reading-card-art">
          <ThemedCardFace :theme="item.theme" :fallback-src="item.image" :reversed="item.orientation === 'reversed'" :alt="`${item.card.name}${item.theme ? ` · ${item.theme.themeName}` : ''}牌面`" />
        </div>
        <div class="reading-card-copy">
          <p class="position-line"><b>{{ index + 1 }} · {{ item.position.label }}</b><small>{{ item.position.meaning }}</small></p>
          <h4>
            {{ item.card.name }}
            <span class="orientation-chip" :class="item.orientation">{{ item.orientationLabel }}</span>
          </h4>
          <p class="card-subline">{{ item.card.numeral }} · {{ item.card.nameEn }} · {{ item.card.correspondence }}</p>
          <p v-if="item.theme" class="theme-line"><span class="theme-badge">{{ item.theme.family.label }} · {{ item.theme.themeName }}</span></p>
          <ul class="keyword-list"><li v-for="keyword in item.keywords" :key="keyword">{{ keyword }}</li></ul>
          <p>{{ item.meaning }}</p>
          <p class="advice"><strong>建议</strong>{{ item.advice }}</p>
          <p v-if="item.theme?.meaning" class="theme-meaning"><strong>主题寓意</strong>{{ item.theme.meaning }}</p>
        </div>
      </li>
    </ol>

    <div class="ai-cta">
      <div>
        <p class="tarot-eyebrow">DEEPER READING</p>
        <h4>AI 深度解读</h4>
        <p>结合你的问题、牌位与正逆位，生成一份完整的占卜报告，保存在占卜记录里 {{ status.retentionDays || 30 }} 天。</p>
        <small v-if="!status.tarotAiEnabled">AI 深度解读暂未开放，上面的即时牌义可以继续参考。</small>
      </div>
      <button class="tarot-button primary" type="button" :disabled="!status.tarotAiEnabled || busy || locked" @click="$emit('request-ai')">
        {{ locked ? "解读已提交" : busy ? "正在提交…" : trial ? "生成解读 · 内测免费" : `生成解读 · ${status.creditCost} 积分` }}
      </button>
    </div>

    <p class="tarot-disclaimer">{{ disclaimer }}</p>
    <div class="stage-actions">
      <button class="tarot-button quiet" type="button" @click="$emit('restart')">重新提问</button>
    </div>
  </section>
</template>

<script setup>
import ThemedCardFace from "./ThemedCardFace.vue";

defineProps({
  reading: { type: Object, required: true },
  status: { type: Object, required: true },
  disclaimer: { type: String, default: "" },
  trial: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  locked: { type: Boolean, default: false },
});
defineEmits(["request-ai", "restart"]);
</script>

<style scoped>
.instant-reading { display: grid; gap: 22px; }
.reading-header h3 { margin: 0; color: #fff4dc; font: 500 28px/1.3 "Moonlit Serif", serif; }
.reading-header blockquote { margin: 12px 0 0; padding: 12px 16px; border-left: 2px solid #e0be78; background: #ffffff08; color: #f1e3c2; font: 500 17px/1.7 "Moonlit Serif", serif; }
.tone { margin: 14px 0 0; color: #c7cbd8; line-height: 1.8; }
.tone-badge { display: inline-block; margin-right: 10px; padding: 2px 10px; border: 1px solid #e0be78; border-radius: 99px; color: #f6dc9c; font-size: 12px; letter-spacing: .1em; }
.tone-badge.tangled, .tone-badge.inward { border-color: #b7a8ff; color: #d6ccff; }

.resonance {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  margin: 14px 0 0;
  padding: 12px 16px;
  border: 1px solid var(--aura);
  border-radius: 14px;
  background: radial-gradient(circle at 0 50%, color-mix(in srgb, var(--aura) 22%, transparent), transparent 70%);
  color: #e9e3f4;
  line-height: 1.7;
}
.resonance strong { color: var(--aura); letter-spacing: .1em; }
.resonance-mark { color: var(--aura); animation: resonance-pulse 2.6s ease-in-out infinite; }
@keyframes resonance-pulse { 50% { opacity: .35; } }
.comparison-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.comparison-card { padding: 16px 18px; border: 1px solid #8d7a5866; border-radius: 14px; background: #ffffff07; }
.comparison-card h4 { margin: 0 0 8px; color: #fff4dc; font: 500 19px/1.4 "Moonlit Serif", serif; }
.comparison-card p { margin: 6px 0 0; color: #c7cbd8; font-size: 13px; line-height: 1.7; }
.comparison-card span { margin-right: 8px; color: #e0be78; font-weight: 700; }

.reading-cards { display: grid; gap: 14px; margin: 0; padding: 0; list-style: none; }
.reading-cards > li {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 20px;
  padding: 18px;
  border: 1px solid #8d7a5855;
  border-radius: 16px;
  background: linear-gradient(140deg, #ffffff0b, #ffffff03);
  animation: reading-rise .7s cubic-bezier(.2, .8, .2, 1) both;
  animation-delay: calc(var(--i) * 120ms);
}
@keyframes reading-rise { from { opacity: 0; transform: translateY(14px); } }
.reading-card-art { position: relative; isolation: isolate; width: 112px; aspect-ratio: 2 / 3; }
.reading-card-art::before { content: ""; position: absolute; inset: -14%; z-index: -1; border-radius: 50%; background: radial-gradient(circle, var(--aura), transparent 66%); opacity: .3; }
.theme-line { margin: 8px 0 0; }
.theme-badge { display: inline-block; padding: 2px 10px; border: 1px solid var(--aura); border-radius: 99px; color: var(--aura); font-size: 12px; letter-spacing: .06em; }
.theme-meaning { padding: 10px 12px; border-left: 2px solid var(--aura); background: #ffffff06; color: #d9d4ea !important; }
.theme-meaning strong { margin-right: 8px; color: var(--aura); }
.position-line { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; margin: 0; }
.position-line b { color: #e0be78; font-size: 13px; letter-spacing: .08em; }
.position-line small { color: #9ea4b8; font-size: 12px; }
.reading-card-copy h4 { display: flex; align-items: center; gap: 10px; margin: 8px 0 2px; color: #fff4dc; font: 500 23px/1.3 "Moonlit Serif", serif; }
.orientation-chip { padding: 2px 9px; border-radius: 99px; background: #e0be7826; color: #f6dc9c; font: 700 12px/1.6 system-ui, sans-serif; }
.orientation-chip.reversed { background: #b7a8ff26; color: #d6ccff; }
.card-subline { margin: 0; color: #9ea4b8; font-size: 12px; letter-spacing: .04em; }
.keyword-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0 0; padding: 0; list-style: none; }
.keyword-list li { padding: 3px 9px; border: 1px solid #ffffff1f; border-radius: 99px; color: #e3dccb; font-size: 12px; }
.reading-card-copy > p { margin: 10px 0 0; color: #d4d6e0; line-height: 1.85; }
.reading-card-copy .advice { padding: 10px 12px; border-radius: 10px; background: #e0be7812; color: #f1e3c2; }
.advice strong { margin-right: 8px; color: #e0be78; }

.ai-cta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 22px;
  padding: 22px;
  border: 1px solid #e0be7866;
  border-radius: 18px;
  background: radial-gradient(circle at 92% 10%, #8f7cff2a, transparent 40%), linear-gradient(130deg, #1c2446, #2b2342);
}
.ai-cta h4 { margin: 0; color: #fff4dc; font: 500 22px/1.3 "Moonlit Serif", serif; }
.ai-cta p:not(.tarot-eyebrow) { margin: 8px 0 0; color: #c7cbd8; line-height: 1.7; font-size: 14px; }
.ai-cta small { display: block; margin-top: 8px; color: #f2c9a8; }
.ai-cta .tarot-button { flex: 0 0 auto; }

@media (max-width: 760px) {
  .comparison-grid { grid-template-columns: 1fr; }
  .reading-cards > li { grid-template-columns: 76px minmax(0, 1fr); gap: 14px; padding: 14px; }
  .reading-card-art { width: 76px; }
  .reading-card-copy h4 { font-size: 20px; }
  .ai-cta { flex-direction: column; align-items: stretch; }
}
@media (max-width: 360px) {
  .reading-cards > li { grid-template-columns: 1fr; }
  .reading-card-art { width: 96px; }
}
@media (prefers-reduced-motion: reduce) {
  .reading-cards > li, .resonance-mark { animation: none; }
}
</style>
