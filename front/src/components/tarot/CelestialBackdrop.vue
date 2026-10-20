<template>
  <div ref="layer" class="celestial-backdrop" :style="{ '--aura': aura || 'transparent' }" aria-hidden="true">
    <div class="sky">
      <span class="nebula nebula-violet"></span>
      <span class="nebula nebula-blue"></span>
      <span class="nebula nebula-aura" :class="{ lit: Boolean(aura) }"></span>
      <canvas ref="canvas"></canvas>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";

// A viewport-sized sky that sticks while the page scrolls, so the pixel budget stays bounded.
// `aura` tints one nebula with the theme colour of the cards being read.
defineProps({ aura: { type: String, default: "" } });

const layer = ref(null);
const canvas = ref(null);
const COLORS = ["255,248,232", "240,214,150", "200,196,255", "180,220,255"];
const CONSTELLATION_DEPTH = 0.06;
let context = null, frame = 0, lastPaint = 0, stars = [], constellations = [], meteor = null, nextMeteorAt = 0;
let visible = true, onScreen = true, reduced = false, finePointer = false, width = 0, height = 0, ratio = 1;
let scrollOffset = 0, canvasTop = 0, pointer = null;
let resizeObserver = null, intersectionObserver = null, motionQuery = null;

function seedSky() {
  const density = width < 600 ? 160 : 260;
  const count = Math.round(density * Math.min(1.4, (width * height) / (1280 * 800)) + 40);
  stars = Array.from({ length: count }, () => {
    const bright = Math.random() < 0.09;
    return {
      x: Math.random() * width,
      y: Math.random() * height,
      r: bright ? 1.15 + Math.random() * 0.9 : 0.35 + Math.random() * 0.8,
      bright,
      depth: bright ? CONSTELLATION_DEPTH : 0.02 + Math.random() * 0.2,
      base: 0.25 + Math.random() * 0.55,
      speed: 0.4 + Math.random() * 1.6,
      phase: Math.random() * Math.PI * 2,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
    };
  });
  // Greedy chains of nearby bright stars become slowly breathing constellations.
  const pool = stars.filter(star => star.bright);
  const used = new Set();
  constellations = [];
  for (const seed of pool) {
    if (used.has(seed) || constellations.length >= (width < 600 ? 3 : 6)) continue;
    const chain = [seed];
    used.add(seed);
    let current = seed;
    while (chain.length < 5) {
      const next = pool
        .filter(star => !used.has(star))
        .map(star => ({ star, distance: Math.hypot(star.x - current.x, star.y - current.y) }))
        .filter(item => item.distance < Math.min(220, width / 4))
        .sort((a, b) => a.distance - b.distance)[0];
      if (!next) break;
      chain.push(next.star);
      used.add(next.star);
      current = next.star;
    }
    if (chain.length >= 3) constellations.push({ chain, phase: Math.random() * Math.PI * 2 });
  }
}

function measure() {
  if (!canvas.value) return;
  canvasTop = canvas.value.getBoundingClientRect().top;
  scrollOffset = window.scrollY;
}

function resize() {
  if (!layer.value || !canvas.value) return;
  const mobile = window.matchMedia("(max-width: 600px)").matches;
  const nextRatio = Math.min(window.devicePixelRatio || 1, mobile ? 1.25 : 1.5);
  const nextWidth = layer.value.clientWidth;
  const nextHeight = Math.min(window.innerHeight, Math.max(layer.value.clientHeight, 1));
  // Content growth below the fold resizes the layer too; only a new viewport needs a reseed.
  if (context && nextWidth === width && nextHeight === height && nextRatio === ratio) return;
  ratio = nextRatio; width = nextWidth; height = nextHeight;
  canvas.value.width = Math.round(width * ratio);
  canvas.value.height = Math.round(height * ratio);
  canvas.value.style.height = `${height}px`;
  context = canvas.value.getContext("2d");
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  seedSky();
  measure();
  paint(performance.now());
}

