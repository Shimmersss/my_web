<template>
  <section class="reading-progress" :class="task.status" aria-labelledby="reading-progress-title">
    <div class="orbits" aria-hidden="true">
      <span class="orbit orbit-1"><i></i></span>
      <span class="orbit orbit-2"><i></i></span>
      <span class="orbit orbit-3"><i></i></span>
      <span class="orbit-core"></span>
    </div>
    <div class="progress-copy">
      <p class="tarot-eyebrow">AI READING · #{{ String(task.id || "").slice(0, 8) }}</p>
      <h2 id="reading-progress-title">{{ stageText }}</h2>
      <div class="progress-track" role="progressbar" aria-label="AI 深度解读进度" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="progress">
        <span :style="{ transform: `scaleX(${progress / 100})` }"></span>
      </div>
      <p aria-live="polite">{{ message }}</p>
      <p v-if="pollError" class="poll-error" role="status">{{ pollError }}</p>
      <router-link v-if="task.status === 'done' && task.reportId" class="tarot-button primary" :to="`/tarot-reading/report/${task.reportId}`">打开占卜报告 <span aria-hidden="true">→</span></router-link>
    </div>
  </section>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  task: { type: Object, required: true },
  pollError: { type: String, default: "" },
});

const STAGES = {
  queued: ["正在等待星盘转动", 8],
  preparing: ["正在整理牌阵与问题", 26],
  writing: ["正在解读牌面的低语", 62],
  saving: ["正在把解读收进记录", 92],
  done: ["解读已经完成", 100],
  error: ["这次解读没有完成", 100],
};
const stageText = computed(() => STAGES[props.task.stage]?.[0] || "正在准备…");
const progress = computed(() => STAGES[props.task.stage]?.[1] ?? 0);
const message = computed(() => {
  if (props.task.status === "error") return props.task.error || "正在处理补偿，请稍后查看。";
  if (props.task.status === "done") return "报告已保存，你可以随时回来阅读。";
  return "可以离开这里，服务器会继续完成解读；回来后会接着显示进度。";
});
</script>

<style scoped>
.reading-progress {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 26px;
  align-items: center;
  padding: 26px 28px;
  border: 1px solid #e0be7866;
  border-radius: 20px;
  background: linear-gradient(135deg, #141c36ee, #2a2340ee);
}
.orbits { position: relative; width: 120px; aspect-ratio: 1; }
.orbit { position: absolute; border: 1px solid #e0be7855; border-radius: 50%; animation: orbit-spin linear infinite; }
.orbit i { position: absolute; top: -4px; left: calc(50% - 4px); width: 8px; height: 8px; border-radius: 50%; background: #f6dc9c; box-shadow: 0 0 10px #f6dc9c; }
.orbit-1 { inset: 0; animation-duration: 6s; }
.orbit-2 { inset: 16px; animation-duration: 4.2s; animation-direction: reverse; }
.orbit-2 i { background: #c8bcff; box-shadow: 0 0 10px #c8bcff; }
.orbit-3 { inset: 32px; animation-duration: 2.8s; }
.orbit-core { position: absolute; inset: 46px; border-radius: 50%; background: radial-gradient(circle at 40% 35%, #fff6d8, #e0be78 60%, #8f6a2f); }
.done .orbit, .error .orbit { animation-play-state: paused; }
.error .orbit-core { background: radial-gradient(circle at 40% 35%, #ffe2d8, #c97a6c 60%, #6d3530); }
@keyframes orbit-spin { to { transform: rotate(360deg); } }

.progress-copy h2 { margin: 0; color: #fff4dc; font: 500 25px/1.3 "Moonlit Serif", serif; }
.progress-copy p:not(.tarot-eyebrow) { margin: 12px 0 0; color: #c7cbd8; font-size: 14px; line-height: 1.7; }
.progress-track { height: 6px; margin-top: 16px; overflow: hidden; border-radius: 99px; background: #ffffff14; }
.progress-track span { display: block; height: 100%; background: linear-gradient(90deg, #8f7cff, #f6dc9c); transform-origin: left; transition: transform .6s ease; }
.poll-error { color: #f2c9a8 !important; }
.progress-copy .tarot-button { display: inline-flex; margin-top: 16px; }

@media (max-width: 600px) {
  .reading-progress { grid-template-columns: 1fr; justify-items: center; text-align: center; padding: 22px 18px; }
  .progress-copy { width: 100%; }
}
@media (prefers-reduced-motion: reduce) {
  .orbit { animation: none; }
  .progress-track span { transition: none; }
}
</style>
