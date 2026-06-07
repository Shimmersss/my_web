<template>
  <div class="ppt-page">
    <div class="container">
      <section class="workspace">
        <div class="workspace-main">
          <div v-if="step === 'form'" class="panel">
            <div class="panel-header">
              <div>
                <h1>PPT 生成</h1>
                <p>先生成可编辑大纲，确认后再生成 PPTX。</p>
              </div>
              <n-tag type="info">公开入口</n-tag>
            </div>

            <div class="field-block">
              <div class="field-label">通用模板</div>
              <div class="template-grid">
                <button
                  v-for="template in templates"
                  :key="template.key"
                  type="button"
                  :class="['template-card', { active: templateKey === template.key }]"
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
                <input type="file" accept=".pptx" @change="handleTemplateSelect" />
                <n-icon size="34"><EaselOutline /></n-icon>
                <strong>{{ templateFile ? templateFile.name : '上传自定义 PPT 模板' }}</strong>
                <span>可选；复用模板框架和素材，配色仍跟随所选通用模板</span>
              </label>
              <label class="file-box">
                <input type="file" accept=".pdf,.docx" @change="handlePaperSelect" />
                <n-icon size="34"><DocumentTextOutline /></n-icon>
                <strong>{{ paperFile ? paperFile.name : '选择论文文件' }}</strong>
                <span>可选，支持 .pdf / .docx，最大 30MB</span>
              </label>
            </div>

            <div class="field-block">
              <div class="field-label">模板复用方式</div>
              <div class="mode-grid">
                <button
                  v-for="mode in templateModeOptions"
                  :key="mode.value"
                  type="button"
                  :class="['mode-card', { active: templateMode === mode.value, disabled: mode.value === 'template-fill' && !templateFile }]"
                  @click="selectTemplateMode(mode.value)"
                >
                  <strong>{{ mode.label }}</strong>
                  <span>{{ mode.description }}</span>
                </button>
              </div>
            </div>

            <div class="field-block">
              <div class="field-label range-label">
                <span>素材提取比例</span>
                <strong>{{ extractionPercent }}%</strong>
              </div>
              <input v-model.number="extractionPercent" class="range-control" type="range" min="10" max="100" step="10" />
              <p class="field-hint">影响 PDF 页面图、DOCX 图片、PPTX 模板 framework 页数和媒体素材数量；比例越高，保留越多模板元素。</p>
            </div>

            <div class="field-block">
              <div class="field-label">生成提示词</div>
              <n-input
                v-model:value="prompt"
                type="textarea"
                :autosize="{ minRows: 8, maxRows: 14 }"
                maxlength="8000"
                show-count
                placeholder="例如：根据论文生成 12 页本科毕业答辩 PPT，风格正式清爽，突出研究背景、方法、实验结果和结论展望。"
              />
            </div>

            <div class="actions">
              <n-button type="primary" size="large" :loading="isSubmitting" @click="submitTask">
                <template #icon><n-icon><SparklesOutline /></n-icon></template>
                生成大纲
              </n-button>
              <n-button size="large" :disabled="isSubmitting" @click="resetForm">
                <template #icon><n-icon><RefreshOutline /></n-icon></template>
                清空
              </n-button>
            </div>

            <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" />
          </div>

          <div v-else-if="step === 'running'" class="panel progress-panel">
            <div class="progress-title">
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
            <n-alert v-if="errorMsg" type="error" :title="errorMsg">
              <n-button size="small" @click="backToForm">重新提交</n-button>
            </n-alert>
          </div>

          <div v-else class="editor-shell">
            <div class="editor-toolbar panel">
              <div>
                <h2>{{ deck.title || 'PPT 大纲' }}</h2>
                <p>{{ deck.subtitle || '可人工编辑，也可以继续对话修改。确认后生成可下载 PPTX。' }}</p>
              </div>
              <div class="toolbar-actions">
                <n-button :loading="isSavingDeck" @click="saveDeckOnly">
                  保存大纲
                </n-button>
                <n-button type="primary" :loading="isRendering" @click="renderDeck">
                  <template #icon><n-icon><EaselOutline /></n-icon></template>
                  {{ activeTask?.status === 'completed' ? '重新生成 PPTX' : '生成 PPTX' }}
                </n-button>
                <n-button v-if="activeTask?.status === 'completed'" @click="downloadCurrent">
                  <template #icon><n-icon><DownloadOutline /></n-icon></template>
                  下载 PPTX
                </n-button>
              </div>
            </div>

            <div class="editor-grid">
              <section class="panel outline-panel">
                <div class="field-block tight">
                  <div class="field-label">标题</div>
                  <n-input v-model:value="deck.title" />
                </div>
                <div class="field-block tight">
                  <div class="field-label">副标题</div>
                  <n-input v-model:value="deck.subtitle" />
                </div>

                <div class="slide-editor-list">
                  <div v-for="(slide, index) in slides" :key="index" class="slide-editor">
                    <div class="slide-editor-head">
                      <strong>{{ String(index + 1).padStart(2, '0') }}</strong>
                      <n-select v-model:value="slide.type" :options="slideTypeOptions" size="small" style="width: 130px" />
                      <n-select v-model:value="slide.layout" :options="slideLayoutOptions" size="small" style="width: 132px" />
                      <n-button size="tiny" quaternary @click="removeSlide(index)">删除</n-button>
                    </div>
                    <n-input v-model:value="slide.title" placeholder="页标题" />
                    <n-input v-model:value="slide.headline" placeholder="核心句" />
                    <n-input v-model:value="slide.imageId" placeholder="图片 ID，例如 paper-image-3（可选）" />
                    <n-input
                      :value="metricsText(slide)"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                      placeholder="指标，每行一个：0.8321 | Random Forest PRC"
                      @update:value="value => updateMetrics(slide, value)"
                    />
                    <n-input
                      :value="bulletsText(slide)"
                      type="textarea"
                      :autosize="{ minRows: 3, maxRows: 6 }"
                      placeholder="要点，每行一条"
                      @update:value="value => updateBullets(slide, value)"
                    />
                    <n-input
                      v-model:value="slide.notes"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 5 }"
                      placeholder="讲稿备注"
                    />
                  </div>
                </div>

                <n-button block dashed @click="addSlide">
                  <template #icon><n-icon><AddOutline /></n-icon></template>
                  添加一页
                </n-button>
              </section>

              <section class="panel preview-panel">
                <div class="chat-box">
                  <h3>对话修改</h3>
                  <div class="chat-history">
                    <div v-for="item in chatMessages" :key="item.id" :class="['chat-message', item.role]">
                      {{ item.content }}
                    </div>
                    <n-empty v-if="!chatMessages.length" description="例如：把实验结果拆成两页，并弱化背景部分。" />
                  </div>
                  <div class="chat-input-row">
                    <n-input
                      v-model:value="revisePrompt"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                      placeholder="输入修改要求，mimo 会基于当前大纲调整"
                    />
                    <n-button type="primary" :loading="isRevising" @click="reviseDeckByChat">修改</n-button>
                  </div>
                </div>

                <div class="preview-header">
                  <h3>前端预览</h3>
                  <p>这里预览的是可编辑大纲的排版效果，下载的 PPTX 会由 PptxGenJS 重新生成。</p>
                </div>
                <div class="slide-preview-list">
                  <article v-for="(slide, index) in slides" :key="`preview-${index}`" class="slide-preview" :style="previewStyle">
                    <div class="preview-page">{{ String(index + 1).padStart(2, '0') }}</div>
                    <small>{{ slide.section || slide.type || 'SLIDE' }}</small>
                    <h4>{{ slide.title || '未命名页面' }}</h4>
                    <p v-if="slide.headline">{{ slide.headline }}</p>
                    <ul>
                      <li v-for="bullet in normalizedBullets(slide)" :key="bullet">{{ bullet }}</li>
                    </ul>
                  </article>
                </div>
              </section>
            </div>

            <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" />
          </div>
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
import {
  NAlert,
  NButton,
  NEmpty,
  NIcon,
  NInput,
  NProgress,
  NSelect,
  NTag
} from 'naive-ui'
import {
  AddOutline,
  DocumentTextOutline,
  DownloadOutline,
  EaselOutline,
  RefreshOutline,
  SparklesOutline,
  TimeOutline,
  ColorPaletteOutline
} from '@vicons/ionicons5'
import {
  createPptGenerationTask,
  downloadGeneratedPpt,
  getPptDeck,
  getPptGenerationStatus,
  getPptTemplates,
  getRecentPptGenerations,
  renderGeneratedPpt,
  revisePptDeck,
  savePptDeck
} from '@/api'