function project(star) {
  const y = (((star.y - scrollOffset * star.depth) % height) + height) % height;
  return { x: star.x, y };
}

function spawnMeteor(now) {
  const fromLeft = Math.random() < 0.5;
  meteor = {
    x: fromLeft ? Math.random() * width * 0.5 : width * (0.5 + Math.random() * 0.5),
    y: Math.random() * height * 0.35,
    vx: (fromLeft ? 1 : -1) * (0.5 + Math.random() * 0.3),
    vy: 0.22 + Math.random() * 0.12,
    born: now,
    life: 1100,
  };
  nextMeteorAt = now + 5000 + Math.random() * 6000;
}

function paint(now) {
  if (!context) return;
  context.clearRect(0, 0, width, height);
  const time = now / 1000;

  for (const constellation of constellations) {
    const alpha = reduced ? 0.18 : 0.06 + 0.24 * (0.5 + 0.5 * Math.sin(time * 0.18 + constellation.phase));
    context.globalAlpha = alpha;
    context.strokeStyle = "rgb(240,214,150)";
    context.lineWidth = 0.7;
    context.beginPath();
    constellation.chain.forEach((star, index) => {
      const point = project(star);
      if (index === 0) context.moveTo(point.x, point.y);
      else context.lineTo(point.x, point.y);
    });
    context.stroke();
  }

  const near = [];
  for (const star of stars) {
    const point = project(star);
    let twinkle = reduced ? 1 : 0.55 + 0.45 * Math.sin(time * star.speed + star.phase);
    let radius = star.r;
    if (pointer) {
      const distance = Math.hypot(point.x - pointer.x, point.y - pointer.y);
      if (distance < 150) {
        const pull = 1 - distance / 150;
        twinkle = Math.min(1.6, twinkle + pull * 0.9);
        radius *= 1 + pull * 0.5;
        if (distance < 130) near.push({ point, distance });
      }
    }
    context.globalAlpha = Math.min(1, star.base * twinkle);
    context.fillStyle = `rgb(${star.color})`;
    context.beginPath();
    context.arc(point.x, point.y, radius, 0, Math.PI * 2);
    context.fill();
    if (star.r > 1.2) {
      context.globalAlpha = Math.min(1, star.base * twinkle * 0.35);
      context.fillRect(point.x - radius * 3, point.y - 0.3, radius * 6, 0.6);
      context.fillRect(point.x - 0.3, point.y - radius * 3, 0.6, radius * 6);
    }
  }

  // The pointer gathers a faint thread of light to the closest stars.
  if (pointer && near.length) {
    near.sort((a, b) => a.distance - b.distance).slice(0, 3).forEach(({ point, distance }) => {
      context.globalAlpha = 0.28 * (1 - distance / 130);
      context.strokeStyle = "rgb(246,220,156)";
      context.lineWidth = 0.6;
      context.beginPath();
      context.moveTo(pointer.x, pointer.y);
      context.lineTo(point.x, point.y);
      context.stroke();
    });
  }

  if (meteor) {
    const age = now - meteor.born;
    if (age > meteor.life) meteor = null;
    else {
      const progress = age / meteor.life;
      const headX = meteor.x + meteor.vx * age, headY = meteor.y + meteor.vy * age;
      const tail = 110;
      const gradient = context.createLinearGradient(headX, headY, headX - meteor.vx * tail, headY - meteor.vy * tail);
      gradient.addColorStop(0, "rgba(255,244,214,0.95)");
      gradient.addColorStop(1, "rgba(255,244,214,0)");
      context.globalAlpha = Math.sin(progress * Math.PI);
      context.strokeStyle = gradient;
      context.lineWidth = 1.5;
      context.beginPath();
      context.moveTo(headX, headY);
      context.lineTo(headX - meteor.vx * tail, headY - meteor.vy * tail);
      context.stroke();
    }
  }
  context.globalAlpha = 1;
}

