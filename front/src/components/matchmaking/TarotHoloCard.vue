<template>
  <div
    ref="card"
    class="tarot-holo-card"
    :class="{ compact }"
    :style="cardStyle"
    @pointermove="setPointerPosition"
    @pointerleave="resetPointerPosition"
  >
    <img v-if="!imageFailed" class="tarot-art" :src="src" :alt="alt" loading="lazy" decoding="async" @error="handleImageError" />
    <span v-else class="tarot-art tarot-art-fallback" role="img" :aria-label="`${alt}加载失败`">✦</span>
    <span class="card-depth" aria-hidden="true"></span>
    <span class="foil" aria-hidden="true"></span>
    <span class="edge-light" aria-hidden="true"></span>
    <div v-if="title || subtitle" class="card-caption">
      <strong v-if="title">{{ title }}</strong>
      <small v-if="subtitle">{{ subtitle }}</small>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from "vue";

const emit = defineEmits(["image-error"]);
const props = defineProps({
  src: { type: String, required: true },
  alt: { type: String, required: true },
  title: { type: String, default: "" },
  subtitle: { type: String, default: "" },
  compact: { type: Boolean, default: false },
});

const card = ref(null);
const imageFailed = ref(false);
const pointer = ref({ x: 50, y: 50, rotateX: 0, rotateY: 0 });
const cardStyle = computed(() => ({
  "--pointer-x": `${pointer.value.x}%`,
  "--pointer-y": `${pointer.value.y}%`,
  "--rotate-x": `${pointer.value.rotateX}deg`,
  "--rotate-y": `${pointer.value.rotateY}deg`,
}));

function setPointerPosition(event) {
  if (event.pointerType === "touch" || !card.value) return;
  const bounds = card.value.getBoundingClientRect();
  const x = Math.max(0, Math.min(100, ((event.clientX - bounds.left) / bounds.width) * 100));
  const y = Math.max(0, Math.min(100, ((event.clientY - bounds.top) / bounds.height) * 100));
  pointer.value = { x, y, rotateX: (50 - y) * 0.075, rotateY: (x - 50) * 0.075 };
}

function resetPointerPosition() {
  pointer.value = { x: 50, y: 50, rotateX: 0, rotateY: 0 };
}

function handleImageError() {
  imageFailed.value = true;
  emit("image-error");
}

watch(() => props.src, () => { imageFailed.value = false; });
</script>

<style scoped lang="scss">
.tarot-holo-card {
  --pointer-x: 50%; --pointer-y: 50%; --rotate-x: 0deg; --rotate-y: 0deg;
  position: relative; isolation: isolate; display: grid; grid-template-rows: minmax(0, 1fr) auto;
  width: 100%; height: 100%; overflow: hidden; border: 1px solid #d6bd8e; border-radius: 10px;
  background: #111726; color: #fff8e9; box-shadow: 0 8px 20px #13182733, inset 0 0 0 1px #f7e1a744;
  transform: perspective(780px) rotateX(var(--rotate-x)) rotateY(var(--rotate-y)) translateZ(0);
  transform-style: preserve-3d; transition: transform 180ms ease-out, box-shadow 180ms ease-out; will-change: transform;
}
.tarot-holo-card:hover { box-shadow: 0 16px 28px #11152655, 0 0 22px #b8b5ff35, inset 0 0 0 1px #fff0b888; }
.tarot-art { display: block; width: 100%; height: 100%; min-height: 0; object-fit: cover; transform: translateZ(1px) scale(1.012); }
.tarot-art-fallback { display: grid; place-items: center; color: #e9c983; background: radial-gradient(circle at 50% 38%, #56617e, #202842 62%, #111726); font-size: 42px; }
.card-depth,.foil,.edge-light { position: absolute; inset: 0; pointer-events: none; }
.card-depth { z-index: 1; background: radial-gradient(ellipse 76% 58% at var(--pointer-x) var(--pointer-y), transparent 24%, #07102024 78%); mix-blend-mode: multiply; }
.foil { z-index: 2; opacity: .62; background: linear-gradient(112deg, transparent 31%, #fbda9f1c 40%, #f7b8dc8c 46%, #b9ddff8a 52%, #f8e4a96e 59%, transparent 68%), repeating-linear-gradient(118deg, #ff94d10d 0 1px, #8ed9ff0d 1px 3px, transparent 3px 6px); background-position: calc(var(--pointer-x) - 68%) calc(var(--pointer-y) - 50%), center; background-size: 210% 210%, 100% 100%; mix-blend-mode: color-dodge; transition: background-position 120ms ease-out; }
.edge-light { z-index: 3; border: 1px solid transparent; border-radius: inherit; background: linear-gradient(135deg, #fff6d499, transparent 26%, transparent 68%, #9ee7ff66) border-box; mask: linear-gradient(#000 0 0) padding-box, linear-gradient(#000 0 0); mask-composite: exclude; -webkit-mask-composite: xor; opacity: .76; }
.card-caption { position: relative; z-index: 4; display: grid; gap: 2px; padding: 8px 6px 9px; background: linear-gradient(90deg, #141a31ee, #392c45ed); box-shadow: 0 -8px 18px #0d122c55; text-align: center; transform: translateZ(9px); }
.card-caption strong { font-family: "Moonlit Serif", serif; font-size: 14px; line-height: 1.25; }.card-caption small { color: #e8d9bc; font-size: 10px; line-height: 1.35; }.compact { border-radius: 12px; }.compact .tarot-art { grid-row: 1 / -1; }.compact .card-caption { display: none; }
@media (prefers-reduced-motion: reduce) { .tarot-holo-card { transform: none; transition: none; }.foil { background-position: center; transition: none; } }
</style>
