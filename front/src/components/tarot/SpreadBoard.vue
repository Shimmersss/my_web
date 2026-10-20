<template>
  <div ref="board" class="spread-board" :class="[`spread-${spread.id}`, { complete }]" :style="{ height: `${layout.height + LABEL_SPACE}px` }">
    <svg class="constellation" :viewBox="`0 0 ${layout.width} ${layout.height}`" aria-hidden="true" focusable="false">
      <line
        v-for="link in links"
        :key="link.key"
        :x1="link.from.centerX" :y1="link.from.centerY" :x2="link.to.centerX" :y2="link.to.centerY"
        :class="{ lit: link.lit }"
      />
      <line
        v-for="link in complete ? links : []"
        :key="`surge-${link.key}`"
        class="surge"
        :x1="link.from.centerX" :y1="link.from.centerY" :x2="link.to.centerX" :y2="link.to.centerY"
      />
      <circle v-for="slot in layout.slots" :key="`star-${slot.id}`" :cx="slot.centerX" :cy="slot.centerY" r="2.4" :class="{ lit: slot.order < filled }" />
    </svg>
    <div
      v-for="slot in layout.slots"
      :key="slot.id"
      class="slot"
      :class="{ waiting: slot.order >= filled, shown: revealed[slot.order] && cards[slot.order] }"
      :style="{ left: `${slot.left}px`, top: `${slot.top}px`, width: `${layout.cardWidth}px`, height: `${layout.cardHeight}px`, '--aura': cards[slot.order]?.theme?.aura || '#e0be78' }"
    >
      <span class="slot-rune" aria-hidden="true"></span>
      <span class="slot-glow" aria-hidden="true"></span>
      <span v-if="slot.order >= filled" class="slot-outline"><b>{{ slot.order + 1 }}</b></span>
      <div v-else :ref="element => (slotCards[slot.order] = element)" class="slot-card">
        <TarotFlipCard
          :card="cards[slot.order] || null"
          :position-label="slot.label"
          :order="slot.order"
          :revealed="Boolean(revealed[slot.order])"
          :disabled="!interactive"
          @reveal="openCloseup(slot.order)"
        />
      </div>
      <span class="slot-label">
        {{ slot.label }}<template v-if="revealed[slot.order] && cards[slot.order]"> · <em :class="cards[slot.order].orientation">{{ orientationLabel(cards[slot.order].orientation) }}</em></template>
      </span>
    </div>
  </div>
  <CardRevealCloseup v-if="activeOrder !== null" :key="activeOrder" :show="closeupOpen" :card="cards[activeOrder]"
    :position-label="spread.positions[activeOrder].label" :order="activeOrder" :origin="closeupOrigin"
    :has-next="revealQueue.length > 0" @close="closeupOpen = false" @cancel="revealQueue = []; closeupOpen = false" @after-leave="finishCloseup" />
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import CardRevealCloseup from "./CardRevealCloseup.vue";
import TarotFlipCard from "./TarotFlipCard.vue";
import { spreadSlotLayout } from "@/utils/tarotLayout";
import { orientationLabel } from "@/utils/tarotReading";

const props = defineProps({
  spread: { type: Object, required: true },
  cards: { type: Array, default: () => [] },
  filled: { type: Number, default: 0 },
  revealed: { type: Array, default: () => [] },
  interactive: { type: Boolean, default: false },
  flyFrom: { type: Object, default: null },
  // Every card is face up: light starts travelling along the constellation.
  complete: { type: Boolean, default: false },
});
const emit = defineEmits(["reveal"]);
const activeOrder = ref(null), closeupOpen = ref(false), closeupOrigin = ref(null);
const revealQueue = ref([]);

function openCloseup(order) {
  if (!props.interactive || !props.cards[order] || activeOrder.value !== null) return;
  closeupOrigin.value = slotCards[order]?.getBoundingClientRect();
  activeOrder.value = order;
  closeupOpen.value = true;
  if (!props.revealed[order]) emit("reveal", order);
}
function finishCloseup() {
  activeOrder.value = null;
  const next = revealQueue.value.shift();
  if (next !== undefined) openCloseup(next);
}
function revealNext() {
  const next = props.revealed.findIndex(value => !value);
  if (next >= 0) openCloseup(next);
}
function revealAll() {
  if (activeOrder.value !== null) return;
  const orders = props.revealed.flatMap((value, index) => value ? [] : [index]);
  const first = orders.shift();
  revealQueue.value = orders;
  if (first !== undefined) openCloseup(first);
}
defineExpose({ revealNext, revealAll });

const LABEL_SPACE = 28;
const board = ref(null);
const width = ref(0);
const slotCards = [];
let observer = null;