const message = useMessage()
const step = ref('form')
const prompt = ref('')
const templateKey = ref('academic-blue')
const templateMode = ref('framework')
const extractionPercent = ref(50)
const templateFile = ref(null)
const paperFile = ref(null)
const templates = ref(defaultTemplates())
const isSubmitting = ref(false)
const isLoadingRecent = ref(false)
const isSavingDeck = ref(false)
const isRendering = ref(false)
const isRevising = ref(false)
const recentTasks = ref([])
const recentError = ref('')
const errorMsg = ref('')
const activeTask = ref(null)
const taskId = ref('')
const taskAccessToken = ref('')
const deck = ref(emptyDeck())
const progress = ref(0)
const progressStage = ref('queued')
const progressStageLabel = ref('等待后台生成')
const queuePosition = ref(0)
const revisePrompt = ref('')
const chatMessages = ref([])
let eventSource = null
let pollTimer = null
const PPT_TASK_TOKENS_KEY = 'ppt-generation-task-tokens'

const slideTypeOptions = [
  { label: '封面', value: 'cover' },
  { label: '目录', value: 'contents' },
  { label: '章节', value: 'section' },
  { label: '内容', value: 'content' },
  { label: '图片', value: 'image' },
  { label: '结论', value: 'conclusion' },
  { label: '致谢', value: 'thanks' }
]

