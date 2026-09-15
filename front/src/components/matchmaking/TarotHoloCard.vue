<template>
  <div
    ref="card"
    class="tarot-holo-card"
    :class="{ compact }"
    @pointerdown="startTouchInteraction"
    @pointerenter="setPointerPosition"
    @pointermove="setPointerPosition"
    @pointerup="finishTouchInteraction"
    @pointercancel="cancelTouchInteraction"
    @pointerleave="handlePointerLeave"
    @click.capture="suppressDraggedClick"
  >
    <span class="card-thickness" aria-hidden="true"></span>
    <div class="card-surface">
      <img v-if="!imageFailed" class="tarot-art" :src="src" :alt="alt" loading="lazy" decoding="async" @error="handleImageError" />
      <span v-else class="tarot-art tarot-art-fallback" role="img" :aria-label="`${alt}加载失败`">✦</span>
      <span class="card-depth" aria-hidden="true"></span>
      <span class="foil" aria-hidden="true"></span>
      <img v-if="!compact" class="celestial-particles" src="/tarot/layers/celestial-particles.webp" alt="" aria-hidden="true" loading="lazy" decoding="async" />
      <img v-if="!compact" class="relief-frame" src="/tarot/layers/relief-frame.webp" alt="" aria-hidden="true" loading="lazy" decoding="async" />
      <span class="edge-light" aria-hidden="true"></span>
      <div v-if="title || subtitle" class="card-caption">
        <strong v-if="title">{{ title }}</strong>
        <small v-if="subtitle">{{ subtitle }}</small>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from "vue";

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
let pointerFrame = 0;
let pendingPointer = null;
let activeTouchId = null;
let touchStart = null;
let touchDragged = false;
let suppressNextClick = false;

