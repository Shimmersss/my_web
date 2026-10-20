<template>
  <span class="tarot-card-back" :class="{ sheen }" :style="{ backgroundImage: CARD_BACK_URL }" aria-hidden="true"></span>
</template>

<script setup>
import { CARD_BACK_URL } from "./cardBack";

defineProps({ sheen: { type: Boolean, default: true } });
</script>

<style scoped>
.tarot-card-back {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 9px;
  background-color: #10172b;
  background-size: cover;
  background-position: center;
  box-shadow: 0 6px 16px #04081499, inset 0 0 0 1px #f2d79a33;
}

.tarot-card-back::after {
  content: "";
  position: absolute;
  inset: -20% -60%;
  background: linear-gradient(105deg, transparent 38%, #fff3d02e 47%, #fff8e455 50%, #fff3d02e 53%, transparent 62%);
  transform: translateX(-60%);
  opacity: 0;
}

.sheen::after {
  opacity: 1;
  animation: card-sheen 5.5s ease-in-out infinite;
}

@keyframes card-sheen {
  0%, 55% { transform: translateX(-60%); }
  85%, 100% { transform: translateX(60%); }
}

@media (prefers-reduced-motion: reduce) {
  .sheen::after { animation: none; opacity: 0; }
}
</style>
