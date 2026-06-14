<template>
  <div class="ppt-page tool-page">
    <div class="container">
      <div class="tool-page__header ppt-page-header">
        <div>
          <h1>PPT 生成</h1>
          <p>描述生成目标，可选上传论文或模板，后台直接生成可编辑 PPTX。</p>
        </div>
        <n-tag type="info">公开入口</n-tag>
      </div>
      <section class="workspace">
        <div class="workspace-main">
          <section v-if="step === 'form'" class="panel">
            <div class="panel-header">
              <ol class="flow-steps" aria-label="PPT 生成流程">
                <li><strong>1</strong><span>描述需求</span></li>
                <li><strong>2</strong><span>可选上传资料</span></li>
                <li><strong>3</strong><span>生成 PPTX</span></li>
              </ol>
            </div>

            <div class="field-block">
              <div class="field-label">通用模板</div>
              <div class="template-grid">
                <button
                  v-for="template in templates"
                  :key="template.key"
                  type="button"
                  :class="['template-card', { active: templateKey === template.key }]"
                  :aria-pressed="templateKey === template.key"
                  @click="templateKey = template.key"
                >
                  <div class="swatches">
                    <span v-for="color in template.palette" :key="color" :style="{ backgroundColor: `#${color}` }"></span>
                  </div>
                  <strong>{{ template.name }}</strong>
                  <small>{{ template.description }}</small>
                </button>
              </div>
            </div>

            <div class="upload-grid">
              <label class="file-box">
                <input type="file" accept=".pptx" aria-label="上传自定义 PPT 模板" @change="handleTemplateSelect" />
                <n-icon size="34"><EaselOutline /></n-icon>
                <strong>{{ templateFile ? templateFile.name : '上传自定义 PPT 模板' }}</strong>
                <span>可选；作为 PPTX 底稿和布局来源</span>
              </label>
              <label class="file-box">
                <input type="file" accept=".pdf,.docx" aria-label="选择论文文件" @change="handlePaperSelect" />
                <n-icon size="34"><DocumentTextOutline /></n-icon>
                <strong>{{ paperFile ? paperFile.name : '选择论文文件' }}</strong>
                <span>可选，支持 .pdf / .docx，最大 30MB</span>
              </label>
            </div>

            <div class="field-block">
              <div class="field-label range-label">
                <span>素材提取比例</span>
                <strong>{{ extractionPercent }}%</strong>
              </div>
              <input v-model.number="extractionPercent" class="range-control" type="range" min="10" max="100" step="10" aria-label="素材提取比例" aria-describedby="extraction-hint" />
              <p id="extraction-hint" class="field-hint">影响 PDF 页面图、DOCX 图片和 PPTX 模板扫描数量；比例越高，保留素材越多。</p>
            </div>

            <div class="field-block">
              <div class="field-label">生成提示词</div>
              <n-input
                v-model:value="prompt"
                type="textarea"
                aria-label="PPT 生成提示词"
                :autosize="{ minRows: 8, maxRows: 14 }"
                maxlength="8000"
                show-count
                placeholder="例如：根据论文生成 12 页本科毕业答辩 PPT，风格正式清爽，突出研究背景、方法、实验结果和结论展望。"
              />
            </div>

            <div class="actions">
              <n-button type="primary" size="large" :loading="isSubmitting" @click="submitTask">
                <template #icon><n-icon><SparklesOutline /></n-icon></template>
                直接生成 PPT
              </n-button>
              <n-button size="large" :disabled="isSubmitting" @click="resetForm">
                <template #icon><n-icon><RefreshOutline /></n-icon></template>
                清空
              </n-button>
            </div>

            <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" />
          </section>

          <section v-else-if="step === 'running'" class="panel progress-panel">
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
              <n-button @click="backToForm">返回表单</n-button>
            </div>
            <n-alert v-if="errorMsg" type="error" :title="errorMsg" />
          </section>

          <section v-else class="panel result-panel">
            <div class="result-mark">
              <n-icon size="42"><EaselOutline /></n-icon>
            </div>
            <h2>PPTX 已生成</h2>
            <p>{{ activeTask?.outputFileName || '可以下载生成结果。' }}</p>
            <div class="actions centered">
              <n-button type="primary" size="large" @click="downloadCurrent">
                <template #icon><n-icon><DownloadOutline /></n-icon></template>
                下载 PPTX
              </n-button>
              <n-button size="large" @click="backToForm">
                再生成一份
              </n-button>
            </div>
          </section>
        </div>

        <aside class="recent-panel">
          <div class="recent-header">
            <div>
              <h2>最近任务</h2>
              <p>保留最近 5 条生成记录</p>
            </div>
            <n-button size="small" :loading="isLoadingRecent" @click="loadRecent">刷新</n-button>
          </div>
          <n-alert v-if="recentError" type="warning" :title="recentError" class="compact-alert" />
          <div v-if="recentTasks.length" class="recent-list">
            <button v-for="item in recentTasks" :key="item.taskId" type="button" class="recent-item" @click="openRecent(item)">
              <strong>{{ recentTitle(item) }}</strong>
              <span>{{ formatTime(item.createdAt) }}</span>
              <n-tag size="small" :type="tagType(item.status)">{{ statusLabel(item) }}</n-tag>
            </button>
          </div>
          <n-empty v-else-if="!isLoadingRecent" description="暂无 PPT 生成记录" />
        </aside>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { NAlert, NButton, NEmpty, NIcon, NInput, NProgress, NTag } from 'naive-ui'
