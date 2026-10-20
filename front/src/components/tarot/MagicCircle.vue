<template>
  <svg class="magic-circle" :class="{ flare }" viewBox="0 0 300 300" aria-hidden="true" focusable="false">
    <defs>
      <radialGradient :id="`${uid}-glow`" cx="50%" cy="50%" r="50%">
        <stop offset="0" stop-color="#f6dc9c" stop-opacity=".42" />
        <stop offset=".55" stop-color="#9c8cff" stop-opacity=".12" />
        <stop offset="1" stop-color="#9c8cff" stop-opacity="0" />
      </radialGradient>
    </defs>
    <circle class="mc-glow" cx="150" cy="150" r="150" :fill="`url(#${uid}-glow)`" />
    <g class="mc-ring mc-outer">
      <circle cx="150" cy="150" r="140" />
      <circle cx="150" cy="150" r="132" stroke-dasharray="1 6" />
      <line v-for="tick in 56" :key="`t${tick}`" x1="150" y1="10" x2="150" :y2="tick % 8 === 0 ? 22 : 15" :transform="`rotate(${tick * (360 / 56)} 150 150)`" />
    </g>
    <g class="mc-ring mc-star">
      <polygon :points="heptagram" />
      <circle cx="150" cy="150" r="96" />
      <text v-for="(planet, index) in planets" :key="planet" :x="point(index, 112).x" :y="point(index, 112).y + 5">{{ planet }}</text>
    </g>
    <g class="mc-ring mc-inner">
      <circle cx="150" cy="150" r="54" stroke-dasharray="3 5" />
      <circle cx="150" cy="150" r="40" />
      <polygon points="150,112 183,169 117,169" />
      <polygon points="150,188 183,131 117,131" />
    </g>
  </svg>
</template>

<script setup>
import { useId } from "vue";

defineProps({ flare: { type: Boolean, default: false } });
const uid = `circle-${useId()}`;
// Seven classical planets sit on the seven points; U+FE0E keeps them as text glyphs.
const planets = ["☉", "☽", "☿", "♀", "♂", "♃", "♄"].map(glyph => `${glyph}︎`);

function point(index, radius) {
  const angle = ((index * 360) / 7 - 90) * (Math.PI / 180);
  return { x: 150 + Math.cos(angle) * radius, y: 150 + Math.sin(angle) * radius };
}
// {7/3} star polygon: join every third vertex.
const heptagram = Array.from({ length: 7 }, (_, step) => {
  const { x, y } = point((step * 3) % 7, 92);
  return `${x.toFixed(1)},${y.toFixed(1)}`;
}).join(" ");
</script>

<style scoped>
.magic-circle { display: block; width: 100%; height: auto; overflow: visible; color: #e0be78; }
.magic-circle circle, .magic-circle line, .magic-circle polygon { fill: none; stroke: currentColor; stroke-width: .9; }
.mc-ring circle, .mc-ring polygon { stroke-opacity: .55; }
.mc-ring line { stroke-opacity: .5; }
.mc-glow { stroke: none !important; opacity: .7; transition: opacity .5s ease; }
.mc-star text { fill: #f6dc9c; font: 14px/1 "Segoe UI Symbol", "Noto Sans Symbols 2", "Apple Symbols", serif; text-anchor: middle; }
.mc-ring { transform-box: view-box; transform-origin: 50% 50%; }
.mc-outer { animation: mc-spin 24s linear infinite; }
.mc-star { animation: mc-spin 36s linear infinite reverse; }
.mc-inner { animation: mc-spin 12s linear infinite; }
.flare .mc-glow { opacity: 1; animation: mc-pulse .9s ease-in-out infinite; transform-box: view-box; transform-origin: 50% 50%; }
.flare .mc-ring circle, .flare .mc-ring polygon { stroke-opacity: .95; }
@keyframes mc-spin { to { transform: rotate(360deg); } }
@keyframes mc-pulse { 50% { transform: scale(1.06); } }
@media (prefers-reduced-motion: reduce) { .mc-ring, .flare .mc-glow { animation: none; } }
</style>