function reducedMotionRequested() {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function applyPointerPosition() {
  pointerFrame = 0;
  if (!card.value || !pendingPointer) return;

  const bounds = card.value.getBoundingClientRect();
  const x = Math.max(0, Math.min(100, ((pendingPointer.x - bounds.left) / bounds.width) * 100));
  const y = Math.max(0, Math.min(100, ((pendingPointer.y - bounds.top) / bounds.height) * 100));
  const normalizedX = (x - 50) / 50;
  const normalizedY = (y - 50) / 50;
  const isTouch = pendingPointer.pointerType === "touch";
  const tilt = isTouch ? 3.1 : 4.25;
  const artShift = isTouch ? 1.8 : 2.5;
  const effectsShift = isTouch ? 5.5 : 8;
  const captionShift = isTouch ? 2 : 3;

  card.value.style.setProperty("--pointer-x", `${x}%`);
  card.value.style.setProperty("--pointer-y", `${y}%`);
  card.value.style.setProperty("--foil-angle", `${(112 + normalizedX * 7).toFixed(1)}deg`);
  card.value.style.setProperty("--art-offset-x", `${(-normalizedX * artShift).toFixed(2)}px`);
  card.value.style.setProperty("--art-offset-y", `${(-normalizedY * artShift).toFixed(2)}px`);
  card.value.style.setProperty("--effects-offset-x", `${(normalizedX * effectsShift).toFixed(2)}px`);
  card.value.style.setProperty("--effects-offset-y", `${(normalizedY * effectsShift).toFixed(2)}px`);
  card.value.style.setProperty("--caption-offset-x", `${(normalizedX * captionShift).toFixed(2)}px`);
  card.value.style.setProperty("--caption-offset-y", `${(normalizedY * captionShift).toFixed(2)}px`);
  card.value.style.setProperty("--rotate-x", `${(-normalizedY * tilt).toFixed(2)}deg`);
  card.value.style.setProperty("--rotate-y", `${(normalizedX * tilt).toFixed(2)}deg`);
}

function setPointerPosition(event) {
  if (props.compact || reducedMotionRequested()) return;
  if (event.pointerType === "touch" && activeTouchId !== event.pointerId) return;
  if (event.pointerType === "touch" && touchStart) {
    touchDragged ||= Math.hypot(event.clientX - touchStart.x, event.clientY - touchStart.y) > 7;
  }
  pendingPointer = { x: event.clientX, y: event.clientY, pointerType: event.pointerType };
  card.value?.setAttribute("data-tilting", "true");
  if (!pointerFrame) pointerFrame = window.requestAnimationFrame(applyPointerPosition);
}

function startTouchInteraction(event) {
  if (props.compact || event.pointerType !== "touch" || reducedMotionRequested()) return;
  suppressNextClick = false;
  activeTouchId = event.pointerId;
  touchStart = { x: event.clientX, y: event.clientY };
  touchDragged = false;
  card.value?.setPointerCapture?.(event.pointerId);
  setPointerPosition(event);
}

function finishTouchInteraction(event) {
  if (event.pointerType !== "touch" || activeTouchId !== event.pointerId) return;
  suppressNextClick = touchDragged;
  if (card.value?.hasPointerCapture?.(event.pointerId)) card.value.releasePointerCapture(event.pointerId);
  activeTouchId = null;
  touchStart = null;
  touchDragged = false;
  resetPointerPosition();
}

function cancelTouchInteraction(event) {
  if (event.pointerType !== "touch" || activeTouchId !== event.pointerId) return;
  suppressNextClick = false;
  activeTouchId = null;
  touchStart = null;
  touchDragged = false;
  resetPointerPosition();
}

function suppressDraggedClick(event) {
  if (!suppressNextClick) return;
  suppressNextClick = false;
  event.preventDefault();
  event.stopPropagation();
}

function handlePointerLeave(event) {
  if (event.pointerType === "touch" && activeTouchId === event.pointerId) return;
  resetPointerPosition();
}

function resetPointerPosition() {
  pendingPointer = null;
  if (pointerFrame) window.cancelAnimationFrame(pointerFrame);
  pointerFrame = 0;
  if (!card.value) return;
  card.value.removeAttribute("data-tilting");
  card.value.style.setProperty("--pointer-x", "50%");
  card.value.style.setProperty("--pointer-y", "50%");
  card.value.style.setProperty("--foil-angle", "112deg");
  card.value.style.setProperty("--art-offset-x", "0px");
  card.value.style.setProperty("--art-offset-y", "0px");
  card.value.style.setProperty("--effects-offset-x", "0px");
  card.value.style.setProperty("--effects-offset-y", "0px");
  card.value.style.setProperty("--caption-offset-x", "0px");
  card.value.style.setProperty("--caption-offset-y", "0px");
  card.value.style.setProperty("--rotate-x", "0deg");
  card.value.style.setProperty("--rotate-y", "0deg");
}

function handleImageError() {
  imageFailed.value = true;
  emit("image-error");
}

watch(() => props.src, () => { imageFailed.value = false; });
watch(() => props.compact, (compact) => { if (compact) resetPointerPosition(); });
onBeforeUnmount(() => {
  if (pointerFrame) window.cancelAnimationFrame(pointerFrame);
  activeTouchId = null;
});
</script>

<style scoped lang="scss">
.tarot-holo-card {
  --pointer-x: 50%;
  --pointer-y: 50%;
  --foil-angle: 112deg;
  --art-offset-x: 0px;
  --art-offset-y: 0px;
  --effects-offset-x: 0px;
  --effects-offset-y: 0px;
  --caption-offset-x: 0px;
  --caption-offset-y: 0px;
  --rotate-x: 0deg;
  --rotate-y: 0deg;
  position: relative;
  isolation: isolate;
  width: 100%;
  height: 100%;
  color: #fff8e9;
  transform: perspective(780px) rotateX(var(--rotate-x)) rotateY(var(--rotate-y)) translateZ(0);
  transform-style: preserve-3d;
  transition: transform 220ms cubic-bezier(.2, .72, .2, 1), filter 220ms ease;
  contain: layout style;
}

.tarot-holo-card:not(.compact) {
  touch-action: pan-y;
}

.tarot-holo-card[data-tilting="true"] {
  transition: none;
  will-change: transform;
}

.card-surface {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  overflow: hidden;
  border: 1px solid #d6bd8e;
  border-radius: 10px;
  background: #111726;
  box-shadow: 0 8px 20px #13182733, inset 0 0 0 1px #f7e1a744;
  transform-style: preserve-3d;
  transition: box-shadow 180ms ease-out;
}

.tarot-holo-card:not(.compact):hover .card-surface {
  box-shadow: 0 18px 34px #090e1f66, 0 0 24px #b8b5ff38, inset 0 0 0 1px #fff0b899;
}

.card-thickness {
  position: absolute;
  inset: 3px 1px 1px 4px;
  z-index: -1;
  border: 1px solid #765b34;
  border-radius: 11px;
  background: linear-gradient(120deg, #46341f, #d3b16d 44%, #5c4326 72%, #251a12);
  box-shadow: 7px 10px 15px #060a1466;
  transform: translateZ(-9px);
}

.tarot-art {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 0;
  object-fit: cover;
  transform: translate3d(var(--art-offset-x), var(--art-offset-y), 2px) scale(1.025);
}

.tarot-art-fallback {
  display: grid;
  place-items: center;
  color: #e9c983;
  background: radial-gradient(circle at 50% 38%, #56617e, #202842 62%, #111726);
  font-size: 42px;
}

.card-depth,
.foil,
.edge-light,
.celestial-particles,
.relief-frame {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.card-depth {
  z-index: 1;
  background: radial-gradient(ellipse 76% 58% at var(--pointer-x) var(--pointer-y), transparent 24%, #0710202b 78%);
  mix-blend-mode: multiply;
}

.foil {
  z-index: 2;
  overflow: hidden;
  opacity: .46;
  background:
    radial-gradient(ellipse 54% 38% at var(--pointer-x) var(--pointer-y), #fff9dd52 0, #ffd6e62b 17%, #b7dfff20 32%, transparent 67%),
    linear-gradient(var(--foil-angle), transparent 38%, #f7cde31f 43%, #fff4c260 48%, #c3e6ff55 52%, #dcc7ff2b 57%, transparent 62%);
  background-position: center, calc(var(--pointer-x) - 72%) calc(var(--pointer-y) - 50%);
  background-size: 100% 100%, 225% 225%;
  mix-blend-mode: screen;
}

.foil::before,
.foil::after {
  content: "";
  position: absolute;
  inset: -12%;
}

.foil::before {
  opacity: .34;
  background: repeating-linear-gradient(calc(var(--foil-angle) + 5deg), transparent 0 13px, #f8d9ed1a 14px, #d8efff24 15px, transparent 17px 31px);
  mask-image: radial-gradient(ellipse 62% 48% at var(--pointer-x) var(--pointer-y), #000 0 32%, transparent 72%);
  -webkit-mask-image: radial-gradient(ellipse 62% 48% at var(--pointer-x) var(--pointer-y), #000 0 32%, transparent 72%);
}

.foil::after {
  opacity: .4;
  background-image: radial-gradient(circle, #fff9dc 0 .55px, transparent .9px);
  background-position: var(--pointer-x) var(--pointer-y);
  background-size: 13px 17px;
  mask-image: radial-gradient(circle at var(--pointer-x) var(--pointer-y), #000 0 8%, transparent 34%);
  -webkit-mask-image: radial-gradient(circle at var(--pointer-x) var(--pointer-y), #000 0 8%, transparent 34%);
}

.celestial-particles {
  z-index: 3;
  object-fit: fill;
  opacity: .72;
  filter: drop-shadow(0 0 4px #b9b5ff85);
  transform: translate3d(var(--effects-offset-x), var(--effects-offset-y), 22px) scale(1.035);
}

.relief-frame {
  z-index: 4;
  object-fit: fill;
  opacity: .82;
  filter: drop-shadow(0 2px 2px #05071188) drop-shadow(0 0 3px #ffeab255);
  transform: translateZ(4px);
}

.edge-light {
  z-index: 5;
  border: 1px solid transparent;
  border-radius: inherit;
  background: linear-gradient(135deg, #fff6d4a8, transparent 26%, transparent 68%, #9ee7ff66) border-box;
  mask: linear-gradient(#000 0 0) padding-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
  -webkit-mask-composite: xor;
  opacity: .8;
}

.card-caption {
  position: relative;
  z-index: 6;
  display: grid;
  gap: 2px;
  padding: 8px 18px 10px;
  background: linear-gradient(90deg, #141a31f2, #392c45f0);
  box-shadow: 0 -8px 18px #0d122c66;
  text-align: center;
  transform: translate3d(var(--caption-offset-x), var(--caption-offset-y), 28px);
}

.card-caption strong {
  font-family: "Moonlit Serif", serif;
  font-size: 14px;
  line-height: 1.25;
  text-shadow: 0 2px 5px #000b;
}

.card-caption small {
  color: #e8d9bc;
  font-size: 10px;
  line-height: 1.35;
}

.compact {
  transform: none;
  transition: none;
}

.compact .card-surface {
  border-radius: 12px;
}

.compact .tarot-art {
  grid-row: 1 / -1;
  transform: scale(1.012);
}

.compact .card-thickness,
.compact .card-caption {
  display: none;
}

.compact .foil {
  opacity: .14;
  background-position: center;
}

.compact .foil::before,
.compact .foil::after {
  display: none;
}

@media (hover: none), (pointer: coarse) {
  .tarot-holo-card:not(.compact)[data-tilting="true"] {
    filter: drop-shadow(0 12px 18px #080c1870);
  }

  .card-thickness {
    transform: translateZ(-6px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .tarot-holo-card,
  .card-surface {
    transform: none;
    transition: none;
  }

  .tarot-art,
  .celestial-particles,
  .relief-frame,
  .card-caption {
    transform: none;
  }

  .foil {
    background-position: center;
  }
}
</style>