import {
  ColorPaletteOutline,
  DocumentTextOutline,
  DownloadOutline,
  EaselOutline,
  RefreshOutline,
  SparklesOutline,
  TimeOutline
} from '@vicons/ionicons5'
import {
  createPptGenerationTask,
  downloadGeneratedPpt,
  getPptGenerationStatus,
  getPptTemplates,
  getRecentPptGenerations
} from '@/api'

const message = useMessage()
const step = ref('form')
const prompt = ref('')
const templateKey = ref('academic-blue')
const extractionPercent = ref(50)
const templateFile = ref(null)
const paperFile = ref(null)
const templates = ref(defaultTemplates())
const isSubmitting = ref(false)
const isLoadingRecent = ref(false)
const recentTasks = ref([])
const recentError = ref('')
const errorMsg = ref('')
const activeTask = ref(null)
const taskId = ref('')
const taskAccessToken = ref('')
const progress = ref(0)
const progressStage = ref('queued')
const progressStageLabel = ref('等待后台生成')
const queuePosition = ref(0)
let eventSource = null
let pollTimer = null
const PPT_TASK_TOKENS_KEY = 'ppt-generation-task-tokens'

const stageItems = [
  { key: 'queued', label: '排队', icon: TimeOutline },
  { key: 'extracting', label: '读取资料', icon: DocumentTextOutline },
  { key: 'planning', label: '规划内容', icon: ColorPaletteOutline },
  { key: 'rendering', label: '生成 PPTX', icon: EaselOutline }
]

const runningTitle = computed(() => activeTask.value?.paperFileName || activeTask.value?.templateFileName || 'PPT 生成任务')

onMounted(async () => {
  await Promise.all([loadTemplates(), loadRecent()])
})

onBeforeUnmount(() => {
  closeStream()
  stopPolling()
})

function handleTemplateSelect(event) {
  templateFile.value = event.target.files?.[0] || null
}

function handlePaperSelect(event) {
  paperFile.value = event.target.files?.[0] || null
}

async function submitTask() {
  errorMsg.value = ''
  if (!prompt.value.trim()) {
    errorMsg.value = '请输入 PPT 生成提示词'
    return
  }
  isSubmitting.value = true
  try {
    const res = await createPptGenerationTask({
      prompt: prompt.value.trim(),
      templateKey: templateKey.value,
      extractionPercent: extractionPercent.value,
      templateFile: templateFile.value,
      paperFile: paperFile.value
    })
    rememberTaskToken(res.data.taskId, res.data.accessToken)
    setActiveTask(res.data)
    step.value = 'running'
    openStream(taskId.value)
    await loadRecent()
  } catch (error) {
    errorMsg.value = error.message || 'PPT 生成任务提交失败'
  } finally {
    isSubmitting.value = false
  }
}

function openStream(id) {
  closeStream()
  eventSource = new EventSource(`/api/ppt-generate/stream/${id}?accessToken=${encodeURIComponent(taskAccessToken.value)}`)
  eventSource.addEventListener('queued', event => {
    const data = parseEvent(event)
    queuePosition.value = data.queuePosition || 0
    progressStage.value = 'queued'
    progressStageLabel.value = data.message || '任务正在等待后台处理'
  })
  eventSource.addEventListener('progress', event => {
    const data = parseEvent(event)
    progress.value = Number(data.progress || progress.value || 0)
    progressStage.value = data.stage || progressStage.value
    progressStageLabel.value = data.stageLabel || data.message || progressStageLabel.value
    queuePosition.value = 0
  })
  eventSource.addEventListener('done', async () => {
    progress.value = 100
    closeStream()
    await refreshStatus()
    await loadRecent()
    step.value = 'result'
    message.success('PPTX 已生成')
  })
  eventSource.addEventListener('task-error', async event => {
    const data = parseEvent(event)
    errorMsg.value = data.message || 'PPT 生成失败'
    closeStream()
    await refreshStatus()
    await loadRecent()
  })
  eventSource.onerror = () => {
    closeStream()
    startPolling()
  }
}

