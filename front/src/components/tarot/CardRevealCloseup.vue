<template>
  <NModal :show="show" :mask-closable="false" :auto-focus="false" :appear="true" :close-on-esc="true"
    @after-enter="continueButton?.focus({ preventScroll: true })"
    @update:show="value => !value && $emit('cancel')" @after-leave="$emit('after-leave')">
    <div class="card-closeup" role="dialog" aria-modal="true" :aria-labelledby="`closeup-title-${order}`">
      <p class="closeup-position">第 {{ order + 1 }} 张 · {{ positionLabel }}</p>
      <div class="closeup-art" :style="originStyle">
        <TarotFlipCard :card="card" :position-label="positionLabel" :order="order" :revealed="turned" disabled @manifested="labelsReady = true" />
      </div>
      <h2 :class="{ 'closeup-sr-only': !labelsReady }" :id="`closeup-title-${order}`" aria-live="polite">{{ labelsReady ? `${card.name} · ${orientationLabel(card.orientation)}` : '月光正在揭开牌面…' }}</h2>
      <p v-if="labelsReady" class="closeup-theme">{{ card.theme?.themeName }}</p>
      <button ref="continueButton" type="button" class="closeup-continue" @click="$emit('close')">
        {{ hasNext ? '查看下一张' : '回到牌阵' }}
      </button>
    </div>
  </NModal>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { NModal } from "naive-ui";
import TarotFlipCard from "./TarotFlipCard.vue";
import { orientationLabel } from "@/utils/tarotReading";
const props = defineProps({ show: Boolean, card: { type: Object, required: true },
  positionLabel: { type: String, required: true }, order: Number, origin: Object, hasNext: Boolean });
defineEmits(["close", "cancel", "after-leave"]);
const turned = ref(false), labelsReady = ref(false), continueButton = ref(null);
let timer;
watch(continueButton, element => element?.focus({ preventScroll: true }), { flush: "post" });
const originStyle = computed(() => {
  const from = props.origin;
  if (!from) return {};
  return { '--from-x': `${from.left + from.width / 2 - window.innerWidth / 2}px`,
    '--from-y': `${from.top + from.height / 2 - window.innerHeight / 2 + 24}px`,
    '--from-scale': Math.min(1, from.width / Math.min(window.innerWidth * .7, 320, (window.innerHeight - 240) * 2 / 3)) };
});
onMounted(async () => {
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduced) turned.value = true;
  else timer = window.setTimeout(() => { turned.value = true; }, 380);
  await nextTick();
  continueButton.value?.focus({ preventScroll: true });
});
onBeforeUnmount(() => window.clearTimeout(timer));
</script>

<style scoped>
.card-closeup { width: min(92vw, 440px); max-height: calc(100dvh - 24px); overflow-y: auto; overflow-x: hidden; padding: 12px 16px; box-sizing: border-box; text-align: center; color: #fff1d5; background: radial-gradient(ellipse, #241c42ee, #0b1022f5); border: 1px solid #e0be7866; border-radius: 24px; box-shadow: 0 28px 100px #000b; }
.closeup-position { margin: 4px 0 16px; color: #e0be78; font-size: 14px; letter-spacing: .1em; }
.closeup-art { width: min(70vw, 320px, calc((100dvh - 240px) * 2 / 3)); aspect-ratio: 2 / 3; margin: 0 auto; animation: card-spotlight .65s cubic-bezier(.2,.8,.2,1) both; }
@keyframes card-spotlight { from { opacity: .4; transform: translate(var(--from-x, 0px), var(--from-y, 40px)) scale(var(--from-scale, .6)); } to { opacity: 1; transform: translate(0, 0) scale(1); } }
h2 { margin: 18px 0 4px; font: 500 22px/1.4 "Moonlit Serif", serif; }
.closeup-theme { margin: 0 0 14px; color: #c4b8d9; font-size: 13px; }
.closeup-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.closeup-continue { margin-top: 14px; min-height: 44px; padding: 10px 28px; border: 1px solid #f0d499; border-radius: 99px; background: linear-gradient(120deg, #f6dc9c, #d6a95c); color: #1b1428; font-weight: 700; cursor: pointer; }
.closeup-continue:focus-visible { outline: 2px solid #fff1cc; outline-offset: 4px; }
@media (prefers-reduced-motion: reduce) { .closeup-art { animation: none; } }
</style>
