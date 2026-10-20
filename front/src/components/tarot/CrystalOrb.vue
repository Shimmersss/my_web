<template>
  <div ref="orb" class="crystal-orb" :class="[`mood-${mood.id}`, { paused: paused }]" :style="{ '--charge': charge, '--orb-color': mood.color, '--orb-soft': mood.soft }" aria-hidden="true">
    <div class="orb-halo"></div>
    <div class="orb-orbit"></div>
    <div class="orb-glass">
      <div class="orb-mist"></div>
      <div :key="mood.id" class="orb-vision">
        <template v-if="mood.id === 'general'">
          <span class="cosmic-whorl"></span><span class="cosmic-whorl second"></span>
          <span v-for="i in 9" :key="i" class="star-mote" :style="particleStyle(i)"></span>
          <span class="guiding-star">✦</span>
        </template>
        <template v-else-if="mood.id === 'career'">
          <span v-for="i in 5" :key="i" class="light-step" :style="{ '--i': i }"></span>
          <span class="north-star">✦</span><span class="rising-ray"></span>
        </template>
        <template v-else-if="mood.id === 'wealth'">
          <span v-for="i in 3" :key="i" class="resource-orbit" :style="{ '--i': i }"><span></span></span>
          <span class="gold-seed"></span>
          <span v-for="i in 6" :key="`dust-${i}`" class="gold-dust" :style="particleStyle(i)"></span>
        </template>
        <template v-else-if="mood.id === 'relations'">
          <svg class="constellation" viewBox="0 0 100 100">
            <path d="M22 37L50 22L78 37L68 70L32 70Z M22 37L68 70 M78 37L32 70" />
            <circle v-for="(point, index) in connectionPoints" :key="index" :cx="point[0]" :cy="point[1]" r="3" :style="{ '--i': index }" />
          </svg>
          <span class="connection-wave"></span>
        </template>
        <template v-else-if="mood.id === 'romance'">
          <span class="love-ribbon"></span><span class="love-ribbon second"></span>
          <span class="heart-light"><span></span></span>
          <span v-for="i in 7" :key="i" class="rose-petal" :style="particleStyle(i)"></span>
        </template>
        <template v-else>
          <span class="growth-stem"></span><span class="growth-leaf left"></span><span class="growth-leaf right"></span>
          <span v-for="i in 3" :key="i" class="growth-ripple" :style="{ '--i': i }"></span>
          <span class="dew-light"></span>
        </template>
      </div>
      <div class="glass-reflection"></div><div class="glass-rim"></div>
    </div>
    <span class="orb-pedestal"></span>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
const props = defineProps({ topicId: { type: String, default: "general" }, charge: { type: [String, Number], default: 0 } });
const moods = {
  general: { id: "general", color: "#c9b5ff", soft: "#7c60c84d" },
  career: { id: "career", color: "#93d9ff", soft: "#3978b84d" },
  wealth: { id: "wealth", color: "#f4d18b", soft: "#aa87394d" },
  relations: { id: "relations", color: "#a8e4e5", soft: "#3689954d" },
  romance: { id: "romance", color: "#ffb5cf", soft: "#c65c8c4d" },
  growth: { id: "growth", color: "#a7ecc2", soft: "#4a98714d" },
};
const mood = computed(() => moods[props.topicId] || moods.general);
const connectionPoints = [[22, 37], [50, 22], [78, 37], [68, 70], [32, 70]];
const orb = ref(null), paused = ref(false);
let observer = null, onScreen = true;
function updatePause() { paused.value = document.hidden || !onScreen; }
function particleStyle(index) { return { '--i': index, '--x': `${18 + (index * 31) % 66}%`, '--y': `${20 + (index * 19) % 63}%` }; }
onMounted(() => {
  document.addEventListener("visibilitychange", updatePause);
  observer = new IntersectionObserver(([entry]) => { onScreen = entry.isIntersecting; updatePause(); });
  observer.observe(orb.value); updatePause();
});
onBeforeUnmount(() => { observer?.disconnect(); document.removeEventListener("visibilitychange", updatePause); });
</script>

