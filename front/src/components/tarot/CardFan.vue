<template>
  <div class="card-fan-wrap">
    <div class="fan-toolbar">
      <span class="fan-count" aria-live="polite">已选 {{ picked.length }} / {{ picksRequired }} 张</span>
      <button class="tarot-button quiet" type="button" :disabled="disabled || remaining === 0" @click="pickRandom">随心选择一张</button>
    </div>
    <div ref="fan" class="card-fan" role="group" :aria-label="`牌堆共 ${count} 张，牌面朝下。用方向键移动，回车选择。`" :style="{ height: `${layout.height}px` }">
      <template v-if="width">
      <button
        v-for="item in layout.items"
        :key="item.index"
        :ref="element => (buttons[item.index] = element)"
        type="button"
        class="fan-card"
        :class="{ picked: pickedSet.has(item.index), dealt }"
        :style="{
          '--x': `${item.x}px`, '--y': `${item.y}px`, '--r': `${item.rotate}deg`, '--i': item.index,
          width: `${layout.cardWidth}px`, height: `${layout.cardHeight}px`, zIndex: item.index + 1 + item.row * 40,
        }"
        :tabindex="item.index === focusIndex ? 0 : -1"
        :disabled="disabled || pickedSet.has(item.index)"
        :aria-label="`第 ${item.index + 1} 张牌${pickedSet.has(item.index) ? '，已选择' : ''}`"
        @click="choose(item.index)"
        @keydown="onKey($event, item.index)"
        @focus="focusIndex = item.index"
      >
        <span class="fan-card-float"><span class="fan-card-face" :style="{ backgroundImage: CARD_BACK_URL }"></span></span>
        <span v-if="flaring === item.index" class="fan-flare" aria-hidden="true"></span>
      </button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { CARD_BACK_URL } from "./cardBack";
import { fanLayout } from "@/utils/tarotLayout";

