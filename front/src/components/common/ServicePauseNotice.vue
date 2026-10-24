<template>
  <div v-if="pausedNames.length" class="service-pause-notice" role="status">
    <div class="container notice-inner">
      <strong>服务调整</strong>
      <span>{{ pausedNames.join('、') }}暂停服务，服务器资源正优先保障论文翻译。</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const FEATURE_NAMES = { Contact: 'PPT 生成', ImageGenerate: 'GPT 生图', Publications: '文献库' }

const auth = useAuthStore()
const pausedNames = computed(() => auth.pausedFeatures.map(feature => FEATURE_NAMES[feature]).filter(Boolean))
</script>

<style scoped>
.service-pause-notice {
  background: var(--desk-tint);
  border-bottom: 1px solid var(--desk-border);
  color: var(--desk-text);
  font-size: 14px;
  line-height: 1.6;
}

.notice-inner {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding-top: 10px;
  padding-bottom: 10px;
}

.notice-inner strong {
  color: var(--desk-accent);
  white-space: nowrap;
}

.notice-inner span {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
