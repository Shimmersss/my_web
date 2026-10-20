<template>
  <svg class="astrolabe" :class="{ active }" viewBox="0 0 300 300" aria-hidden="true" focusable="false">
    <defs>
      <radialGradient :id="`${uid}-core`" cx="50%" cy="46%" r="55%">
        <stop offset="0" stop-color="#fff6d8" />
        <stop offset=".55" stop-color="#e3c27c" />
        <stop offset="1" stop-color="#a47c3c" />
      </radialGradient>
      <radialGradient :id="`${uid}-halo`" cx="50%" cy="50%" r="50%">
        <stop offset="0" stop-color="#f5d996" stop-opacity=".34" />
        <stop offset="1" stop-color="#f5d996" stop-opacity="0" />
      </radialGradient>
      <path :id="`${uid}-ring`" d="M150 6 a144 144 0 1 1 -0.01 0" />
    </defs>
    <circle class="halo" cx="150" cy="150" r="148" :fill="`url(#${uid}-halo)`" />
    <circle class="charge-ring" cx="150" cy="150" r="153" />
    <g class="ring ring-text">
      <text><textPath :href="`#${uid}-ring`">{{ RING_TEXT }}</textPath></text>
    </g>
    <g class="ring ring-zodiac">
      <circle cx="150" cy="150" r="138" />
      <circle cx="150" cy="150" r="117" />
      <line v-for="tick in 72" :key="`t${tick}`" x1="150" y1="12" x2="150" :y2="tick % 6 === 0 ? 22 : 17" :transform="`rotate(${tick * 5} 150 150)`" />
      <text v-for="(sign, index) in zodiac" :key="sign" x="150" y="134" :transform="`rotate(${index * 30 + 15} 150 150) translate(0 -${106})`">{{ sign }}</text>
    </g>
    <g class="ring ring-arcana">
      <circle cx="150" cy="150" r="100" />
      <circle cx="150" cy="150" r="88" stroke-dasharray="2 5" />
      <g v-for="index in 22" :key="`a${index}`" :transform="`rotate(${(index - 1) * (360 / 22)} 150 150)`">
        <line x1="150" y1="50" x2="150" y2="58" />
        <circle cx="150" cy="64" r="1.6" class="dot" />
      </g>
    </g>
    <g class="ring ring-star">
      <polygon :points="triangle(0)" />
      <polygon :points="triangle(180)" />
      <circle cx="150" cy="150" r="58" />
    </g>
    <g class="core">
      <circle cx="150" cy="150" r="34" class="core-disc" />
      <path :fill="`url(#${uid}-core)`" d="M150 124 A26 26 0 1 0 150 176 A20 22 0 1 1 150 124 Z" />
      <path class="spark" d="M174 132 l2.6 7.4 7.4 2.6 -7.4 2.6 -2.6 7.4 -2.6 -7.4 -7.4 -2.6 7.4 -2.6 z" />
    </g>
  </svg>
</template>

<script setup>
import { useId } from "vue";

defineProps({ active: { type: Boolean, default: false } });
const uid = `astro-${useId()}`;
const RING_TEXT = "✦ MAJOR ARCANA ✦ MOONLIT DIVINATION ✦ 月下占卜 ✦ THE FOOL TO THE WORLD ✦ AS ABOVE, SO BELOW ✦";
// U+FE0E forces text glyphs so platforms do not swap in colour emoji.
const zodiac = ["♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓"].map(sign => `${sign}︎`);

function triangle(offset) {
  return [0, 120, 240].map(angle => {
    const radians = ((angle + offset - 90) * Math.PI) / 180;
    return `${(150 + Math.cos(radians) * 78).toFixed(1)},${(150 + Math.sin(radians) * 78).toFixed(1)}`;
  }).join(" ");
}
</script>

<style scoped>
.astrolabe {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
  color: #e0be78;
}

.astrolabe circle,
.astrolabe line,
.astrolabe polygon {
  fill: none;
  stroke: currentColor;
  stroke-width: .8;
}

.ring circle,
.ring polygon { stroke-opacity: .5; }
.ring line { stroke-opacity: .55; }
.ring text {
  fill: #f1d79e;
  font: 15px/1 "Segoe UI Symbol", "Noto Sans Symbols 2", "Apple Symbols", serif;
  text-anchor: middle;
  opacity: .85;
}
.dot { fill: currentColor !important; stroke: none !important; }
.halo { stroke: none !important; opacity: .6; transition: opacity .8s ease; }
.charge-ring {
  stroke-dasharray: 3 9;
  stroke-opacity: 0;
  transition: stroke-opacity .6s ease;
}
.core-disc { fill: #10172b !important; stroke-opacity: .6; }
.spark { fill: #fff3cf; }

.ring, .charge-ring, .core .spark {
  transform-box: view-box;
  transform-origin: 50% 50%;
}
.ring-text text { fill: #e9cf93; font: 600 8.6px/1 "Moonlit Serif", serif; letter-spacing: 1.6px; opacity: .7; }
.ring-text { animation: astro-spin 90s linear infinite reverse; }
.ring-zodiac { animation: astro-spin 160s linear infinite; }
.ring-arcana { animation: astro-spin 110s linear infinite reverse; }
.ring-star { animation: astro-spin 220s linear infinite; }
.charge-ring { animation: astro-spin 30s linear infinite reverse; }
.spark { animation: astro-twinkle 4.8s ease-in-out infinite; transform-box: fill-box; }

.active .ring-zodiac { animation-duration: 18s; }
.active .ring-arcana { animation-duration: 12s; }
.active .ring-star { animation-duration: 26s; }
.active .ring-text { animation-duration: 14s; }
.active .charge-ring { stroke-opacity: .85; }
.active .halo { opacity: 1; }

@keyframes astro-spin { to { transform: rotate(360deg); } }
@keyframes astro-twinkle { 50% { opacity: .35; transform: scale(.7); } }

@media (prefers-reduced-motion: reduce) {
  .ring, .charge-ring, .spark { animation: none !important; }
}
</style>
