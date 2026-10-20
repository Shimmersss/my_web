<template>
  <span v-if="particles.length" class="gold-burst" :style="color ? { '--mote': color } : null" aria-hidden="true">
    <span v-for="particle in particles" :key="particle.id" class="mote" :style="particle.style"></span>
  </span>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from "vue";

// Bumping `trigger` releases one short burst of motes; nothing runs between bursts.
const props = defineProps({ trigger: { type: Number, default: 0 }, color: { type: String, default: "" } });
const particles = ref([]);
let timer = 0, serial = 0;

watch(() => props.trigger, value => {
  if (!value || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const count = window.matchMedia("(max-width: 600px)").matches ? 10 : 20;
  particles.value = Array.from({ length: count }, (_, index) => {
    const angle = (index / count) * Math.PI * 2 + Math.random() * 0.5;
    const distance = 50 + Math.random() * 70;
    return {
      id: `${value}-${serial++}`,
      style: {
        "--dx": `${Math.cos(angle) * distance}px`,
        "--dy": `${Math.sin(angle) * distance}px`,
        "--size": `${3 + Math.random() * 4}px`,
        "--delay": `${Math.random() * 90}ms`,
      },
    };
  });
  window.clearTimeout(timer);
  timer = window.setTimeout(() => { particles.value = []; }, 1100);
});
onBeforeUnmount(() => window.clearTimeout(timer));
</script>

<style scoped>
.gold-burst { --mote: #f3cf7a; position: absolute; left: 50%; top: 50%; width: 0; height: 0; pointer-events: none; z-index: 5; }
.mote {
  position: absolute;
  left: calc(var(--size) / -2);
  top: calc(var(--size) / -2);
  width: var(--size);
  height: var(--size);
  border-radius: 50%;
  background: radial-gradient(circle, #fffbe8, var(--mote) 55%, transparent 70%);
  opacity: 0;
  animation: mote-fly .9s cubic-bezier(.15, .7, .3, 1) var(--delay) forwards;
}
@keyframes mote-fly {
  0% { transform: translate(0, 0) scale(.4); opacity: 1; }
  70% { opacity: .9; }
  100% { transform: translate(var(--dx), var(--dy)) scale(1); opacity: 0; }
}
</style>