async function openRecent(item) {
  closeStream()
  stopPolling()
  setActiveTask(item)
  errorMsg.value = item.errorMessage || ''
  if (item.status === 'completed') {
    step.value = 'result'
  } else {
    step.value = 'running'
    if (item.status !== 'error') openStream(item.taskId)
  }
}

async function refreshStatus() {
  if (!taskId.value) return
  try {
    const res = await getPptGenerationStatus(taskId.value, taskAccessToken.value)
    setActiveTask(res.data)
    if (res.data.status === 'completed') {
      stopPolling()
      step.value = 'result'
    } else if (res.data.status === 'error') {
      stopPolling()
      errorMsg.value = res.data.errorMessage || 'PPT 生成失败'
    }
  } catch (error) {
    errorMsg.value = error.message || '任务状态恢复失败'
  }
}

async function loadTemplates() {
  try {
    const res = await getPptTemplates()
    if (Array.isArray(res.data) && res.data.length) templates.value = res.data
  } catch {
    templates.value = defaultTemplates()
  }
}

async function loadRecent() {
  isLoadingRecent.value = true
  recentError.value = ''
  try {
    const res = await getRecentPptGenerations(Object.values(readTaskTokens()))
    recentTasks.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    recentError.value = error.message || '最近任务加载失败'
  } finally {
    isLoadingRecent.value = false
  }
}

function setActiveTask(data) {
  activeTask.value = data
  taskId.value = data.taskId
  taskAccessToken.value = data.accessToken || tokenForTask(data.taskId)
  templateKey.value = data.templateKey || templateKey.value
  extractionPercent.value = Number(data.extractionPercent || extractionPercent.value || 50)
  progress.value = Number(data.progress || 0)
  progressStage.value = data.progressStage || 'queued'
  progressStageLabel.value = data.progressStageLabel || statusLabel(data)
  queuePosition.value = data.queuePosition || 0
}

function downloadCurrent() {
  if (activeTask.value?.status === 'completed' && taskId.value) downloadGeneratedPpt(taskId.value, taskAccessToken.value)
}

function resetForm() {
  prompt.value = ''
  templateFile.value = null
  paperFile.value = null
  extractionPercent.value = 50
  errorMsg.value = ''
}

