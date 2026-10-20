<template>
  <div
    class="themed-face"
    :class="[`phase-${phase}`, { reversed, compact, covered: Boolean(theme) }]"
    :style="faceStyle"
  >
    <TarotHoloCard :src="theme?.cover || fallbackSrc" :alt="alt" :compact="compact" :reversed="reversed" :title="title" :subtitle="subtitle" @image-load="imageReady = true" @image-error="imageReady = true" />
    <span v-if="theme?.lineart && phase !== 'done'" class="manifest-layer" aria-hidden="true">
      <img class="lineart" :src="theme.lineart" alt="" decoding="async" />
      <span class="lineart-tint"></span>
      <span v-if="phase === 'drawing'" class="scanlight"></span>
    </span>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import TarotHoloCard from "@/components/matchmaking/TarotHoloCard.vue";

// Moonlit covers carry a screenshot stage, so the art is cropped exactly like MoonlitCardArtwork.
const props = defineProps({
  theme: { type: Object, default: null },
  fallbackSrc: { type: String, default: "" },
  alt: { type: String, required: true },
  reversed: { type: Boolean, default: false },
  compact: { type: Boolean, default: false },
  title: { type: String, default: "" },
  subtitle: { type: String, default: "" },
  // Plays the manifestation once on mount: gold line art sketches in, then the painting emerges.
  manifest: { type: Boolean, default: false },
});

const emit = defineEmits(["manifested"]);
const imageReady = ref(false);
const faceStyle = computed(() => ({
  "--aura": props.theme?.aura || "#e9c983",
}));
const phase = ref(props.manifest && props.theme?.lineart ? "drawing" : "done");
const timers = [];
watch([phase, imageReady], ([current, loaded]) => {
  if (current === "done" && loaded) emit("manifested");
}, { flush: "post" });

onMounted(() => {
  if (phase.value !== "drawing") return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { phase.value = "done"; return; }
  timers.push(window.setTimeout(() => { phase.value = "emerging"; }, 950));
  timers.push(window.setTimeout(() => { phase.value = "done"; }, 2100));
});
onBeforeUnmount(() => timers.forEach(window.clearTimeout));
</script>

<style scoped>
.themed-face { position: relative; width: 100%; height: 100%; }

.themed-face :deep(.tarot-art) { transition: opacity .9s ease; }
.themed-face.covered :deep(.tarot-art) { transform: translate3d(var(--art-offset-x), var(--art-offset-y), 2px) scale(1.08); }
.themed-face.covered.reversed :deep(.tarot-art) { transform: translate3d(calc(var(--art-offset-x) * -1), calc(var(--art-offset-y) * -1), 2px) rotate(180deg) scale(1.08); }
.themed-face.covered.compact :deep(.tarot-art) { transform: scale(1.08); }
.themed-face.covered.compact.reversed :deep(.tarot-art) { transform: rotate(180deg) scale(1.08); }
.phase-drawing :deep(.tarot-art) { opacity: 0; }
.phase-drawing :deep(.foil) { opacity: 0; }

/* The line art ships as opaque black-on-white; inverting it and multiplying a gradient
   over the result gives glowing lines in the theme colour on a night background. */
.manifest-layer {
  position: absolute;
  inset: 0;
  z-index: 2;
  overflow: hidden;
  isolation: isolate;
  border-radius: 10px;
  background: #0b1022;
  pointer-events: none;
  opacity: 0;
}
.lineart { display: block; width: 100%; height: 100%; object-fit: fill; filter: invert(1); }
.reversed .lineart { transform: rotate(180deg); }
.lineart-tint { position: absolute; inset: 0; background: linear-gradient(165deg, #fffbe9 8%, var(--aura) 52%, #fff1c6 92%); mix-blend-mode: multiply; }
.phase-drawing .manifest-layer { animation: manifest-in .9s ease-out forwards; }
.phase-emerging .manifest-layer { opacity: 1; animation: manifest-out 1.1s ease-in forwards; }
@keyframes manifest-in { from { opacity: 0; transform: scale(1.04); } to { opacity: 1; transform: scale(1); } }
@keyframes manifest-out { to { opacity: 0; } }

.scanlight {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 28%;
  background: linear-gradient(180deg, transparent, var(--aura) 48%, #fffbe9 52%, transparent);
  mix-blend-mode: screen;
  opacity: .6;
  transform: translateY(-110%);
  animation: scan .95s cubic-bezier(.45, 0, .25, 1) forwards;
}
@keyframes scan { to { transform: translateY(380%); } }

@media (prefers-reduced-motion: reduce) {
  .manifest-layer { display: none; }
  .themed-face :deep(.tarot-art) { transition: none; }
}
</style>
