<template>
  <div class="moonlit-card-artwork">
    <TarotHoloCard :key="card.variantId" :src="card.image" :alt="`${card.name} · ${card.themeName}`" />
  </div>
</template>
<script setup>
import TarotHoloCard from './TarotHoloCard.vue'
defineProps({ card: { type: Object, required: true } })
</script>
<style scoped>
.moonlit-card-artwork{width:100%;max-width:350px;aspect-ratio:2/3;margin:0 auto}
/* The cover includes the screenshot stage; fill the original card frame with only its artwork. */
.moonlit-card-artwork :deep(.tarot-art){transform:translate3d(var(--art-offset-x),var(--art-offset-y),2px) scale(1.08)}
@media(prefers-reduced-motion:reduce){.moonlit-card-artwork :deep(.tarot-art){transform:scale(1.08)}}
</style>