function backToForm() {
  closeStream()
  stopPolling()
  step.value = 'form'
  activeTask.value = null
  taskId.value = ''
  taskAccessToken.value = ''
  progress.value = 0
  queuePosition.value = 0
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(refreshStatus, 2500)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function closeStream() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function parseEvent(event) {
  try {
    return JSON.parse(event.data)
  } catch {
    return {}
  }
}

function readTaskTokens() {
  try {
    return JSON.parse(sessionStorage.getItem(PPT_TASK_TOKENS_KEY) || '{}')
  } catch {
    return {}
  }
}

function rememberTaskToken(id, accessToken) {
  if (!id || !accessToken) return
  const tokens = readTaskTokens()
  delete tokens[id]
  tokens[id] = accessToken
  const recentEntries = Object.entries(tokens).slice(-20)
  sessionStorage.setItem(PPT_TASK_TOKENS_KEY, JSON.stringify(Object.fromEntries(recentEntries)))
}

function tokenForTask(id) {
  return readTaskTokens()[id] || ''
}

function defaultTemplates() {
  return [
    { key: 'academic-blue', name: '学术蓝', description: '正式、清爽，适合论文答辩', palette: ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937'] },
    { key: 'minimal-ink', name: '极简黑白', description: '高对比、少装饰，适合技术分享', palette: ['111827', '374151', '0EA5E9', 'F8FAFC', '1F2937'] },
    { key: 'emerald-report', name: '数据绿', description: '沉稳、偏报告感，适合项目汇报', palette: ['047857', '064E3B', 'F59E0B', 'ECFDF5', '1F2937'] },
    { key: 'warm-defense', name: '暖色答辩', description: '温和醒目，适合毕业答辩', palette: ['B45309', '7C2D12', '2563EB', 'FFF7ED', '1F2937'] }
  ]
}

function recentTitle(item) {
  return item.paperFileName || item.templateFileName || trimPrompt(item.prompt)
}

function trimPrompt(value) {
  const text = value || '仅提示词生成'
  return text.length > 28 ? `${text.slice(0, 28)}...` : text
}

function statusLabel(item) {
  if (item.status === 'completed') return '已完成'
  if (item.status === 'error') return '失败'
  if (item.status === 'generating') return item.progressStageLabel || '生成中'
  return item.queuePosition > 0 ? `排队第 ${item.queuePosition} 位` : '排队中'
}

function tagType(status) {
  if (status === 'completed') return 'success'
  if (status === 'error') return 'error'
  if (status === 'generating') return 'info'
  return 'warning'
}

function formatTime(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>

<style scoped lang="scss">
.ppt-page {
  background: #f6f8fb;
}

.container {
  width: min(1180px, calc(100% - 32px));
  margin: 0 auto;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 20px;
  align-items: start;
}

.ppt-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel,
.recent-panel {
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
}

.panel {
  padding: 24px;
}

.panel-header,
.recent-header,
.progress-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.flow-steps {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.flow-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #475569;
  font-size: 14px;
}

.flow-steps strong {
  width: 24px;
  height: 24px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  color: #1d4ed8;
  background: #dbeafe;
  font-size: 12px;
}

h1,
h2,
h3 {
  margin: 0;
  color: #0f172a;
}

p {
  margin: 6px 0 0;
  color: #64748b;
}

.field-block {
  margin-top: 22px;
}

.field-label {
  margin-bottom: 10px;
  font-weight: 700;
  color: #334155;
}

.template-grid,
.upload-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.template-card,
.file-box {
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #fff;
  padding: 16px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  min-width: 0;
}

.template-card.active {
  border-color: #2563eb;
  background: #eff6ff;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.template-card:focus-visible,
.file-box:focus-within,
.range-control:focus-visible {
  outline: 3px solid rgba(37, 99, 235, 0.28);
  outline-offset: 3px;
}

.template-card strong,
.file-box strong {
  display: block;
  margin-top: 8px;
  color: #0f172a;
  overflow-wrap: anywhere;
}

.template-card small,
.file-box span,
.field-hint {
  display: block;
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}

.swatches {
  display: flex;
  gap: 5px;
}

.swatches span {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.file-box {
  position: relative;
  min-height: 138px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
}

.file-box input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.range-label {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.range-control {
  width: 100%;
  accent-color: #2563eb;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
  flex-wrap: wrap;
}

.actions :deep(.n-button) {
  min-width: 0;
}

.actions.centered {
  justify-content: center;
}

.progress-panel,
.result-panel {
  min-height: 420px;
}

.progress-title {
  justify-content: flex-start;
  margin-bottom: 22px;
}

.stage-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 22px;
}

.stage-item {
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  padding: 14px 10px;
  color: #64748b;
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
}

.stage-item.active {
  color: #2563eb;
  border-color: #bfdbfe;
  background: #eff6ff;
}

.result-panel {
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.result-mark {
  width: 82px;
  height: 82px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: #ecfdf5;
  color: #047857;
  margin-bottom: 18px;
}

.recent-panel {
  padding: 18px;
  position: sticky;
  top: 88px;
}

.recent-header {
  align-items: center;
  margin-bottom: 14px;
}

.recent-header h2 {
  font-size: 18px;
}

.recent-list {
  display: grid;
  gap: 10px;
}

.recent-item {
  width: 100%;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 10px;
  text-align: left;
  cursor: pointer;
  min-width: 0;
}

.recent-item strong {
  color: #0f172a;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.recent-item span {
  color: #64748b;
  font-size: 12px;
}

.compact-alert {
  margin-bottom: 12px;
}

@media (max-width: 980px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .recent-panel {
    position: static;
  }
}

@media (max-width: 720px) {
  .container {
    width: min(100% - 20px, 1180px);
  }

  .panel {
    padding: 18px;
  }

  .panel-header,
  .recent-header {
    flex-direction: column;
  }

  .ppt-page-header {
    flex-direction: column;
  }

  .flow-steps {
    grid-template-columns: 1fr;
  }

  .template-grid,
  .upload-grid,
  .stage-grid {
    grid-template-columns: 1fr;
  }

  .actions {
    display: grid;
    grid-template-columns: 1fr;
  }

  .actions :deep(.n-button) {
    width: 100%;
  }

  .progress-title {
    align-items: center;
  }

  .stage-item {
    justify-content: flex-start;
  }

  .recent-item {
    grid-template-columns: 1fr;
  }
}
</style>
