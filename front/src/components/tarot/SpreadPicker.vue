<template>
  <form class="spread-picker" @submit.prevent="$emit('next')">
    <fieldset>
      <legend class="sr-only">选择牌阵</legend>
      <div class="spread-options">
        <label v-for="spread in catalogue.spreads" :key="spread.id" class="spread-option" :class="{ selected: spreadId === spread.id }">
          <input type="radio" name="tarot-spread" :value="spread.id" :checked="spreadId === spread.id" @change="$emit('update:spreadId', spread.id)" />
          <svg class="spread-diagram" :viewBox="`0 0 100 ${diagramHeight(spread)}`" aria-hidden="true">
            <line v-for="[from, to] in spread.links" :key="`${from}-${to}`" :x1="point(spread, from).x" :y1="point(spread, from).y" :x2="point(spread, to).x" :y2="point(spread, to).y" />
            <g v-for="(position, index) in spread.positions" :key="position.id" :style="{ '--i': index }" class="diagram-card">
              <rect :x="point(spread, position.id).x - 7" :y="point(spread, position.id).y - 10.5" width="14" height="21" rx="2" />
              <text :x="point(spread, position.id).x" :y="point(spread, position.id).y + 3">{{ index + 1 }}</text>
            </g>
          </svg>
          <span class="spread-name">{{ spread.name }} <small>{{ spread.cardCount }} 张</small></span>
          <span class="spread-description">{{ spread.description }}</span>
          <span class="spread-positions">{{ spread.positions.map(position => position.label).join(" ／ ") }}</span>
        </label>
      </div>
    </fieldset>

    <div v-if="selected?.requiresOptions" class="option-fields">
      <label>选择一<input :value="optionA" :maxlength="catalogue.limits.optionMax" placeholder="例如：留在现在的城市" @input="$emit('update:optionA', $event.target.value)" /></label>
      <label>选择二<input :value="optionB" :maxlength="catalogue.limits.optionMax" placeholder="例如：去新的城市发展" @input="$emit('update:optionB', $event.target.value)" /></label>
    </div>

    <p v-if="problem" class="field-problem" role="status">{{ problem }}</p>
    <div class="stage-actions">
      <button class="tarot-button quiet" type="button" @click="$emit('back')"><span aria-hidden="true">←</span> 修改问题</button>
      <button class="tarot-button primary" type="submit" :disabled="busy">{{ busy ? "正在准备牌…" : "开始洗牌" }}</button>
    </div>
  </form>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  catalogue: { type: Object, required: true },
  spreadId: { type: String, default: "" },
  optionA: { type: String, default: "" },
  optionB: { type: String, default: "" },
  problem: { type: String, default: "" },
  busy: { type: Boolean, default: false },
});
defineEmits(["update:spreadId", "update:optionA", "update:optionB", "next", "back"]);

const selected = computed(() => props.catalogue.spreads.find(spread => spread.id === props.spreadId));

function diagramHeight(spread) {
  const [w, h] = spread.aspect.split("/").map(Number);
  return Math.round(100 * (h / w));
}
function point(spread, id) {
  const position = spread.positions.find(item => item.id === id);
  return { x: position.x, y: (position.y / 100) * diagramHeight(spread) };
}
</script>

<style scoped>
fieldset { margin: 0; padding: 0; border: 0; min-width: 0; }
.spread-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.spread-option {
  position: relative;
  display: grid;
  align-content: start;
  gap: 8px;
  padding: 18px;
  border: 1px solid #8d7a5866;
  border-radius: 16px;
  background: linear-gradient(160deg, #ffffff0a, #ffffff03);
  cursor: pointer;
  transition: border-color .2s ease, transform .25s ease, background-color .2s ease;
}
.spread-option:hover { transform: translateY(-3px); }
.spread-option.selected { border-color: #e2c07b; background-color: #e2c07b14; }
.spread-option:focus-within { outline: 2px solid #f1d18b; outline-offset: 3px; }
.spread-option input { position: absolute; opacity: 0; pointer-events: none; }
.spread-diagram { width: min(170px, 100%); height: auto; margin: 0 auto 6px; overflow: visible; }
.spread-diagram line { stroke: #e0be78; stroke-width: .6; stroke-dasharray: 2 2; opacity: .55; }
.diagram-card rect { fill: #1a2245; stroke: #e0be78; stroke-width: .9; transition: fill .3s ease; }
.diagram-card text { fill: #f3dca2; font: 700 7px/1 system-ui, sans-serif; text-anchor: middle; }
.selected .diagram-card { animation: diagram-glow 2.4s ease-in-out infinite; animation-delay: calc(var(--i) * .25s); }
.selected .diagram-card rect { fill: #33406f; }
@keyframes diagram-glow { 50% { opacity: .55; } }
.spread-name { color: #fff4dc; font: 500 21px/1.3 "Moonlit Serif", serif; }
.spread-name small { margin-left: 6px; color: #d7b96f; font: 600 12px system-ui, sans-serif; }
.spread-description { color: #b9bdcc; font-size: 13px; line-height: 1.7; }
.spread-positions { color: #e0c58a; font-size: 12px; letter-spacing: .04em; }

.option-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 18px; }
.option-fields label { display: grid; gap: 7px; color: #f6e7c4; font-weight: 700; font-size: 14px; }
.option-fields input {
  min-width: 0;
  padding: 12px 13px;
  border: 1px solid #b8975c88;
  border-radius: 10px;
  background: #0b1124cc;
  color: #fff8e8;
  font: inherit;
  font-weight: 400;
}
.option-fields input:focus { outline: 2px solid #f1d18b; outline-offset: 1px; }
.field-problem { margin: 14px 0 0; color: #f2b9a8; font-size: 13px; }

@media (max-width: 860px) {
  .spread-options { grid-template-columns: 1fr; }
  .spread-option { grid-template-columns: 96px minmax(0, 1fr); column-gap: 14px; }
  .spread-diagram { grid-row: 1 / span 3; width: 96px; margin: 0; }
}
@media (max-width: 420px) {
  .spread-option { grid-template-columns: 1fr; padding: 15px; }
  .spread-diagram { grid-row: auto; width: 120px; margin: 0 auto; }
  .option-fields { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .spread-option { transition: none; }
  .selected .diagram-card { animation: none; }
}
</style>
