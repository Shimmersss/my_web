<template>
  <div class="shuffle-ritual" :class="phase">
    <div class="shuffle-stage" aria-hidden="true">
      <MagicCircle class="stage-circle" :flare="phase === 'cut' || phase === 'settle'" />
      <span v-for="index in CARD_COUNT" :key="index" class="shuffle-card" :style="cardStyle(index - 1)">
        <TarotCardBack :sheen="false" />
      </span>
    </div>
    <p class="shuffle-status" role="status" aria-live="polite">{{ statusText }}</p>
    <button v-if="!reduced && phase !== 'settle'" class="tarot-button quiet skip" type="button" @click="skip">跳过动画</button>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import TarotCardBack from "./TarotCardBack.vue";
import MagicCircle from "./MagicCircle.vue";

const props = defineProps({ ready: { type: Boolean, default: false } });
const emit = defineEmits(["done"]);

const CARD_COUNT = 9;
// The deck swirls around the circle (shuffle), is cut into three piles (as the ritual describes), then restacks.
const TIMELINE = [["vortex", 0], ["cut", 1700], ["settle", 3100]];
const phase = ref("vortex");
const reduced = ref(false);
let timers = [];
let finished = false;

const statusText = computed(() => {
  if (phase.value === "vortex") return "牌在星涡中流转，心里默念你的问题…";
  if (phase.value === "cut") return "切牌：分成三叠，再合而为一…";
  return props.ready ? "牌已洗好，命运静候你的手" : "等待月光回应…";
});

function cardStyle(index) {
  return {
    "--i": index,
    "--angle": `${index * (360 / CARD_COUNT)}deg`,
    "--pile": (index % 3) - 1,
    "--stack": `${(index - CARD_COUNT / 2) * 0.6}px`,
  };
}

function finish() {
  if (finished || phase.value !== "settle" || !props.ready) return;
  finished = true;
  timers.push(window.setTimeout(() => emit("done"), reduced.value ? 0 : 600));
}

function skip() {
  timers.forEach(window.clearTimeout);
  timers = [];
  phase.value = "settle";
  finish();
}

onMounted(() => {
  reduced.value = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduced.value) { phase.value = "settle"; finish(); return; }
  TIMELINE.slice(1).forEach(([name, at]) => {
    timers.push(window.setTimeout(() => { phase.value = name; finish(); }, at));
  });
});

watch(() => props.ready, finish);
onBeforeUnmount(() => timers.forEach(window.clearTimeout));
</script>

<style scoped>
.shuffle-ritual { display: grid; justify-items: center; gap: 18px; padding: 10px 0 6px; }
.shuffle-stage {
  --orbit: 112px;
  --cut-x: 116px;
  position: relative;
  width: min(420px, 100%);
  height: 380px;
}
.stage-circle {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 300px;
  transform: translate(-50%, -50%);
  opacity: .85;
}

.shuffle-card {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 90px;
  height: 135px;
  margin: -67px 0 0 -45px;
  transform: translateY(var(--stack));
  will-change: transform;
}
/* Matching transform lists keep each keyframe interpolating along a circle, not a straight line. */
.vortex .shuffle-card { animation: vortex 1.7s cubic-bezier(.45, .05, .35, 1) both; animation-delay: calc(var(--i) * 22ms); }
.cut .shuffle-card { animation: cut 1.4s cubic-bezier(.5, 0, .25, 1) both; animation-delay: calc(var(--i) * 18ms); }
.settle .shuffle-card { animation: settle .55s ease-out both; }

@keyframes vortex {
  0% { transform: rotate(var(--angle)) translateY(0) rotate(calc(var(--angle) * -1)) translateY(var(--stack)); }
  40% { transform: rotate(calc(var(--angle) + 220deg)) translateY(calc(var(--orbit) * -1)) rotate(calc(var(--angle) * -1 - 200deg)) translateY(var(--stack)); }
  75% { transform: rotate(calc(var(--angle) + 430deg)) translateY(calc(var(--orbit) * -.8)) rotate(calc(var(--angle) * -1 - 400deg)) translateY(var(--stack)); }
  100% { transform: rotate(calc(var(--angle) + 540deg)) translateY(0) rotate(calc(var(--angle) * -1 - 540deg)) translateY(var(--stack)); }
}
@keyframes cut {
  0% { transform: translate3d(0, var(--stack), 0); }
  35%, 60% { transform: translate3d(calc(var(--pile) * var(--cut-x)), calc(var(--stack) + 10px), 0) rotate(calc(var(--pile) * 3deg)); }
  78% { transform: translate3d(calc(var(--pile) * -40px), calc(var(--stack) - 18px), 0); }
  100% { transform: translate3d(0, var(--stack), 0); }
}
@keyframes settle {
  from { transform: translate3d(0, calc(var(--stack) - 4px), 0) scale(1.03); }
  to { transform: translate3d(0, var(--stack), 0) scale(1); }
}

.shuffle-status { margin: 0; color: #f0dcae; font: 500 17px/1.5 "Moonlit Serif", serif; letter-spacing: .06em; text-align: center; }
.skip { font-size: 13px; }

@media (max-width: 420px) {
  .shuffle-stage { --orbit: 76px; --cut-x: 82px; height: 290px; }
  .stage-circle { width: 236px; }
  .shuffle-card { width: 70px; height: 105px; margin: -52px 0 0 -35px; }
}

@media (prefers-reduced-motion: reduce) {
  .shuffle-card { animation: none !important; }
}
</style>