const slideLayoutOptions = [
  { label: '自动版式', value: 'auto' },
  { label: '大图页', value: 'full-image' },
  { label: '右图文', value: 'image-right' },
  { label: '指标页', value: 'metrics' },
  { label: '对比页', value: 'comparison' },
  { label: '结论页', value: 'conclusion' }
]

const templateModeOptions = [
  { label: '框架复用', value: 'framework', description: '生成可编辑 PPTX，并套用模板骨架和素材节奏。' },
  { label: '母版填充', value: 'template-fill', description: '直接在上传模板 slide 上替换文本，保留原元素尺寸位置。' }
]

const stageItems = [
  { key: 'queued', label: '排队', icon: TimeOutline },
  { key: 'extracting', label: '读取资料', icon: DocumentTextOutline },
  { key: 'planning', label: '生成大纲', icon: ColorPaletteOutline },
  { key: 'reusing_assets', label: '复用素材', icon: DocumentTextOutline },
  { key: 'refreshing_assets', label: '刷新素材', icon: DocumentTextOutline },
  { key: 'rendering', label: '生成 PPTX', icon: EaselOutline }
]

const slides = computed(() => {
  if (!Array.isArray(deck.value.slides)) deck.value.slides = []
  return deck.value.slides
})
const selectedTemplate = computed(() => templates.value.find(item => item.key === templateKey.value) || templates.value[0])
const previewStyle = computed(() => {
  const palette = selectedTemplate.value?.palette || ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937']
  return {
    '--accent': `#${palette[0]}`,
    '--deep': `#${palette[1]}`,
    '--gold': `#${palette[2]}`,
    '--pale': `#${palette[3]}`,
    '--ink': `#${palette[4]}`
  }
})
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
  if (templateFile.value) {
    templateMode.value = 'template-fill'
  } else if (templateMode.value === 'template-fill') {
    templateMode.value = 'framework'
  }
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
      templateMode: templateMode.value,
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
    errorMsg.value = error.message || 'PPT 大纲任务提交失败'
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
  eventSource.addEventListener('outline-ready', async event => {
    const data = parseEvent(event)
    if (data.deck) deck.value = normalizeDeck(data.deck)
    await refreshStatus()
    await loadRecent()
    closeStream()
    step.value = 'outline'
    message.success('大纲已生成，可以编辑确认')
  })
  eventSource.addEventListener('done', async () => {
    progress.value = 100
    closeStream()
    await refreshStatus()
    await loadRecent()
    step.value = 'outline'
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

async function saveDeckOnly() {
  if (!taskId.value) return
  isSavingDeck.value = true
  try {
    const res = await savePptDeck(taskId.value, taskAccessToken.value, deck.value)
    deck.value = normalizeDeck(res.data)
    message.success('大纲已保存')
  } catch (error) {
    errorMsg.value = error.message || '保存大纲失败'
  } finally {
    isSavingDeck.value = false
  }
}

async function renderDeck() {
  if (!taskId.value) return
  isRendering.value = true
  errorMsg.value = ''
  try {
    await savePptDeck(taskId.value, taskAccessToken.value, deck.value)
    const res = await renderGeneratedPpt(taskId.value, taskAccessToken.value, deck.value, {
      templateMode: templateMode.value,
      extractionPercent: extractionPercent.value
    })
    setActiveTask(res.data)
    step.value = 'running'
    openStream(taskId.value)
  } catch (error) {
    errorMsg.value = error.message || '生成 PPTX 失败'
  } finally {
    isRendering.value = false
  }
}

async function reviseDeckByChat() {
  if (!taskId.value || !revisePrompt.value.trim()) return
  const instruction = revisePrompt.value.trim()
  chatMessages.value.push({ id: Date.now(), role: 'user', content: instruction })
  revisePrompt.value = ''
  isRevising.value = true
  try {
    const res = await revisePptDeck(taskId.value, taskAccessToken.value, instruction, deck.value)
    deck.value = normalizeDeck(res.data)
    chatMessages.value.push({ id: Date.now() + 1, role: 'assistant', content: '已按要求更新大纲和预览。' })
  } catch (error) {
    errorMsg.value = error.message || '对话修改失败'
  } finally {
    isRevising.value = false
  }
}

async function openRecent(item) {
  setActiveTask(item)
  errorMsg.value = item.errorMessage || ''
  if (['outline_ready', 'completed'].includes(item.status)) {
    await loadDeck(item.taskId)
    step.value = 'outline'
  } else if (item.status === 'error') {
    step.value = 'running'
  } else {
    step.value = 'running'
    openStream(item.taskId)
  }
}

async function loadDeck(id) {
  try {
    const res = await getPptDeck(id, tokenForTask(id))
    deck.value = normalizeDeck(res.data)
  } catch (error) {
    errorMsg.value = error.message || '读取大纲失败'
  }
}

async function refreshStatus() {
  if (!taskId.value) return
  try {
    const res = await getPptGenerationStatus(taskId.value, taskAccessToken.value)
    setActiveTask(res.data)
    if (['outline_ready', 'completed'].includes(res.data.status)) {
      stopPolling()
      await loadDeck(taskId.value)
      step.value = 'outline'
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
  templateMode.value = data.templateMode || (data.templateFileName ? 'template-fill' : templateMode.value)
  extractionPercent.value = Number(data.extractionPercent || extractionPercent.value || 50)
  progress.value = Number(data.progress || 0)
  progressStage.value = data.progressStage || 'queued'
  progressStageLabel.value = data.progressStageLabel || statusLabel(data)
  queuePosition.value = data.queuePosition || 0
}

function addSlide() {
  slides.value.push({
    type: 'content',
    section: '',
    title: '新页面',
    headline: '',
    layout: 'auto',
    imageId: '',
    metrics: [],
    bullets: [''],
    notes: ''
  })
}

function removeSlide(index) {
  if (slides.value.length <= 1) return
  slides.value.splice(index, 1)
}

function updateBullets(slide, value) {
  slide.bullets = value.split('\n').map(item => item.trim()).filter(Boolean)
}

function bulletsText(slide) {
  return Array.isArray(slide.bullets) ? slide.bullets.join('\n') : ''
}

function updateMetrics(slide, value) {
  slide.metrics = value.split('\n')
    .map(item => item.trim())
    .filter(Boolean)
    .map(item => {
      const [valuePart, labelPart] = item.split('|')
      return {
        value: (valuePart || '').trim(),
        label: (labelPart || '').trim()
      }
    })
    .filter(item => item.value || item.label)
}

function metricsText(slide) {
  return Array.isArray(slide.metrics)
    ? slide.metrics.map(item => `${item.value || ''}${item.label ? ` | ${item.label}` : ''}`.trim()).join('\n')
    : ''
}

function normalizedBullets(slide) {
  return Array.isArray(slide.bullets) ? slide.bullets.filter(Boolean).slice(0, 5) : []
}

function downloadCurrent() {
  if (activeTask.value?.status === 'completed' && taskId.value) downloadGeneratedPpt(taskId.value, taskAccessToken.value)
}

function resetForm() {
  prompt.value = ''
  templateFile.value = null
  paperFile.value = null
  templateMode.value = 'framework'
  extractionPercent.value = 50
  errorMsg.value = ''
}

function selectTemplateMode(value) {
  if (value === 'template-fill' && !templateFile.value) {
    errorMsg.value = '母版填充模式需要先上传 PPTX 模板'
    return
  }
  templateMode.value = value
}

function backToForm() {
  closeStream()
  stopPolling()
  step.value = 'form'
  activeTask.value = null
  taskId.value = ''
  taskAccessToken.value = ''
  deck.value = emptyDeck()
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

function normalizeDeck(value) {
  return {
    title: value?.title || '',
    subtitle: value?.subtitle || '',
    audience: value?.audience || '',
    theme: value?.theme || '',
    slides: Array.isArray(value?.slides) && value.slides.length ? value.slides.map(slide => ({
      type: slide.type || 'content',
      section: slide.section || '',
      title: slide.title || '',
      headline: slide.headline || '',
      layout: slide.layout || 'auto',
      imageId: slide.imageId || '',
      metrics: Array.isArray(slide.metrics) ? slide.metrics : [],
      bullets: Array.isArray(slide.bullets) ? slide.bullets : [],
      notes: slide.notes || '',
      imageHint: slide.imageHint || ''
    })) : emptyDeck().slides
  }
}

function emptyDeck() {
  return {
    title: '',
    subtitle: '',
    audience: '',
    theme: '',
    slides: [{ type: 'cover', title: '待生成大纲', headline: '', layout: 'auto', imageId: '', metrics: [], bullets: [], notes: '' }]
  }
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
  if (item.status === 'outline_ready') return '待确认大纲'
  if (item.status === 'error') return '失败'
  if (item.status === 'rendering') return '生成 PPTX'
  if (item.status === 'outlining') return '生成大纲'
  return item.queuePosition > 0 ? `排队第 ${item.queuePosition} 位` : '排队中'
}

function tagType(status) {
  if (status === 'completed') return 'success'
  if (status === 'outline_ready') return 'info'
  if (status === 'error') return 'error'
  if (status === 'rendering' || status === 'outlining') return 'info'
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
  min-height: calc(100vh - 72px);
  background: #f6f8fb;
  padding: 28px 0 56px;
}

.container {
  width: min(1320px, calc(100% - 32px));
  margin: 0 auto;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 20px;
  align-items: start;
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
.progress-title,
.editor-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
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

  &.tight {
    margin-top: 0;
    margin-bottom: 14px;
  }
}

.field-label {
  margin-bottom: 10px;
  font-weight: 700;
  color: #334155;
}

.template-grid,
.upload-grid,
.mode-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.template-card,
.file-box,
.mode-card {
  min-height: 132px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  padding: 18px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #fbfdff;
  cursor: pointer;
  text-align: left;

  &:hover,
  &.active {
    border-color: #2563eb;
    background: #f1f7ff;
  }

  input {
    display: none;
  }

  strong {
    color: #0f172a;
    word-break: break-word;
  }

  span,
  small {
    color: #64748b;
    font-size: 13px;
  }
}

.mode-card {
  min-height: 92px;

  &.disabled {
    cursor: not-allowed;
    opacity: 0.58;
  }
}

.range-label {
  display: flex;
  align-items: center;
  justify-content: space-between;

  strong {
    color: #155eef;
  }
}

.range-control {
  width: 100%;
  accent-color: #2563eb;
}

.field-hint {
  font-size: 13px;
  line-height: 1.55;
}

.swatches {
  display: flex;
  gap: 5px;

  span {
    width: 22px;
    height: 22px;
    border-radius: 999px;
    border: 1px solid rgba(15, 23, 42, 0.12);
  }
}

.actions,
.toolbar-actions {
  display: flex;
  gap: 10px;
  margin-top: 22px;
  flex-wrap: wrap;
}

.toolbar-actions {
  margin-top: 0;
}

.progress-panel {
  min-height: 340px;
}

.progress-title {
  justify-content: flex-start;
  margin-bottom: 24px;
}

.stage-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 24px 0;
}

.stage-item {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 44px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  color: #64748b;
  background: #f8fafc;

  &.active {
    color: #155eef;
    border-color: #bfdbfe;
    background: #eff6ff;
    font-weight: 700;
  }
}

.editor-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(360px, 0.9fr) minmax(420px, 1.1fr);
  gap: 16px;
  align-items: start;
}

.outline-panel,
.preview-panel {
  max-height: calc(100vh - 160px);
  overflow: auto;
}

.slide-editor-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 16px;
}

.slide-editor {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.slide-editor-head {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}

.chat-box {
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 18px;
  margin-bottom: 18px;
}

.chat-history {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 180px;
  overflow: auto;
  margin: 12px 0;
}

.chat-message {
  padding: 9px 11px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.5;

  &.user {
    background: #eff6ff;
    color: #1d4ed8;
    align-self: flex-end;
  }

  &.assistant {
    background: #f1f5f9;
    color: #334155;
    align-self: flex-start;
  }
}

.chat-input-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: end;
}

.preview-header {
  margin-bottom: 12px;
}

.slide-preview-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.slide-preview {
  position: relative;
  aspect-ratio: 16 / 9;
  padding: 24px;
  border-radius: 8px;
  border: 1px solid #dbe3ef;
  background: linear-gradient(180deg, var(--pale), #fff 42%);
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    inset: 0 0 auto;
    height: 5px;
    background: var(--accent);
  }

  small {
    color: var(--gold);
    font-weight: 800;
    letter-spacing: 0;
  }

  h4 {
    margin: 12px 0 8px;
    color: var(--deep);
    font-size: 19px;
    line-height: 1.2;
  }

  p {
    color: var(--ink);
    font-size: 13px;
    line-height: 1.45;
  }

  ul {
    margin: 12px 0 0;
    padding-left: 18px;
    color: #334155;
    font-size: 12px;
    line-height: 1.55;
  }
}

.preview-page {
  position: absolute;
  right: 14px;
  top: 14px;
  color: var(--accent);
  font-weight: 800;
}

.recent-panel {
  padding: 20px;
  position: sticky;
  top: 92px;
}

.compact-alert {
  margin-top: 14px;
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 16px;
}

.recent-item {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 10px;
  align-items: center;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  text-align: left;
  cursor: pointer;

  &:hover {
    border-color: #93c5fd;
  }

  strong {
    color: #0f172a;
    font-size: 14px;
    word-break: break-word;
  }

  span {
    color: #64748b;
    font-size: 12px;
  }
}

@media (max-width: 1120px) {
  .workspace,
  .editor-grid {
    grid-template-columns: 1fr;
  }

  .recent-panel {
    position: static;
  }

  .outline-panel,
  .preview-panel {
    max-height: none;
  }
}

@media (max-width: 680px) {
  .template-grid,
  .upload-grid,
  .stage-grid,
  .slide-preview-list {
    grid-template-columns: 1fr;
  }

  .panel {
    padding: 18px;
  }

  .editor-toolbar,
  .panel-header {
    flex-direction: column;
  }
}
</style>