const layout = computed(() => spreadSlotLayout(props.spread, Math.max(240, width.value || 560)));
const links = computed(() => {
  const byId = Object.fromEntries(layout.value.slots.map(slot => [slot.id, slot]));
  return props.spread.links.map(([from, to]) => ({
    key: `${from}-${to}`,
    from: byId[from],
    to: byId[to],
    lit: byId[from].order < props.filled && byId[to].order < props.filled,
  }));
});

// FLIP: the newest slot card starts where the chosen fan card was and glides into place.
watch(() => props.filled, async (now, before) => {
  if (now <= before || !props.flyFrom) return;
  await nextTick();
  const element = slotCards[now - 1];
  if (!element || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const target = element.getBoundingClientRect();
  const from = props.flyFrom;
  const dx = from.left + from.width / 2 - (target.left + target.width / 2);
  const dy = from.top + from.height / 2 - (target.top + target.height / 2);
  const scale = from.width / Math.max(1, target.width);
  element.animate(
    [
      { transform: `translate(${dx}px, ${dy}px) scale(${scale}) rotate(-8deg)`, opacity: 0.85 },
      { transform: `translate(${dx * 0.35}px, ${dy * 0.35 - 30}px) scale(${(scale + 1) / 2}) rotate(4deg)`, opacity: 1, offset: 0.55 },
      { transform: "none", opacity: 1 },
    ],
    { duration: 720, easing: "cubic-bezier(.2,.8,.2,1)" },
  );
});

onMounted(() => {
  width.value = board.value.clientWidth;
  observer = new ResizeObserver(([entry]) => { width.value = Math.round(entry.contentRect.width); });
  observer.observe(board.value);
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<style scoped>
.spread-board {
  position: relative;
  width: min(100%, 600px);
  margin: 0 auto;
}
.spread-single { width: min(100%, 520px); }
.spread-choice { width: min(100%, 900px); }
.constellation { position: absolute; left: 0; top: 0; width: 100%; height: calc(100% - 28px); overflow: visible; }
.constellation line {
  stroke: #e0be78;
  stroke-width: 1;
  stroke-dasharray: 3 6;
  opacity: .22;
  transition: opacity .6s ease;
}
.constellation line.lit { opacity: .75; stroke-dasharray: none; animation: line-shimmer 3.2s ease-in-out infinite; }
.constellation circle { fill: #e0be78; opacity: .3; }
.constellation circle.lit { opacity: .9; }
@keyframes line-shimmer { 50% { opacity: .4; } }
.constellation line.surge {
  stroke: #fff3cf;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-dasharray: 6 26;
  opacity: .9;
  animation: surge-flow 2.4s linear infinite;
}
@keyframes surge-flow { to { stroke-dashoffset: -64; } }

.slot { position: absolute; }
.slot-rune, .slot-glow { position: absolute; left: 50%; top: 50%; z-index: 0; pointer-events: none; }
.slot-rune {
  width: 128%;
  aspect-ratio: 1;
  border: 1px dashed #e0be7840;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  opacity: 0;
  transition: opacity .6s ease;
}
.slot-rune::before {
  content: "";
  position: absolute;
  inset: 9%;
  border: 1px solid #e0be7826;
  border-top-color: #f6dc9caa;
  border-radius: 50%;
}
.waiting .slot-rune { opacity: 1; animation: rune-spin 18s linear infinite; }
.waiting .slot-rune::before { animation: rune-spin-inner 6s linear infinite; }
@keyframes rune-spin { to { transform: translate(-50%, -50%) rotate(360deg); } }
@keyframes rune-spin-inner { to { transform: rotate(-360deg); } }
.slot-glow {
  width: 170%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: radial-gradient(circle, var(--aura) 0%, transparent 60%);
  transform: translate(-50%, -50%) scale(.6);
  opacity: 0;
  transition: opacity 1.2s ease .5s, transform 1.2s ease .5s;
}
.shown .slot-glow { opacity: .32; transform: translate(-50%, -50%) scale(1); }
.slot-outline {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border: 1px dashed #e0be7888;
  border-radius: 9px;
  background: radial-gradient(circle, #e0be7814, transparent 70%);
  color: #e0be78aa;
  font: 500 18px/1 "Moonlit Serif", serif;
}
.slot-card { position: relative; z-index: 1; width: 100%; height: 100%; }
.slot-label {
  position: absolute;
  left: 50%;
  top: calc(100% + 6px);
  width: max-content;
  max-width: 110%;
  transform: translateX(-50%);
  color: #e9d6a8;
  font-size: 12px;
  line-height: 1.35;
  text-align: center;
}
.slot-label em { font-style: normal; color: #f6dc9c; }
.slot-label em.reversed { color: #c8bcff; }

@media (prefers-reduced-motion: reduce) {
  .constellation line.lit, .constellation line.surge, .waiting .slot-rune, .waiting .slot-rune::before { animation: none; }
  .slot-glow { transition: none; }
}
</style>
