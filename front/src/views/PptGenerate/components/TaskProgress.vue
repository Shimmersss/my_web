<template>
<section class="panel progress-panel">
            <div class="progress-title" role="status" aria-live="polite">
              <n-icon size="32"><TimeOutline /></n-icon>
              <div>
                <h2>{{ runningTitle }}</h2>
                <p v-if="queuePosition > 0">正在排队，第 {{ queuePosition }} 位</p>
                <p v-else>{{ progressStageLabel }}</p>
              </div>
            </div>
            <n-progress type="line" :percentage="Math.round(progress)" :processing="progress < 100" />
            <div class="stage-grid">
              <div v-for="item in stageItems" :key="item.key" :class="['stage-item', { active: item.key === progressStage }]">
                <n-icon><component :is="item.icon" /></n-icon>
                <span>{{ item.label }}</span>
              </div>
            </div>
            <div class="actions">
              <n-button @click="$emit('back')">返回表单</n-button>
              <n-button type="error" secondary @click="$emit('cancel')">取消本次任务</n-button>
            </div>
          </section>
</template>
<script setup>
import { NButton, NIcon, NProgress } from 'naive-ui'
import { TimeOutline } from '@vicons/ionicons5'
defineProps({ runningTitle: String, queuePosition: Number, progressStageLabel: String,
  progress: Number, stageItems: Array, progressStage: String })
defineEmits(['back', 'cancel'])
</script>
<style scoped>
.progress-title { display: flex; align-items: flex-start; justify-content: flex-start; gap: 16px; margin-bottom: 22px; }
.stage-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap:12px; margin-top:22px; }
.stage-item { border:1px solid #e5eaf2; border-radius:8px; padding:14px 10px; color:#64748b; display:flex; gap:8px; align-items:center; justify-content:center; }
.stage-item.active { color:#b83126; border-color:#d9b5ab; background:#f3eadf; }
.actions { display:flex; gap:12px; margin-top:24px; flex-wrap:wrap; }
.actions :deep(.n-button) { min-width:0; }
@media(max-width:768px) { .stage-grid { grid-template-columns:1fr; } }
@media(max-width:480px) { .actions { display:grid; grid-template-columns:1fr; } .actions :deep(.n-button) { width:100%; } .progress-title { align-items:center; } .stage-item { justify-content:flex-start; } }
</style>