function loop(now) {
  frame = 0;
  if (!visible || !onScreen || reduced) return;
  if (now - lastPaint >= 33) { // ~30fps keeps phones cool; twinkle does not need more.
    if (!meteor && now >= nextMeteorAt) spawnMeteor(now);
    paint(now);
    lastPaint = now;
  }
  frame = window.requestAnimationFrame(loop);
}

function start() {
  if (!frame && visible && onScreen && !reduced) frame = window.requestAnimationFrame(loop);
}
function stop() {
  if (frame) window.cancelAnimationFrame(frame);
  frame = 0;
}
function handleVisibility() {
  visible = document.visibilityState === "visible";
  visible ? start() : stop();
}
function handleMotion() {
  reduced = motionQuery.matches;
  meteor = null;
  if (reduced) { stop(); paint(performance.now()); } else start();
}
function handleScroll() {
  measure();
  if (reduced) paint(performance.now());
}
function handlePointer(event) {
  if (!finePointer || reduced) return;
  pointer = { x: event.clientX, y: event.clientY - canvasTop };
}
function clearPointer() { pointer = null; }

onMounted(() => {
  motionQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
  reduced = motionQuery.matches;
  finePointer = window.matchMedia("(hover: hover) and (pointer: fine)").matches;
  motionQuery.addEventListener("change", handleMotion);
  nextMeteorAt = performance.now() + 2000;
  resize();
  resizeObserver = new ResizeObserver(() => resize());
  resizeObserver.observe(layer.value);
  intersectionObserver = new IntersectionObserver(([entry]) => {
    onScreen = entry.isIntersecting;
    onScreen ? start() : stop();
  });
  intersectionObserver.observe(layer.value);
  document.addEventListener("visibilitychange", handleVisibility);
  window.addEventListener("resize", resize);
  window.addEventListener("scroll", handleScroll, { passive: true });
  window.addEventListener("pointermove", handlePointer, { passive: true });
  document.documentElement.addEventListener("pointerleave", clearPointer);
  start();
});

onBeforeUnmount(() => {
  stop();
  resizeObserver?.disconnect();
  intersectionObserver?.disconnect();
  motionQuery?.removeEventListener("change", handleMotion);
  document.removeEventListener("visibilitychange", handleVisibility);
  window.removeEventListener("resize", resize);
  window.removeEventListener("scroll", handleScroll);
  window.removeEventListener("pointermove", handlePointer);
  document.documentElement.removeEventListener("pointerleave", clearPointer);
  context = null;
});
</script>

<style scoped>
.celestial-backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.sky {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: hidden;
}

canvas {
  position: absolute;
  inset: 0 0 auto;
  display: block;
  width: 100%;
}

.nebula {
  position: absolute;
  border-radius: 50%;
  will-change: transform;
}
.nebula-violet {
  right: -18%;
  top: -24%;
  width: 72vmax;
  height: 72vmax;
  background: radial-gradient(circle, #6b4aa83d 0%, #3b2a6a1f 38%, transparent 66%);
  animation: nebula-drift-a 48s ease-in-out infinite alternate;
}
.nebula-blue {
  left: -26%;
  top: 22%;
  width: 64vmax;
  height: 64vmax;
  background: radial-gradient(circle, #1f4f8a33 0%, #183a6a1a 40%, transparent 66%);
  animation: nebula-drift-b 56s ease-in-out infinite alternate;
}
.nebula-aura {
  left: 50%;
  bottom: -42%;
  width: 80vmax;
  height: 80vmax;
  margin-left: -40vmax;
  background: radial-gradient(circle, var(--aura) 0%, transparent 58%);
  opacity: 0;
  transition: opacity 1.8s ease;
  animation: nebula-drift-b 40s ease-in-out infinite alternate-reverse;
}
.nebula-aura.lit { opacity: .2; }
@keyframes nebula-drift-a { to { transform: translate3d(-9%, 7%, 0) scale(1.08); } }
@keyframes nebula-drift-b { to { transform: translate3d(8%, -6%, 0) scale(1.06); } }

@media (prefers-reduced-motion: reduce) {
  .nebula { animation: none; }
  .nebula-aura { transition: none; }
}
</style>
