<template>
  <div v-if="customAnnouncement || pausedNames.length" class="service-pause-notice" :class="{ 'is-info': !pausedNames.length && customAnnouncement?.tone !== 'warning' }" role="status">
    <div class="container notice-inner">
      <p v-if="customAnnouncement" class="notice-row">
        <strong>{{ customAnnouncement.title || '公告' }}</strong>
        <span>{{ customAnnouncement.content }}</span>
      </p>
      <p v-if="pausedNames.length" class="notice-row">
        <strong>服务调整</strong>
        <span>{{ pausedNames.join('、') }}暂停服务，服务器资源正优先保障论文翻译。</span>
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const FEATURE_NAMES = { Contact: 'PPT 生成', ImageGenerate: 'GPT 生图', Publications: '文献库' }

const auth = useAuthStore()
const pausedNames = computed(() => auth.pausedFeatures.map(feature => FEATURE_NAMES[feature]).filter(Boolean))
// Root-configured banner text; rendered as plain text only.
const customAnnouncement = computed(() => auth.announcement?.enabled && auth.announcement.content ? auth.announcement : null)
</script>

<style scoped>
.service-pause-notice {
  background: var(--desk-tint);
  border-bottom: 1px solid var(--desk-border);
  color: var(--desk-text);
  font-size: 14px;
  line-height: 1.6;
}

.service-pause-notice.is-info {
  background: var(--desk-soft);
}

.notice-inner {
  display: grid;
  gap: 2px;
  padding-top: 10px;
  padding-bottom: 10px;
}

.notice-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0;
}

.notice-row strong {
  color: var(--desk-accent);
  white-space: nowrap;
}

.is-info .notice-row strong {
  color: var(--desk-text);
}

.notice-row span {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
