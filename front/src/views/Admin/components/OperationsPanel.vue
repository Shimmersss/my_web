<template>
  <section class="operations" aria-labelledby="operations-title">
    <div class="heading">
      <div><h2 id="operations-title">任务运行状态</h2><p role="status">{{ state?.accepting ? '正常接单' : state ? '维护中，暂停新任务' : '正在读取' }}</p></div>
      <button :disabled="busy || !state" @click="changeAdmission">{{ state?.accepting ? '暂停接单' : '恢复接单' }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="state">正在接单 {{ state.admitting }} · 本地重任务 {{ state.heavyActive }} · AI请求 {{ state.networkActive }} · 等待许可 {{ state.waiting }}</p>
    <div class="modules">
      <article v-for="(module, name) in state?.modules" :key="name">
        <h3>{{ names[name] || name }}</h3>
        <p>排队 {{ module.queued ?? '—' }} · 运行 {{ module.running ?? '—' }}</p>
        <p>待补偿 {{ module.pendingCompensation ?? '—' }} · 待保存 {{ module.pendingSnapshots ?? 0 }}</p>
        <small>{{ module.workerAvailable ? 'Worker 已启动' : 'Worker 未就绪' }}</small>
      </article>
    </div>
  </section>
</template>
<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getOperations, updateAdmission } from '@/api'
import { createTaskPoller } from '@/utils/taskPoller'
const state = ref(null), error = ref(''), busy = ref(false)
const names = { translation: '翻译', ppt: '演示生成', image: '图片生成', imagegen: '图片生成', matchmaking: '婚恋' }
let disposed = false
const controller = new AbortController()
const observer = createTaskPoller({ fetchTask: (_, options) => getOperations(options),
  onTask: data => { state.value = data }, onError: text => { error.value = text },
  isTerminal: () => false, interval: 5000 })
onMounted(() => observer.start('operations'))
onBeforeUnmount(() => { disposed = true; observer.stop(); controller.abort() })
async function changeAdmission() {
  if (busy.value) return
  busy.value = true
  try {
    const result = await updateAdmission(state.value.accepting ? 'pause' : 'resume', { signal: controller.signal })
    if (!disposed) state.value = result.data
  } catch (e) { if (!disposed) error.value = e.message }
  finally { busy.value = false }
}
</script>
<style scoped>
.operations { padding: 24px; margin: 24px 0; border: 1px solid #d8d2c6; border-radius: 16px; background: #fffdf7; }
.heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
h2,h3 { margin: 0; } p { line-height: 1.6; }
button { border: 1px solid #958771; border-radius: 8px; padding: 10px 16px; background: #eee7db; cursor: pointer; }
.modules { display: grid; grid-template-columns: repeat(auto-fit,minmax(min(100%,200px),1fr)); gap: 12px; }
article { min-width: 0; padding: 16px; background: #f4f0e8; border-radius: 8px; }
@media(max-width:400px) { .operations { padding: 16px; } }
</style>
