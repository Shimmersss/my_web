<template>
  <button class="moonlit-tile" type="button" :class="{ locked: !count }" :aria-label="label" @click="$emit('select', card)">
    <span v-if="count" class="tile-art-frame"><img class="tile-art" :src="card.image" :alt="card.name" loading="lazy" /></span>
    <span v-else class="locked-back" aria-hidden="true"><i>✦</i><small>未收藏</small></span>
    <span class="tile-copy"><strong>{{ card.name }}</strong><small>NO. {{ card.number }} · {{ card.ownedThemes }} / 5 主题</small></span>
  </button>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ card: { type: Object, required: true }, count: { type: Number, default: 0 } })
defineEmits(['select'])
const label = computed(() => `查看${props.card.name}的五个主题，已收藏${props.card.ownedThemes}个主题`)
</script>
<style scoped>
.moonlit-tile{min-width:0;padding:0;border:0;background:transparent;color:#fff9eb;cursor:pointer;text-align:center}.moonlit-tile:focus-visible{outline:3px solid #efd38d;outline-offset:5px;border-radius:13px}.tile-art-frame,.locked-back{display:block;width:100%;aspect-ratio:2/3;border-radius:10px}.tile-art-frame{overflow:hidden}.tile-art{display:block;width:100%;height:100%;object-fit:cover;transform:scale(1.08)}.locked-back{box-sizing:border-box;display:grid;place-content:center;gap:8px;border:1px solid #b99455;background:radial-gradient(circle at 50% 34%,#30395b,#11172b 68%);box-shadow:inset 0 0 0 5px #151c34,inset 0 0 0 6px #c7a86a66;color:#d9bb78}.locked-back i{font-style:normal;font-size:28px}.locked-back small{font-size:10px}.tile-copy{display:block;margin-top:9px}.tile-copy strong,.tile-copy small{display:block;overflow-wrap:anywhere}.tile-copy strong{font:500 14px/1.4 "Moonlit Serif",serif}.tile-copy small{margin-top:4px;color:#9da4bc;font-size:10px}.locked .tile-copy{color:#c8c9d3}
</style>