const props = defineProps({
  count: { type: Number, default: 22 },
  picksRequired: { type: Number, required: true },
  picked: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["pick"]);

const fan = ref(null);
const width = ref(0);
const dealt = ref(false);
const focusIndex = ref(0);
const flaring = ref(-1);
let flareTimer = 0;
const buttons = [];
let observer = null, dealFrame = 0;

// Cards render only once the real width is known, so they never deal in from a guessed layout.
const layout = computed(() => fanLayout(props.count, width.value || 960));
const pickedSet = computed(() => new Set(props.picked));
const remaining = computed(() => props.picksRequired - props.picked.length);

function rectOf(index) {
  return buttons[index]?.querySelector(".fan-card-face")?.getBoundingClientRect() || null;
}

function choose(index) {
  if (props.disabled || pickedSet.value.has(index) || remaining.value <= 0) return;
  emit("pick", { index, rect: rectOf(index) });
  flaring.value = index;
  window.clearTimeout(flareTimer);
  flareTimer = window.setTimeout(() => { flaring.value = -1; }, 650);
  const next = nearestFree(index, 1) ?? nearestFree(index, -1);
  if (next != null) focusIndex.value = next;
}

function pickRandom() {
  const free = layout.value.items.map(item => item.index).filter(index => !pickedSet.value.has(index));
  if (!free.length) return;
  // Only chooses which face-down position to take; the card behind it is fixed by the server.
  choose(free[Math.floor(Math.random() * free.length)]);
}

function nearestFree(from, step) {
  for (let index = from + step; index >= 0 && index < props.count; index += step) {
    if (!pickedSet.value.has(index)) return index;
  }
  return null;
}

function onKey(event, index) {
  const keys = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
  let target = null;
  if (event.key in keys) target = nearestFree(index, keys[event.key]);
  else if (event.key === "Home") target = nearestFree(-1, 1);
  else if (event.key === "End") target = nearestFree(props.count, -1);
  else return;
  event.preventDefault();
  if (target == null) return;
  focusIndex.value = target;
  buttons[target]?.focus();
}

onMounted(() => {
  width.value = fan.value.clientWidth;
  observer = new ResizeObserver(([entry]) => { width.value = Math.round(entry.contentRect.width); });
  observer.observe(fan.value);
  focusIndex.value = nearestFree(-1, 1) ?? 0;
  // Two frames so the stacked start state paints before the cards fan out.
  dealFrame = requestAnimationFrame(() => { dealFrame = requestAnimationFrame(() => { dealt.value = true; }); });
});
onBeforeUnmount(() => { observer?.disconnect(); cancelAnimationFrame(dealFrame); window.clearTimeout(flareTimer); });
</script>

<style scoped>
.card-fan-wrap { display: grid; gap: 12px; }
.fan-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.fan-count { color: #f0dcae; font: 500 16px/1.4 "Moonlit Serif", serif; letter-spacing: .05em; }

.card-fan { position: relative; width: 100%; }
.fan-card {
  position: absolute;
  left: 0;
  top: 0;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  transform: translate(-50%, -50%) translate(var(--x), var(--y)) rotate(0deg) scale(.6);
  opacity: 0;
  transition: transform .7s cubic-bezier(.2, .8, .2, 1), opacity .5s ease;
  transition-delay: calc(var(--i) * 24ms);
}
.fan-card.dealt { transform: translate(-50%, -50%) translate(var(--x), var(--y)) rotate(var(--r)); opacity: 1; }
.fan-card-float {
  display: block;
  width: 100%;
  height: 100%;
  animation: fan-breathe 4.6s ease-in-out infinite;
  animation-delay: calc(var(--i) * -.41s);
}
@keyframes fan-breathe { 50% { transform: translateY(-5px); } }
.fan-card-face {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 8px;
  background-color: #10172b;
  background-size: cover;
  box-shadow: 0 4px 10px #03061299, inset 0 0 0 1px #f2d79a40;
  transition: transform .22s cubic-bezier(.2, .8, .2, 1);
}
.fan-card-face::after {
  content: "";
  position: absolute;
  inset: -3px;
  border-radius: 10px;
  border: 1px solid #ffe7a8;
  box-shadow: 0 0 18px #f6d58b99, 0 0 36px #a79bff55;
  opacity: 0;
  transition: opacity .22s ease;
}
.fan-card:not(:disabled):hover .fan-card-face,
.fan-card:focus-visible .fan-card-face { transform: translateY(-16px) scale(1.04); }
.fan-card:not(:disabled):hover .fan-card-face::after,
.fan-card:focus-visible .fan-card-face::after { opacity: 1; }
.fan-card-face::before {
  content: "✦";
  position: absolute;
  left: 50%;
  top: -22px;
  color: #fff1c4;
  font-size: 14px;
  line-height: 1;
  opacity: 0;
  transform: translate(-50%, 6px);
  transition: opacity .22s ease, transform .22s ease;
}
.fan-card:not(:disabled):hover .fan-card-face::before,
.fan-card:focus-visible .fan-card-face::before { opacity: 1; transform: translate(-50%, 0); }
.fan-flare {
  position: absolute;
  left: 50%;
  top: 40%;
  width: 180%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: radial-gradient(circle, #fff6d8 0%, #f3cf7a88 22%, transparent 62%);
  pointer-events: none;
  transform: translate(-50%, -50%) scale(.2);
  animation: fan-flare .65s ease-out forwards;
}
@keyframes fan-flare { 40% { opacity: 1; } to { transform: translate(-50%, -50%) scale(1.4); opacity: 0; } }
.fan-card:focus-visible { outline: none; }
.fan-card:disabled { cursor: default; }
.fan-card.picked .fan-card-face { opacity: .16; transform: translateY(-8px); }

@media (prefers-reduced-motion: reduce) {
  .fan-card, .fan-card-face, .fan-card-face::after, .fan-card-face::before { transition: none; }
  .fan-card-float, .fan-flare { animation: none; }
}
</style>