<style scoped>
.crystal-orb { --charge: 0; position: relative; width: min(232px, 100%); aspect-ratio: 1; isolation: isolate; }
.orb-halo { position: absolute; inset: -12%; border-radius: 50%; background: radial-gradient(circle, var(--orb-soft), transparent 68%); opacity: calc(.65 + var(--charge) * .35); animation: crystal-breathe 6s ease-in-out infinite; }
.orb-orbit { position: absolute; inset: 7%; border: 1px solid var(--orb-soft); border-top-color: var(--orb-color); border-radius: 50%; transform: rotate(-25deg); animation: orbit-turn 22s linear infinite; }
.orb-glass { position: absolute; inset: 15%; border-radius: 50%; overflow: hidden; background: radial-gradient(circle at 38% 28%, #bfc6f633, #0f1830 68%, #060b1e); border: 1px solid #dedcff5e; box-shadow: inset -12px -14px 23px #02091bd9, inset 7px 9px 20px #f7efff22, 0 0 24px var(--orb-soft); }
.orb-mist { position: absolute; inset: -12%; border-radius: 50%; background: radial-gradient(ellipse at 50% 75%, var(--orb-soft), transparent 60%), conic-gradient(from 40deg, transparent, var(--orb-soft), transparent 55%, #ffffff0d, transparent); filter: blur(9px); animation: mist-turn 24s linear infinite; }
.orb-vision { position: absolute; inset: 0; }
.orb-vision span { position: absolute; }
.glass-reflection { position: absolute; left: 18%; top: 11%; width: 36%; height: 18%; border-radius: 50%; background: linear-gradient(145deg, #fff9efba, #ffffff0d); filter: blur(2px); transform: rotate(-32deg); opacity: .5; }
.glass-rim { position: absolute; inset: 3%; border-radius: 50%; border-top: 1px solid #ffffff69; border-left: 1px solid #fff2; box-shadow: inset 0 -5px 14px var(--orb-soft); }
.orb-pedestal { position: absolute; left: 26%; right: 26%; bottom: 10%; height: 8%; border-radius: 50%; border-bottom: 2px solid #d2b98688; background: radial-gradient(ellipse, var(--orb-soft), #0a102000 70%); box-shadow: 0 9px 20px #0006; }
.cosmic-whorl { inset: 27% 13%; border: 1px solid var(--orb-color); border-radius: 50%; transform: rotate(-32deg); box-shadow: 0 0 9px var(--orb-soft); animation: cosmic-tilt 8s ease-in-out infinite alternate; }
.cosmic-whorl.second { inset: 14% 29%; animation-delay: -4s; }
.guiding-star { left: 42%; top: 35%; color: #fff4d5; font-size: 32px; text-shadow: 0 0 17px var(--orb-color); animation: glimmer 4s ease-in-out infinite; }
.star-mote, .gold-dust { left: var(--x); top: var(--y); width: 3px; height: 3px; border-radius: 50%; background: #fff1cf; box-shadow: 0 0 7px var(--orb-color); animation: glimmer 3s ease-in-out infinite; animation-delay: calc(var(--i) * -.6s); }
.light-step { left: calc(13% + var(--i) * 7%); bottom: calc(13% + var(--i) * 10%); width: 30%; height: 6%; border: 1px solid var(--orb-color); transform: skewY(-12deg); background: linear-gradient(90deg, #91daff0a, #91daff65); box-shadow: 0 0 10px var(--orb-soft); animation: step-light 3s ease-in-out infinite; animation-delay: calc(var(--i) * -.35s); }
.north-star { left: 61%; top: 17%; color: #e6f5ff; font-size: 22px; text-shadow: 0 0 12px var(--orb-color); animation: glimmer 3s ease-in-out infinite; }
.rising-ray { left: 23%; bottom: 18%; width: 1px; height: 66%; background: linear-gradient(transparent, var(--orb-color), transparent); transform: rotate(36deg); opacity: .4; }
.resource-orbit { inset: calc(15% + var(--i) * 7%); border: 1px solid #f4d18b77; border-radius: 50%; animation: orbit-turn 9s linear infinite; animation-delay: calc(var(--i) * -2s); }
.resource-orbit:nth-child(2) { animation-direction: reverse; }
.resource-orbit span { left: 42%; top: -4px; width: 8px; height: 8px; border-radius: 50%; background: #f9d98e; box-shadow: 0 0 10px #ecc379; }
.gold-seed { inset: 41%; border: 1px solid #f8db93; transform: rotate(45deg); background: #f0c36955; box-shadow: 0 0 18px #dfad6655; animation: glimmer 4s ease-in-out infinite; }
.constellation { position: absolute; inset: 8%; width: 84%; height: 84%; overflow: visible; }
.constellation path { fill: none; stroke: #a8e4e5; stroke-width: .6; stroke-dasharray: 3 2; opacity: .55; animation: connection-flow 8s linear infinite; }
.constellation circle { fill: #e4ffff; filter: drop-shadow(0 0 3px #b1ffff); animation: node-light 4s ease-in-out infinite; animation-delay: calc(var(--i) * -.6s); }
.connection-wave { inset: 30%; border: 1px solid #c9f6f766; border-radius: 50%; animation: expand-wave 5s ease-out infinite; }
.love-ribbon { inset: 23% 13%; border: 1px solid #ffc7deaa; border-radius: 50%; transform: rotate(-38deg); box-shadow: 0 0 9px #e77eac33; animation: love-sway 7s ease-in-out infinite alternate; }
.love-ribbon.second { transform: rotate(38deg); animation-direction: alternate-reverse; }
.heart-light { inset: 35%; animation: heart-beat 3.6s ease-in-out infinite; }
.heart-light span { width: 53%; height: 53%; left: 25%; top: 28%; transform: rotate(-45deg); background: #f0a9c5; box-shadow: 0 0 24px #f3a0c977; }
.heart-light span::before, .heart-light span::after { content: ""; position: absolute; width: 100%; height: 100%; background: inherit; border-radius: 50%; }
.heart-light span::before { top: -50%; }.heart-light span::after { left: 50%; }
.rose-petal { left: var(--x); top: var(--y); width: 5px; height: 9px; border-radius: 90% 20% 80% 20%; background: #ffc4da88; animation: petal-drift 7s ease-in-out infinite; animation-delay: calc(var(--i) * -1s); }
.growth-stem { left: 49%; top: 35%; width: 2px; height: 40%; border-radius: 50%; background: linear-gradient(#e1ffe9, #69b58f); box-shadow: 0 0 10px #98e5b655; transform-origin: bottom; animation: sprout-sway 6s ease-in-out infinite; }
.growth-leaf { top: 43%; width: 24%; height: 13%; border: 1px solid #d2ffdfaa; background: linear-gradient(20deg, #48856888, #b9f9d877); box-shadow: 0 0 10px #81c79744; }
.growth-leaf.left { left: 27%; border-radius: 90% 0 90% 0; transform: rotate(26deg); animation: leaf-sway 6s ease-in-out infinite; }.growth-leaf.right { left: 50%; top: 32%; border-radius: 0 90% 0 90%; transform: rotate(-26deg); animation: leaf-sway 6s ease-in-out infinite reverse; }
.growth-ripple { left: calc(20% + var(--i) * 4%); right: calc(20% + var(--i) * 4%); bottom: calc(12% + var(--i) * 4%); height: 9%; border: 1px solid #a2edc355; border-radius: 50%; animation: glimmer 4s ease-in-out infinite; animation-delay: calc(var(--i) * -.8s); }
.dew-light { left: 48%; top: 23%; width: 5px; height: 7px; border-radius: 60% 40% 60% 40%; background: #e5fff0; box-shadow: 0 0 10px #b0ffd7; animation: glimmer 4s ease-in-out infinite; }
@keyframes crystal-breathe { 0%,100% { transform: scale(.94); opacity: .55; } 50% { transform: scale(1.06); opacity: .95; } }
@keyframes orbit-turn { to { transform: rotate(360deg); } }
@keyframes mist-turn { to { transform: rotate(-360deg); } }
@keyframes cosmic-tilt { to { transform: rotate(32deg) scale(.85); } }
@keyframes glimmer { 0%,100% { opacity: .35; } 50% { opacity: 1; } }
@keyframes step-light { 0%,100% { opacity: .35; translate: 0 3px; } 50% { opacity: 1; translate: 0 -3px; } }
@keyframes connection-flow { to { stroke-dashoffset: -30; } }
@keyframes node-light { 0%,100% { opacity: .4; } 50% { opacity: 1; } }
@keyframes expand-wave { 0% { transform: scale(.7); opacity: .6; } 100% { transform: scale(2.1); opacity: 0; } }
@keyframes love-sway { to { transform: rotate(38deg) scale(.9); } }
@keyframes heart-beat { 0%,100% { transform: scale(.93); opacity: .8; } 15% { transform: scale(1.08); opacity: 1; } 35% { transform: scale(.98); } 55% { transform: scale(1.05); } }
@keyframes petal-drift { 0%,100% { transform: translate(-3px,8px) rotate(-20deg); opacity: .2; } 50% { transform: translate(5px,-10px) rotate(35deg); opacity: .8; } }
@keyframes sprout-sway { 0%,100% { transform: rotate(-3deg); } 50% { transform: rotate(3deg); } }
@keyframes leaf-sway { 50% { translate: 0 -3px; } }
.paused *, .paused *::before, .paused *::after { animation-play-state: paused !important; }
@media (max-width: 760px) { .crystal-orb { width: 164px; } }
@media (prefers-reduced-motion: reduce) { .crystal-orb *, .crystal-orb *::before, .crystal-orb *::after { animation: none !important; } }
</style>
