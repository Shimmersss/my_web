<template>
  <button
    type="button"
    class="tarot-flip-card"
    :class="{ turned, charging, reversed: card?.orientation === 'reversed' }"
    :style="{ '--aura': card?.theme?.aura || '#e9c983' }"
    :disabled="!card || disabled"
    
    :aria-label="label"
    @click="$emit('reveal')"
  >
    <span class="charge-halo" aria-hidden="true"></span>
    <span class="flip-inner">
      <span class="flip-face flip-back"><TarotCardBack :sheen="Boolean(card) && !turned" /></span>
      <span class="flip-face flip-front">
        <ThemedCardFace
          v-if="card && turned"
          compact
          @manifested="faceManifested"
          :theme="card.theme"
          :fallback-src="cardImage(card.cardId)"
          :reversed="card.orientation === 'reversed'"
          :manifest="animateFace"
          :alt="`${card.name}${card.theme ? ` · ${card.theme.themeName}` : ''}牌面`"
        />
      </span>
    </span>
    <span v-if="shockwave" :key="shockwave" class="shockwave" aria-hidden="true"></span>
    <GoldBurst :trigger="burst" :color="card?.theme?.aura || ''" />
  </button>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import TarotCardBack from "./TarotCardBack.vue";
import ThemedCardFace from "./ThemedCardFace.vue";
import GoldBurst from "./GoldBurst.vue";
import { cardImage, orientationLabel } from "@/utils/tarotReading";

const props = defineProps({
  card: { type: Object, default: null },
  positionLabel: { type: String, required: true },
  order: { type: Number, required: true },
  revealed: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["reveal", "manifested"]);

// Reveal sequence: the theme's light gathers (charging), the card turns, then the face manifests.
const CHARGE_MS = 520;
const turned = ref(props.revealed);
const charging = ref(false);
const animateFace = ref(false);
const burst = ref(0);
const shockwave = ref(0);
const timers = [];
let turnFinishesAt = 0;
function faceManifested() {
  const remaining = Math.max(0, turnFinishesAt - performance.now());
  if (remaining) timers.push(window.setTimeout(() => emit("manifested"), remaining));
  else emit("manifested");
}

watch(() => props.revealed, (now, before) => {
  if (!now || before !== false) { turned.value = now; return; }
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { turned.value = true; return; }
  turnFinishesAt = performance.now() + CHARGE_MS + 850;
  charging.value = true;
  timers.push(window.setTimeout(() => {
    charging.value = false;
    animateFace.value = true;
    turned.value = true;
  }, CHARGE_MS));
  timers.push(window.setTimeout(() => { burst.value += 1; shockwave.value += 1; }, CHARGE_MS + 420));
});
onBeforeUnmount(() => timers.forEach(window.clearTimeout));

const label = computed(() => {
  if (!props.card) return `${props.positionLabel}：等待选牌`;
  if (!props.revealed) return `揭示第 ${props.order + 1} 张牌：${props.positionLabel}`;
  const theme = props.card.theme ? `，${props.card.theme.themeName}主题` : "";
  return `放大查看，${props.positionLabel}：${props.card.name}，${orientationLabel(props.card.orientation)}${theme}`;
});
</script>

<style scoped>
.tarot-flip-card {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  perspective: 900px;
}
.tarot-flip-card:disabled, .tarot-flip-card.turned { cursor: default; }
.tarot-flip-card:focus-visible { outline: 3px solid #f1d18b; outline-offset: 4px; }
.tarot-flip-card:not(:disabled):not(.turned):not(.charging):hover .flip-inner { transform: translateY(-6px); }

.charge-halo {
  position: absolute;
  inset: -22%;
  border-radius: 50%;
  background: radial-gradient(circle, var(--aura) 0%, transparent 62%);
  opacity: 0;
  transform: scale(.6);
  pointer-events: none;
}
.charging .charge-halo { animation: halo-gather .52s ease-in forwards; }
.turned .charge-halo { opacity: .28; transform: scale(1); transition: opacity 1.4s ease; }
@keyframes halo-gather { to { opacity: .75; transform: scale(1); } }

.flip-inner {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  transform-style: preserve-3d;
  transition: transform .85s cubic-bezier(.3, .9, .25, 1);
}
.charging .flip-inner { animation: card-tremble .52s ease-in-out; }
@keyframes card-tremble {
  0% { transform: translateY(0); }
  30% { transform: translateY(-8px) rotate(-1.5deg); }
  55% { transform: translateY(-10px) rotate(1.5deg); }
  80% { transform: translateY(-9px) rotate(-1deg); }
  100% { transform: translateY(-8px) rotate(0); }
}
/* Cards turn from left to right, as the reading ritual asks, so orientation is never altered. */
.turned .flip-inner { transform: rotateY(-180deg); }
.flip-face {
  position: absolute;
  inset: 0;
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
  border-radius: 9px;
}
.flip-front { transform: rotateY(180deg); }
.turned .flip-front::after {
  content: "";
  position: absolute;
  inset: -4px;
  border-radius: 12px;
  border: 1px solid var(--aura);
  box-shadow: 0 0 24px var(--aura);
  opacity: 0;
  animation: front-glow 1.8s ease-out .4s forwards;
  pointer-events: none;
}
@keyframes front-glow { 30% { opacity: .9; } 100% { opacity: .5; } }

.shockwave {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 120%;
  aspect-ratio: 1;
  border: 2px solid var(--aura);
  border-radius: 50%;
  pointer-events: none;
  transform: translate(-50%, -50%) scale(.3);
  opacity: .9;
  animation: shockwave .9s cubic-bezier(.2, .7, .3, 1) forwards;
}
@keyframes shockwave { to { transform: translate(-50%, -50%) scale(1.9); opacity: 0; } }

@media (prefers-reduced-motion: reduce) {
  .flip-inner { transition: none; }
  .charging .flip-inner, .shockwave { animation: none; }
  .turned .flip-front::after { animation: none; opacity: .5; }
}
</style>
