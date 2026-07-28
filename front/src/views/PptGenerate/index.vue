<template>
  <div class="ppt-page tool-page">
    <div class="container">
      <div class="tool-page__header ppt-page-header">
        <div>
          <h1>PPT 生成</h1>
          <p>输入一句需求或上传一份资料，AI 会自动理解内容并生成 PPTX 或 HTML 演示文稿。</p>
          <p class="quota-line">余额：{{ auth.isLoggedIn ? `${auth.credits} credits` : '未登录' }} · 本次预计 {{ pptEstimatedCredits }} credits</p>
        </div>
        <n-tag type="info">公开入口</n-tag>
      </div>
      <section class="workspace">
        <div class="workspace-main">
          <section v-if="step === 'form'" class="panel">
            <div class="panel-header">
              <ol class="flow-steps" aria-label="PPT 生成流程">
                <li><strong>1</strong><span>输入需求或资料</span></li>
                <li><strong>2</strong><span>自动提取重点</span></li>
                <li><strong>3</strong><span>生成 PPTX / HTML</span></li>
              </ol>
            </div>

            <div class="field-block output-format-block">
              <div class="field-label">输出格式</div>
              <div class="output-format-picker" role="radiogroup" aria-label="输出格式">
                <button type="button" role="radio" :class="['output-format-card', { active: outputFormat === 'pptx' }]" :aria-checked="outputFormat === 'pptx'" @click="outputFormat = 'pptx'">
                  <strong>PPTX</strong>
                  <span>可在 PowerPoint 中继续编辑</span>
                </button>
                <button type="button" role="radio" :class="['output-format-card', { active: outputFormat === 'html' }]" :aria-checked="outputFormat === 'html'" @click="outputFormat = 'html'">
                  <strong>HTML</strong>
                  <span>单文件网页演示，可直接分享</span>
                </button>
              </div>
            </div>

            <div class="field-block">
              <div class="field-label">通用模板</div>
              <div class="template-picker">
                <div class="template-groups">
                  <section v-for="group in templateGroups" :key="group.key" class="template-group">
                    <button type="button" class="template-group__header" :aria-expanded="expandedTemplateCategories.has(group.key)" @click="toggleTemplateCategory(group.key)">
                      <span>
                        <strong>{{ group.label }}</strong>
                        <small>{{ group.items.length }} 套模板</small>
                      </span>
                      <span class="template-group__meta">{{ expandedTemplateCategories.has(group.key) ? '收起' : '展开' }} <b>{{ expandedTemplateCategories.has(group.key) ? '−' : '+' }}</b></span>
                    </button>
                    <div v-show="expandedTemplateCategories.has(group.key)" class="template-grid">
                      <button
                        v-for="template in group.items"
                        :key="template.key"
                        type="button"
                        :class="['template-card', { active: templateKey === template.key }]"
                        :aria-pressed="templateKey === template.key"
                        @click="selectTemplate(template)"
                      >
                        <div class="swatches">
                          <span v-for="color in template.palette" :key="color" :style="{ backgroundColor: `#${color}` }"></span>
                        </div>
                        <strong>{{ template.name }}</strong>
                        <small>{{ template.description }}</small>
                        <small class="template-complexity">{{ template.complexity === 'rich' ? '复杂布局 · 图文/数据槽位' : '通用布局' }}</small>
                        <small v-if="template.recommendedFor?.length" class="template-recommendation">适合：{{ template.recommendedFor.join(' · ') }}</small>
                        <span v-if="template.source" class="template-source" @click.stop>
                          <a :href="template.sourceUrl" target="_blank" rel="noreferrer">{{ template.source }} · {{ template.license || '开源' }}</a>
                        </span>
                        <small v-if="template.usageNote" class="template-usage-note">{{ template.usageNote }}</small>
                      </button>
                    </div>
                  </section>
                </div>

                <section class="template-showcase" aria-label="模板样式预览">
                  <div class="template-showcase__heading">
                    <div>
                      <span class="template-showcase__eyebrow">STYLE PREVIEW · 5 PAGES</span>
                      <h3>{{ selectedTemplate?.name || '学术蓝' }}</h3>
                      <p>{{ selectedTemplate?.description || '选择模板后查看页面节奏和配色示意。' }}</p>
                    </div>
                    <n-tag size="small" type="success">当前选择</n-tag>
                  </div>
                  <div v-if="templatePreviewSlides.length" class="template-showcase__stage">
                    <div class="template-showcase__main" :style="templatePreviewStyle(templatePreviewSlides[templatePreviewIndex])">
                      <div class="template-preview-slide" :class="[`template-preview-slide--${templatePreviewSlides[templatePreviewIndex].kind}`, `template-preview-design--${selectedTemplate?.design || 'academic'}`]">
                        <span class="template-preview-kicker">{{ templatePreviewSlides[templatePreviewIndex].eyebrow }}</span>
                        <span v-if="templatePreviewSlides[templatePreviewIndex].index" class="template-preview-chapter">{{ templatePreviewSlides[templatePreviewIndex].index }}</span>
                        <h4>{{ templatePreviewSlides[templatePreviewIndex].title }}</h4>
                        <p v-if="templatePreviewSlides[templatePreviewIndex].subtitle" class="template-preview-subtitle">{{ templatePreviewSlides[templatePreviewIndex].subtitle }}</p>
                        <ul v-if="templatePreviewSlides[templatePreviewIndex].bullets?.length" class="template-preview-bullets">
                          <li v-for="bullet in templatePreviewSlides[templatePreviewIndex].bullets" :key="bullet">{{ bullet }}</li>
                        </ul>
                        <div v-if="templatePreviewSlides[templatePreviewIndex].metric" class="template-preview-metric">
                          <strong>{{ templatePreviewSlides[templatePreviewIndex].metric.value }}</strong>
                          <span>{{ templatePreviewSlides[templatePreviewIndex].metric.label }}</span>
                        </div>
                        <div class="template-preview-footer">{{ String(templatePreviewIndex + 1).padStart(2, '0') }} / 05</div>
                      </div>
                    </div>
                    <div class="template-showcase__thumbs">
                      <button
                        v-for="(slide, index) in templatePreviewSlides"
                        :key="`${selectedTemplate?.key}-${slide.kind}`"
                        type="button"
                        :class="['template-preview-thumb', { active: templatePreviewIndex === index }]"
                        :aria-label="`预览第 ${index + 1} 页`"
                        @click="templatePreviewIndex = index"
                      >
                        <div class="template-preview-thumb__canvas" :class="`template-preview-design--${selectedTemplate?.design || 'academic'}`" :style="templatePreviewStyle(slide)">
                          <span>{{ slide.index || (index + 1).toString().padStart(2, '0') }}</span>
                          <strong>{{ slide.title }}</strong>
                        </div>
                        <small>{{ index + 1 }} · {{ slide.label }}</small>
                      </button>
                    </div>
                  </div>
                  <p class="template-showcase__note">这里展示的是布局、色彩和页面节奏示意；上传资料后，系统会按内容自动匹配版式。</p>
                </section>
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
                <input type="file" accept=".pdf,.docx,.pptx,.xlsx,.txt,.md,.csv,.html,.htm" aria-label="上传资料文件" @change="handleSourceSelect" />
                <n-icon size="34"><DocumentTextOutline /></n-icon>
                <strong>{{ sourceFile ? sourceFile.name : '上传资料文件' }}</strong>
                <span>可选；支持 PDF、Word、PPT、Excel、TXT、Markdown、CSV、网页，最大 30MB</span>
              </label>
            </div>

            <div class="field-block">
              <div class="field-label">补充要求 <span class="optional-label">（可选）</span></div>
              <n-input
                v-model:value="prompt"
                type="textarea"
                aria-label="PPT 生成提示词"
                :autosize="{ minRows: 8, maxRows: 14 }"
                maxlength="8000"
                show-count
                placeholder="例如：做成 10 页产品发布会 PPT，面向企业客户，突出核心价值、使用场景、关键数据和下一步行动。不上传资料时，也可以直接用提示词生成。"
              />
              <p class="field-hint">提示词和资料至少提供一个；只上传资料时，系统会自动提炼主题、结构、重点数据和适合的视觉素材。</p>
            </div>

            <div class="actions">
              <n-button type="primary" size="large" :loading="isSubmitting" @click="submitTask">
                <template #icon><n-icon><SparklesOutline /></n-icon></template>
                生成 {{ outputFormat === 'html' ? 'HTML' : 'PPTX' }}
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

          <section v-else class="panel result-panel result-panel--wide">
            <div class="result-toolbar">
              <div class="result-heading">
                <div class="result-mark">
                  <n-icon size="34"><EaselOutline /></n-icon>
                </div>
                <div>
                  <h2>{{ outputFormatLabel(activeTask) }} 已生成，可直接预览</h2>
                  <p>{{ activeTask?.outputFileName || `网页预览与 ${outputFormatLabel(activeTask)} 下载均已准备好。` }}</p>
                </div>
              </div>
              <div class="result-toolbar-actions">
                <n-button size="small" :loading="previewLoading" @click="loadPreview">刷新预览</n-button>
                <n-button size="small" :type="previewEditing ? 'primary' : 'default'" @click="previewEditing = !previewEditing">
                  {{ previewEditing ? '完成网页编辑' : '网页编辑' }}
                </n-button>
              </div>
            </div>

            <n-alert v-if="previewError" type="warning" :title="previewError" class="preview-alert" />
            <div v-if="previewLoading && !previewSlides.length" class="preview-empty">正在生成网页预览…</div>
            <div v-else-if="previewSlides.length" class="preview-workbench">
              <div class="preview-rail" aria-label="幻灯片缩略图">
                <button
                  v-for="(slide, index) in previewSlides"
                  :key="`${taskId}-${index}`"
                  type="button"
                  :class="['preview-thumb', { active: previewSelectedIndex === index }]"
                  :aria-label="`查看第 ${index + 1} 页`"
                  @click="previewSelectedIndex = index"
                >
                  <canvas :ref="element => setPreviewThumbCanvas(index, element)" class="preview-thumb__canvas" width="320" height="180"></canvas>
                  <span>{{ index + 1 }}</span>
                </button>
              </div>
              <div class="preview-stage">
                <div class="preview-stage__canvas-wrap">
                  <canvas ref="previewCanvas" class="preview-canvas" width="1600" height="900" role="img" :aria-label="`第 ${previewSelectedIndex + 1} 页 PPT 预览`"></canvas>
                </div>
                <div class="preview-stage__caption">
                  <div>
                    <strong>{{ selectedPreviewSlide?.title || 'PPT 页面预览' }}</strong>
                    <span>{{ previewSelectedIndex + 1 }} / {{ previewSlides.length }} · {{ selectedPreviewSlide?.layout || selectedPreviewSlide?.type || '内容页' }}</span>
                  </div>
                  <span class="preview-stage__hint">图片式预览 · 固定 16:9{{ previewData?.previewFidelity === 'approximate-native-template' ? ' · 模板原生版式以下载文件为准' : '' }}</span>
                </div>
              </div>
              <aside v-if="previewEditing && selectedPreviewSlide" class="preview-editor">
                <div class="preview-editor__heading">
                  <strong>编辑第 {{ previewSelectedIndex + 1 }} 页</strong>
                  <span>修改后提交会生成新版本</span>
                </div>
                <n-input :value="selectedPreviewSlide.title" size="small" maxlength="80" placeholder="页面标题" @update:value="updatePreviewField('title', $event)" />
                <n-input :value="selectedPreviewSlide.headline" size="small" maxlength="140" placeholder="核心句（可选）" @update:value="updatePreviewField('headline', $event)" />
                <n-input :value="selectedPreviewSlide.bulletText" type="textarea" size="small" :autosize="{ minRows: 5, maxRows: 10 }" placeholder="每行一个要点" @update:value="updatePreviewField('bulletText', $event)" />
                <div class="preview-editor__nav">
                  <n-button size="small" :disabled="previewSelectedIndex <= 0" @click="previewSelectedIndex -= 1">上一页</n-button>
                  <n-button size="small" :disabled="previewSelectedIndex >= previewSlides.length - 1" @click="previewSelectedIndex += 1">下一页</n-button>
                </div>
              </aside>
            </div>
            <div v-else class="preview-empty">此任务暂未生成可用的网页预览，但仍可下载 {{ outputFormatLabel(activeTask) }}。</div>

            <section class="revision-box">
              <div class="revision-box__heading">
                <div>
                  <h3>继续修改这份 PPT</h3>
                  <p>可以写一句修改要求，也可以先打开“网页编辑”改标题和要点，再提交生成新版本。</p>
                </div>
                <n-tag size="small" type="info">会生成新版本</n-tag>
              </div>
              <n-input v-model:value="revisionPrompt" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="4000" placeholder="例如：把第 2 页改成客户痛点，第 5 页增加一个竞品对比，整体语气更像产品发布会。" />
              <div class="actions">
                <n-button type="primary" :loading="revisionSubmitting" @click="submitRevision">
                  <template #icon><n-icon><SparklesOutline /></n-icon></template>
                  {{ previewEditing ? '保存网页修改并生成' : '按提示词生成新版本' }}
                </n-button>
                <n-button size="large" @click="downloadCurrent">
                  <template #icon><n-icon><DownloadOutline /></n-icon></template>
                  下载 {{ outputFormatLabel(activeTask) }}
                </n-button>
                <n-button size="large" @click="backToForm">再生成一份</n-button>
              </div>
            </section>
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
  getQuotaSettings,
  getPptPreview,
  getPptPreviewImage,
  getPptGenerationStatus,
  getPptTemplates,
  getRecentPptGenerations,
  revisePptGenerationTask
} from '@/api'
import { apiUrl, BASE_URL } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const auth = useAuthStore()
const step = ref('form')
const prompt = ref('')
const templateKey = ref('academic-blue')
const outputFormat = ref('pptx')
const templateFile = ref(null)
const sourceFile = ref(null)
const templates = ref(defaultTemplates())
const expandedTemplateCategories = ref(new Set(['core']))
const isSubmitting = ref(false)
const isLoadingRecent = ref(false)
const recentTasks = ref([])
const recentError = ref('')
const errorMsg = ref('')
const activeTask = ref(null)
const previewData = ref(null)
const previewImageUrls = ref({})
const previewLoading = ref(false)
const previewError = ref('')
const previewEditing = ref(false)
const revisionPrompt = ref('')
const revisionSubmitting = ref(false)
const templatePreviewIndex = ref(0)
const previewSelectedIndex = ref(0)
const previewCanvas = ref(null)
const previewThumbCanvases = ref({})
const taskId = ref('')
const taskAccessToken = ref('')
const progress = ref(0)
const progressStage = ref('queued')
const progressStageLabel = ref('等待后台生成')
const queuePosition = ref(0)
let eventSource = null
let pollTimer = null
let streamGeneration = 0
let previewGeneration = 0
let previewAbortController = null
let authWatchReady = false
let pendingIdempotencyKey = ''
const PPT_TASK_TOKENS_KEY = 'ppt-generation-task-tokens'
const PPT_ACTIVE_TASK_KEY = 'ppt-generation-active-task'
const pptCreditPerTask = ref(10)
const pptEstimatedCredits = computed(() => pptCreditPerTask.value)
const previewSlides = computed(() => previewData.value?.slides || [])
const selectedTemplate = computed(() => templates.value.find(item => item.key === templateKey.value) || templates.value[0] || null)
const selectedPreviewSlide = computed(() => previewSlides.value[previewSelectedIndex.value] || null)
const templatePreviewSlides = computed(() => buildTemplatePreviewSlides(selectedTemplate.value))
const templateGroups = computed(() => {
  const groups = new Map()
  templates.value.forEach(template => {
    const key = templateCategoryKey(template)
    if (!groups.has(key)) groups.set(key, {
      key,
      label: template.categoryLabel || templateCategoryLabel(key),
      items: []
    })
    groups.get(key).items.push(template)
  })
  return [...groups.values()]
})

const previewImageCache = new Map()

const stageItems = [
  { key: 'queued', label: '排队', icon: TimeOutline },
  { key: 'extracting', label: '读取资料', icon: DocumentTextOutline },
  { key: 'planning', label: '规划内容', icon: ColorPaletteOutline },
  { key: 'rendering', label: '生成输出', icon: EaselOutline }
]

const runningTitle = computed(() => activeTask.value?.sourceFileName || activeTask.value?.paperFileName || activeTask.value?.templateFileName || 'PPT 生成任务')

onMounted(async () => {
  await auth.refresh().catch(() => {})
  await Promise.all([loadTemplates(), loadRecent(), loadQuotaSettings()])
  authWatchReady = true
  await restoreActiveTask()
})

onBeforeUnmount(() => {
  closeStream()
  stopPolling()
  clearPreview()
})

watch(() => auth.user?.id || null, (nextId, previousId) => {
  if (authWatchReady && previousId !== undefined && nextId !== previousId) {
    backToForm()
    clearActiveTask()
  }
})

watch([previewData, previewSelectedIndex, previewImageUrls], () => {
  nextTick(renderPreviewCanvases)
}, { deep: true })

function handleTemplateSelect(event) {
  const file = event.target.files?.[0] || null
  if (file && (!file.name.toLowerCase().endsWith('.pptx') || file.size > 30 * 1024 * 1024)) {
    errorMsg.value = file.size > 30 * 1024 * 1024 ? 'PPT 模板不能超过 30MB' : '仅支持 .pptx 模板文件'
    event.target.value = ''
    templateFile.value = null
    return
  }
  errorMsg.value = ''
  templateFile.value = file
}

function handleSourceSelect(event) {
  const file = event.target.files?.[0] || null
  const lower = file?.name?.toLowerCase() || ''
  if (file && (!isSupportedSource(lower) || file.size > 30 * 1024 * 1024)) {
    errorMsg.value = file.size > 30 * 1024 * 1024 ? '资料文件不能超过 30MB' : '资料支持 PDF、Word、PPT、Excel、TXT、Markdown、CSV 或网页'
    event.target.value = ''
    sourceFile.value = null
    return
  }
  errorMsg.value = ''
  sourceFile.value = file
}

async function submitTask() {
  errorMsg.value = ''
  if (!prompt.value.trim() && !sourceFile.value) {
    errorMsg.value = '请输入提示词，或上传一份资料'
    return
  }
  if (!auth.isLoggedIn) {
    errorMsg.value = '请先登录账号'
    return
  }
  if (!auth.isRoot && auth.credits < pptEstimatedCredits.value) {
    errorMsg.value = `额度不足，预计需要 ${pptEstimatedCredits.value} credits`
    return
  }
  if (templateFile.value && (templateFile.value.size > 30 * 1024 * 1024 || !templateFile.value.name.toLowerCase().endsWith('.pptx'))) {
    errorMsg.value = 'PPT 模板无效或超过 30MB'
    return
  }
  if (sourceFile.value && (sourceFile.value.size > 30 * 1024 * 1024 || !isSupportedSource(sourceFile.value.name.toLowerCase()))) {
    errorMsg.value = '资料文件无效或超过 30MB'
    return
  }
  isSubmitting.value = true
  if (!pendingIdempotencyKey) {
    pendingIdempotencyKey = typeof globalThis.crypto?.randomUUID === 'function'
      ? globalThis.crypto.randomUUID()
      : `ppt-${Date.now()}-${Math.random().toString(36).slice(2)}`
  }
  try {
    const res = await createPptGenerationTask({
      prompt: prompt.value.trim(),
      templateKey: templateKey.value,
      outputFormat: outputFormat.value,
      templateFile: templateFile.value,
      sourceFile: sourceFile.value,
      idempotencyKey: pendingIdempotencyKey
    })
    pendingIdempotencyKey = ''
    rememberTaskToken(res.data.taskId, res.data.accessToken)
    if (typeof res.data.credits !== 'undefined') auth.updateCredits(res.data.credits)
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
  const generation = ++streamGeneration
  const streamTaskId = id
  // Tasks are created behind the authenticated HttpOnly session; keep task tokens out of URLs/logs.
  eventSource = new EventSource(apiUrl(`/ppt-generate/stream/${encodeURIComponent(id)}`), {
    withCredentials: /^https?:\/\//i.test(BASE_URL)
  })
  eventSource.addEventListener('queued', event => {
    if (generation !== streamGeneration) return
    const data = parseEvent(event)
    queuePosition.value = data.queuePosition || 0
    progressStage.value = 'queued'
    progressStageLabel.value = data.message || '任务正在等待后台处理'
  })
  eventSource.addEventListener('progress', event => {
    if (generation !== streamGeneration) return
    const data = parseEvent(event)
    progress.value = Number(data.progress || progress.value || 0)
    progressStage.value = data.stage || progressStage.value
    progressStageLabel.value = data.stageLabel || data.message || progressStageLabel.value
    queuePosition.value = 0
  })
  eventSource.addEventListener('done', async () => {
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    progress.value = 100
    closeStream()
    await refreshStatus()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    await loadRecent()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    step.value = 'result'
    await loadPreview()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    message.success(`${outputFormatLabel(activeTask.value)} 已生成`)
  })
  eventSource.addEventListener('task-error', async event => {
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    const data = parseEvent(event)
    errorMsg.value = data.message || 'PPT 生成失败'
    closeStream()
    await refreshStatus()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    await loadRecent()
  })
  eventSource.onerror = () => {
    if (generation !== streamGeneration) return
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
    await loadPreview()
  } else {
    step.value = 'running'
    if (item.status !== 'error') openStream(item.taskId)
  }
}

async function refreshStatus() {
  if (!taskId.value) return
  const requestedTaskId = taskId.value
  const requestedToken = taskAccessToken.value
  try {
    const res = await getPptGenerationStatus(requestedTaskId, requestedToken)
    if (taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    setActiveTask(res.data)
    if (res.data.status === 'completed') {
      stopPolling()
      step.value = 'result'
      await loadPreview()
    } else if (res.data.status === 'error') {
      stopPolling()
      errorMsg.value = res.data.errorMessage || 'PPT 生成失败'
    }
  } catch (error) {
    if (taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    errorMsg.value = error.message || '任务状态恢复失败'
  }
}

async function loadTemplates() {
  try {
    const res = await getPptTemplates()
    if (Array.isArray(res.data) && res.data.length) templates.value = res.data
    if (!templates.value.some(item => item.key === templateKey.value)) templateKey.value = templates.value[0]?.key || 'academic-blue'
    ensureTemplateCategoryExpanded(templateKey.value)
  } catch {
    templates.value = defaultTemplates()
    ensureTemplateCategoryExpanded(templateKey.value)
  }
}

function selectTemplate(template) {
  if (!template?.key) return
  templateKey.value = template.key
  templatePreviewIndex.value = 0
  ensureTemplateCategoryExpanded(template.key)
}

function toggleTemplateCategory(key) {
  const next = new Set(expandedTemplateCategories.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedTemplateCategories.value = next
}

function ensureTemplateCategoryExpanded(key) {
  const template = templates.value.find(item => item.key === key)
  if (!template) return
  const next = new Set(expandedTemplateCategories.value)
  next.add(templateCategoryKey(template))
  expandedTemplateCategories.value = next
}

async function loadPreview() {
  clearPreview()
  previewError.value = ''
  if (!taskId.value || activeTask.value?.status !== 'completed') return
  const requestedTaskId = taskId.value
  const requestedToken = taskAccessToken.value
  const generation = ++previewGeneration
  const controller = new AbortController()
  previewAbortController = controller
  previewLoading.value = true
  try {
    const res = await getPptPreview(requestedTaskId, requestedToken, { signal: controller.signal })
    if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    const data = res.data || {}
    data.slides = Array.isArray(data.slides) ? data.slides.map(slide => ({
      ...slide,
      bullets: Array.isArray(slide.bullets) ? slide.bullets : [],
      metrics: Array.isArray(slide.metrics) ? slide.metrics : [],
      bulletText: Array.isArray(slide.bullets) ? slide.bullets.join('\n') : ''
    })) : []
    previewData.value = data
    previewSelectedIndex.value = Math.min(previewSelectedIndex.value, Math.max(0, data.slides.length - 1))
    const imageFiles = [...new Set(data.slides.map(slide => slide.imageFile).filter(Boolean))].slice(0, 24)
    const loaded = await Promise.all(imageFiles.map(async fileName => {
      try {
        const url = await getPptPreviewImage(requestedTaskId, fileName, requestedToken, { signal: controller.signal })
        if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) {
          URL.revokeObjectURL(url)
          return null
        }
        return [fileName, url]
      } catch {
        return null
      }
    }))
    if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    previewImageUrls.value = Object.fromEntries(loaded.filter(Boolean))
    await nextTick()
    if (generation !== previewGeneration || taskId.value !== requestedTaskId) return
    renderPreviewCanvases()
  } catch (error) {
    if (error?.name === 'AbortError') return
    if (generation !== previewGeneration || taskId.value !== requestedTaskId) return
    previewError.value = error.message || '网页预览加载失败'
  } finally {
    if (generation === previewGeneration) {
      previewLoading.value = false
      previewAbortController = null
    }
  }
}

function clearPreview() {
  previewGeneration += 1
  previewLoading.value = false
  if (previewAbortController) {
    previewAbortController.abort()
    previewAbortController = null
  }
  Object.values(previewImageUrls.value || {}).forEach(url => URL.revokeObjectURL(url))
  previewImageCache.clear()
  previewImageUrls.value = {}
  previewData.value = null
  previewError.value = ''
  previewSelectedIndex.value = 0
  previewThumbCanvases.value = {}
}

function setPreviewThumbCanvas(index, element) {
  if (element) previewThumbCanvases.value[index] = element
  else delete previewThumbCanvases.value[index]
}

function updatePreviewField(field, value) {
  const slide = selectedPreviewSlide.value
  if (slide) slide[field] = value
}

function renderPreviewCanvases() {
  if (!previewSlides.value.length) return
  drawPreviewSlide(previewCanvas.value, selectedPreviewSlide.value, previewSelectedIndex.value)
  previewSlides.value.forEach((slide, index) => drawPreviewSlide(previewThumbCanvases.value[index], slide, index))
}

function drawPreviewSlide(canvas, slide, slideIndex = 0) {
  if (!canvas || !slide) return
  const width = 1600
  const height = 900
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const palette = previewPalette()
  const dark = ['cover', 'section', 'thanks', 'chapter'].includes(String(slide.type || '').toLowerCase())
  const background = dark ? palette.deep : palette.background
  const text = dark ? '#ffffff' : palette.text
  ctx.fillStyle = background
  ctx.fillRect(0, 0, width, height)
  ctx.fillStyle = palette.accent
  ctx.fillRect(0, 0, 22, height)
  ctx.fillStyle = dark ? palette.highlight : palette.accent
  ctx.fillRect(70, 86, 140, 10)
  ctx.fillStyle = dark ? 'rgba(255,255,255,.68)' : palette.muted
  ctx.font = '700 25px "Noto Sans CJK SC", "PingFang SC", Arial, sans-serif'
  ctx.fillText(String(slide.section || slide.type || 'CONTENT').toUpperCase().slice(0, 28), 70, 145)

  const title = String(slide.title || '未命名页面')
  ctx.fillStyle = text
  ctx.font = `${dark ? '700 78px' : '700 68px'} "Noto Sans CJK SC", "PingFang SC", Arial, sans-serif`
  const titleY = dark ? 360 : 235
  drawCanvasText(ctx, title, 70, titleY, dark ? 1250 : 930, dark ? 94 : 82, 2)

  if (slide.headline) {
    ctx.fillStyle = dark ? 'rgba(255,255,255,.78)' : palette.muted
    ctx.font = '400 31px "Noto Sans CJK SC", "PingFang SC", Arial, sans-serif'
    drawCanvasText(ctx, String(slide.headline), 72, dark ? 560 : 355, dark ? 1150 : 920, 45, 3)
  }

  const imageUrl = slide.imageFile ? previewImageUrls.value[slide.imageFile] : ''
  if (imageUrl) drawCanvasImage(ctx, imageUrl, 1030, 210, 460, 390)

  const bullets = Array.isArray(slide.bullets) ? slide.bullets.slice(0, 5) : []
  if (bullets.length) {
    const startY = dark ? 665 : 485
    ctx.font = '400 28px "Noto Sans CJK SC", "PingFang SC", Arial, sans-serif'
    bullets.forEach((bullet, index) => {
      const y = startY + index * 58
      ctx.fillStyle = palette.accent
      ctx.beginPath()
      ctx.arc(86, y - 9, 7, 0, Math.PI * 2)
      ctx.fill()
      ctx.fillStyle = text
      drawCanvasText(ctx, String(bullet), 112, y, imageUrl ? 760 : 1320, 36, 2)
    })
  }

  if (Array.isArray(slide.metrics) && slide.metrics.length) {
    const metrics = slide.metrics.slice(0, 3)
    const cardWidth = Math.min(370, 1320 / metrics.length - 22)
    metrics.forEach((metric, index) => {
      const x = 70 + index * (cardWidth + 24)
      const y = 680
      ctx.fillStyle = dark ? 'rgba(255,255,255,.1)' : 'rgba(255,255,255,.72)'
      ctx.fillRect(x, y, cardWidth, 128)
      ctx.strokeStyle = dark ? 'rgba(255,255,255,.24)' : `${palette.accent}55`
      ctx.strokeRect(x, y, cardWidth, 128)
      ctx.fillStyle = palette.accent
      ctx.font = '700 42px "Noto Sans CJK SC", "PingFang SC", Arial, sans-serif'
      ctx.fillText(String(metric.value || ''), x + 22, y + 52)
      ctx.fillStyle = text
      ctx.font = '400 22px "Noto Sans CJK SC", "PingFang SC", Arial, sans-serif'
      drawCanvasText(ctx, String(metric.label || ''), x + 22, y + 92, cardWidth - 44, 28, 1)
    })
  }

  ctx.fillStyle = dark ? 'rgba(255,255,255,.52)' : palette.muted
  ctx.font = '400 22px Arial, sans-serif'
  ctx.fillText(`${String(slideIndex + 1).padStart(2, '0')} / ${String(previewSlides.value.length).padStart(2, '0')}`, 1400, 835)
}

function drawCanvasText(ctx, value, x, y, maxWidth, lineHeight, maxLines) {
  const text = String(value || '').trim()
  if (!text) return
  const chars = Array.from(text)
  const lines = []
  let line = ''
  chars.forEach(char => {
    const candidate = line + char
    if (ctx.measureText(candidate).width > maxWidth && line) {
      lines.push(line)
      line = char
    } else {
      line = candidate
    }
  })
  if (line) lines.push(line)
  lines.slice(0, maxLines).forEach((item, index) => {
    let output = item
    if (index === maxLines - 1 && lines.length > maxLines) output = `${item.slice(0, Math.max(0, item.length - 2))}…`
    ctx.fillText(output, x, y + index * lineHeight)
  })
}

function drawCanvasImage(ctx, url, x, y, width, height) {
  let image = previewImageCache.get(url)
  if (!image) {
    const generation = previewGeneration
    image = new Image()
    image.onload = () => {
      if (generation !== previewGeneration) return
      previewImageCache.set(url, image)
      renderPreviewCanvases()
    }
    image.onerror = () => {
      if (generation === previewGeneration) previewImageCache.delete(url)
    }
    image.src = url
    previewImageCache.set(url, image)
  }
  if (!image.complete || !image.naturalWidth) return
  const scale = Math.min(width / image.naturalWidth, height / image.naturalHeight)
  const drawWidth = image.naturalWidth * scale
  const drawHeight = image.naturalHeight * scale
  ctx.drawImage(image, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2, drawWidth, drawHeight)
}

function previewPalette() {
  const values = (previewData.value?.palette || ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937']).map(color => `#${String(color).replace('#', '')}`)
  return { accent: values[0], deep: values[1], highlight: values[2], background: values[3], text: values[4], muted: `${values[4]}aa` }
}

function buildTemplatePreviewSlides(template) {
  const name = template?.name || '学术蓝'
  const design = template?.design || 'academic'
  return [
    { kind: 'cover', label: '封面', eyebrow: name.toUpperCase(), title: '你的主题标题', subtitle: '一句话说明这份 PPT 要解决什么问题', index: '' },
    { kind: 'agenda', label: '目录', eyebrow: 'CONTENTS', title: '内容结构', subtitle: '清晰的章节节奏，让听众知道接下来会发生什么', bullets: ['背景与目标', '关键洞察', '方案与证据', '行动计划'], index: '' },
    { kind: 'section', label: '章节页', eyebrow: 'CHAPTER 01', title: '章节标题', subtitle: '用大字号和留白建立节奏', index: '01' },
    { kind: 'content', label: '内容页', eyebrow: 'KEY MESSAGE', title: '一句明确的结论先行', subtitle: '正文、要点和关键数据会根据你的资料自动替换', bullets: ['高价值信息优先', '图表与素材匹配内容', '相邻页面保持节奏变化'], metric: { value: design === 'dark-tech' ? 'AI' : '68%', label: '示意指标' }, index: '' },
    { kind: 'closing', label: '结尾页', eyebrow: 'NEXT STEP', title: '谢谢观看', subtitle: '把结论落到下一步行动', index: '' }
  ]
}

function templatePreviewStyle(slide) {
  const palette = (selectedTemplate.value?.palette || ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937']).map(color => `#${String(color).replace('#', '')}`)
  const dark = ['cover', 'section', 'closing'].includes(slide?.kind)
  return {
    '--template-accent': palette[0],
    '--template-deep': palette[1],
    '--template-highlight': palette[2],
    '--template-bg': dark ? palette[1] : palette[3],
    '--template-text': dark ? '#ffffff' : palette[4]
  }
}

async function submitRevision() {
  const promptText = revisionPrompt.value.trim()
  const slides = previewEditing.value
    ? previewSlides.value.map((slide, index) => ({
        slideIndex: index + 1,
        title: (slide.title || '').trim(),
        headline: (slide.headline || '').trim(),
        bullets: String(slide.bulletText || '').split(/\n+/).map(value => value.trim()).filter(Boolean).slice(0, 6)
      }))
    : []
  if (!promptText && !slides.some(slide => slide.title || slide.headline || slide.bullets.length)) {
    errorMsg.value = '请输入修改要求，或先打开网页编辑修改页面内容'
    return
  }
  revisionSubmitting.value = true
  errorMsg.value = ''
  const idempotencyKey = typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `ppt-revise-${Date.now()}-${Math.random().toString(36).slice(2)}`
  try {
    const res = await revisePptGenerationTask(taskId.value, taskAccessToken.value, {
      prompt: promptText,
      slides,
      idempotencyKey
    })
    rememberTaskToken(res.data.taskId, res.data.accessToken)
    if (typeof res.data.credits !== 'undefined') auth.updateCredits(res.data.credits)
    setActiveTask(res.data)
    revisionPrompt.value = ''
    previewEditing.value = false
    step.value = 'running'
    openStream(taskId.value)
    await loadRecent()
  } catch (error) {
    errorMsg.value = error.message || 'PPT 二次修改提交失败'
  } finally {
    revisionSubmitting.value = false
  }
}

async function loadQuotaSettings() {
  try {
    const res = await getQuotaSettings()
    pptCreditPerTask.value = Number(res.data?.pptCreditPerTask || 10)
  } catch {
    pptCreditPerTask.value = 10
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
  if (!data?.taskId) return
  activeTask.value = data
  taskId.value = data.taskId
  taskAccessToken.value = data.accessToken || tokenForTask(data.taskId)
  persistActiveTask()
  templateKey.value = data.templateKey || templateKey.value
  outputFormat.value = normalizeOutputFormat(data.outputFormat || outputFormat.value)
  ensureTemplateCategoryExpanded(templateKey.value)
  progress.value = Number(data.progress || 0)
  progressStage.value = data.progressStage || 'queued'
  progressStageLabel.value = data.progressStageLabel || statusLabel(data)
  queuePosition.value = data.queuePosition || 0
}

async function downloadCurrent() {
  if (activeTask.value?.status !== 'completed' || !taskId.value) return
  try {
    await downloadGeneratedPpt(taskId.value, taskAccessToken.value, activeTask.value?.outputFormat)
  } catch (error) {
    errorMsg.value = error.message || `${outputFormatLabel(activeTask.value)} 下载失败`
  }
}

function resetForm() {
  prompt.value = ''
  templateFile.value = null
  sourceFile.value = null
  errorMsg.value = ''
}

function backToForm() {
  closeStream()
  stopPolling()
  clearActiveTask()
  step.value = 'form'
  activeTask.value = null
  taskId.value = ''
  taskAccessToken.value = ''
  progress.value = 0
  queuePosition.value = 0
  previewEditing.value = false
  revisionPrompt.value = ''
  clearPreview()
}

async function restoreActiveTask() {
  const saved = readActiveTask()
  if (!saved?.taskId) return
  taskId.value = saved.taskId
  taskAccessToken.value = saved.accessToken || tokenForTask(saved.taskId)
  try {
    const res = await getPptGenerationStatus(taskId.value, taskAccessToken.value)
    setActiveTask(res.data)
    errorMsg.value = res.data.errorMessage || ''
    if (res.data.status === 'completed') {
      step.value = 'result'
      await loadPreview()
    } else if (res.data.status === 'error') {
      step.value = 'running'
    } else {
      step.value = 'running'
      openStream(taskId.value)
    }
  } catch {
    clearActiveTask()
  }
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
  streamGeneration += 1
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
  try {
    sessionStorage.setItem(PPT_TASK_TOKENS_KEY, JSON.stringify(Object.fromEntries(recentEntries)))
  } catch {
    // Private browsing / quota restrictions must not turn a successful task into a submit error.
  }
}

function tokenForTask(id) {
  return readTaskTokens()[id] || ''
}

function readActiveTask() {
  try {
    return JSON.parse(sessionStorage.getItem(PPT_ACTIVE_TASK_KEY) || 'null')
  } catch {
    return null
  }
}

function persistActiveTask() {
  if (!taskId.value) return
  try {
    sessionStorage.setItem(PPT_ACTIVE_TASK_KEY, JSON.stringify({
      taskId: taskId.value,
      accessToken: taskAccessToken.value || ''
    }))
  } catch {
    // Continue with in-memory state when sessionStorage is unavailable.
  }
}

function clearActiveTask() {
  try { sessionStorage.removeItem(PPT_ACTIVE_TASK_KEY) } catch {}
}

function defaultTemplates() {
  return [
    { key: 'academic-blue', name: '学术蓝', description: '清晰克制，适合研究、课程和正式汇报', palette: ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937'], source: 'Marp Core · default', license: 'MIT', sourceUrl: 'https://github.com/marp-team/marp-core', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'minimal-ink', name: '极简黑白', description: '高对比、留白充足，适合技术分享和决策简报', palette: ['111827', '374151', '0EA5E9', 'F8FAFC', '1F2937'], source: 'Marp Core · uncover', license: 'MIT', sourceUrl: 'https://github.com/marp-team/marp-core', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'emerald-report', name: '数据绿', description: '沉稳、偏报告感，适合项目复盘和经营数据', palette: ['047857', '064E3B', 'F59E0B', 'ECFDF5', '1F2937'], source: 'Marp Core · gaia', license: 'MIT', sourceUrl: 'https://github.com/marp-team/marp-core', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'warm-defense', name: '暖色演讲', description: '温和醒目，适合培训、主题分享和答辩', palette: ['B45309', '7C2D12', '2563EB', 'FFF7ED', '1F2937'], source: 'Marp Core · gaia', license: 'MIT', sourceUrl: 'https://github.com/marp-team/marp-core', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'gaia-editorial', name: 'Gaia 编辑感', description: '杂志式标题和强章节节奏，适合品牌故事、趋势与案例', palette: ['9A3412', '431407', '0F766E', 'FFF7ED', '292524'], source: 'Marp Core · gaia', license: 'MIT', sourceUrl: 'https://github.com/marp-team/marp-core', category: 'editorial', categoryLabel: '杂志与创意', complexity: 'rich' },
    { key: 'uncover-contrast', name: 'Uncover 高对比', description: '大字号、强聚焦、演讲现场识别度高', palette: ['0F172A', '020617', 'F97316', 'F8FAFC', 'E2E8F0'], source: 'Marp Core · uncover', license: 'MIT', sourceUrl: 'https://github.com/marp-team/marp-core', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'dracula-night', name: 'Dracula 夜色', description: '深色科技感，适合开发者、AI、产品和发布会', palette: ['BD93F9', '282A36', '50FA7B', '282A36', 'F8F8F2'], source: 'Dracula Marp', license: 'MIT', sourceUrl: 'https://github.com/dracula/marp', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'slidev-seriph', name: 'Seriph 叙事', description: '优雅的衬线标题和细腻层次，适合长文档与知识分享', palette: ['2563EB', '172554', 'F59E0B', 'F8FAFC', '334155'], source: 'Slidev official theme', license: 'MIT', sourceUrl: 'https://github.com/slidevjs/themes', category: 'core', categoryLabel: '基础风格', complexity: 'standard' },
    { key: 'slidev-apple-basic', name: 'Apple Basic', description: '黑白极简和大面积留白，适合产品发布和创意提案', palette: ['111827', '000000', '3B82F6', 'FFFFFF', '374151'], source: 'Slidev official theme', license: 'MIT', sourceUrl: 'https://github.com/slidevjs/themes', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'standard' },
    { key: 'startup-pitch', name: 'Startup Pitch', description: '问题—方案—证据—行动，适合融资、产品和商业计划', palette: ['7C3AED', '312E81', 'F59E0B', 'F5F3FF', '1F2937'], source: 'Marp ecosystem', license: 'MIT', sourceUrl: 'https://github.com/marp-team/awesome-marp', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'standard' },
    { key: 'product-launch', name: 'Product Launch', description: '大图、指标和场景切换，适合产品发布与增长复盘', palette: ['0E7490', '164E63', 'F43F5E', 'ECFEFF', '164E63'], source: 'Marp ecosystem', license: 'MIT', sourceUrl: 'https://github.com/marp-team/awesome-marp', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'standard' },
    { key: 'training-canvas', name: 'Training Canvas', description: '清楚的模块化教学节奏，适合课程、培训和工作坊', palette: ['2563EB', '1E3A8A', 'F97316', 'EFF6FF', '1E293B'], source: 'Slidev', license: 'MIT', sourceUrl: 'https://github.com/slidevjs/slidev', category: 'training', categoryLabel: '课程与培训', complexity: 'standard' },
    { key: 'ppt-master-editorial', name: 'Editorial Magazine', description: '杂志式图文叙事，适合品牌故事、案例和趋势洞察', palette: ['C2410C', '431407', 'F59E0B', 'FFF7ED', '292524'], source: 'PPT Master', license: 'MIT', sourceUrl: 'https://github.com/hugohe3/ppt-master', design: 'editorial', category: 'editorial', categoryLabel: '杂志与创意', complexity: 'rich' },
    { key: 'ppt-master-memphis', name: 'Memphis Pop', description: '几何图形、强色块和活泼节奏，适合活动、教育和创意提案', palette: ['F43F5E', '312E81', 'FACC15', 'FFF1F2', '1E1B4B'], source: 'PPT Master', license: 'MIT', sourceUrl: 'https://github.com/hugohe3/ppt-master', design: 'memphis', category: 'editorial', categoryLabel: '杂志与创意', complexity: 'rich' },
    { key: 'ppt-master-data-journalism', name: 'Data Journalism', description: '深色数据新闻风，适合经营分析、行业报告和复杂指标', palette: ['38BDF8', '0F172A', 'FBBF24', '111827', 'E2E8F0'], source: 'PPT Master', license: 'MIT', sourceUrl: 'https://github.com/hugohe3/ppt-master', design: 'data-journalism', category: 'data', categoryLabel: '数据与咨询', complexity: 'rich' },
    { key: 'ppt-master-swiss-grid', name: 'Swiss Grid', description: '严格网格、红色强调和咨询感结构，适合策略与方案汇报', palette: ['DC2626', '111827', 'FDE047', 'F8FAFC', '1F2937'], source: 'PPT Master', license: 'MIT', sourceUrl: 'https://github.com/hugohe3/ppt-master', design: 'swiss-grid', category: 'data', categoryLabel: '数据与咨询', complexity: 'rich' },
    { key: 'ppt-master-glassmorphism', name: 'Glassmorphism SaaS', description: '半透明层次、渐变深度和产品界面感，适合 SaaS 与 AI 产品', palette: ['A78BFA', '111827', '22D3EE', '111827', 'F8FAFC'], source: 'PPT Master', license: 'MIT', sourceUrl: 'https://github.com/hugohe3/ppt-master', design: 'glass-saas', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'rich' },
    { key: 'presenton-glass-saas', name: 'Presenton Glass SaaS', description: '大图、渐变卡片和场景化产品页，适合商业发布与增长复盘', palette: ['8B5CF6', '1E1B4B', '2DD4BF', 'F5F3FF', 'EDE9FE'], source: 'Presenton', license: 'Apache-2.0', sourceUrl: 'https://github.com/presenton/presenton', design: 'glass-saas', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'rich' },
    { key: 'presenton-gradient-pitch', name: 'Presenton Gradient Pitch', description: '高对比渐变和路演节奏，适合融资、商业计划和产品策略', palette: ['F97316', '4C1D95', 'FDE68A', 'F5F3FF', '312E81'], source: 'Presenton', license: 'Apache-2.0', sourceUrl: 'https://github.com/presenton/presenton', design: 'gradient-pitch', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'rich' },
    { key: 'presenton-product-studio', name: 'Presenton Product Studio', description: '产品截图、指标和双栏证据页，适合产品方案和客户案例', palette: ['06B6D4', '164E63', 'FB7185', 'ECFEFF', '164E63'], source: 'Presenton', license: 'Apache-2.0', sourceUrl: 'https://github.com/presenton/presenton', design: 'product-studio', category: 'product', categoryLabel: '产品与 SaaS', complexity: 'rich' },
    { key: 'primer-github-blueprint', name: 'Primer Blueprint', description: '开源项目蓝图风，适合技术架构、开发者和项目路线图', palette: ['0969DA', '1F2328', '54AEFF', 'F6F8FA', '1F2328'], source: 'GitHub Primer', license: 'Design system', sourceUrl: 'https://primer.style/presentations/presentation-formats/powerpoint/', design: 'primer', category: 'corporate', categoryLabel: '企业与开源', complexity: 'rich', openSource: false, usageNote: 'Primer-inspired 风格参考，非官方模板；本项目未分发 Primer 模板文件' },
    { key: 'primer-data-report', name: 'Primer Data Report', description: '清晰的企业报告结构，适合季度经营、项目复盘和数据说明', palette: ['8250DF', '24292F', 'BF8700', 'FFFFFF', '24292F'], source: 'GitHub Primer', license: 'Design system', sourceUrl: 'https://primer.style/presentations/presentation-formats/powerpoint/', design: 'primer-report', category: 'data', categoryLabel: '数据与咨询', complexity: 'rich', openSource: false, usageNote: 'Primer-inspired 风格参考，非官方模板；本项目未分发 Primer 模板文件' },
    { key: 'primer-open-source', name: 'Primer Open Source', description: '社区与开源项目叙事，适合技术社区、产品生态和发布说明', palette: ['1A7F37', '24292F', '9A6700', 'F6F8FA', '24292F'], source: 'GitHub Primer', license: 'Design system', sourceUrl: 'https://primer.style/presentations/presentation-formats/powerpoint/', design: 'primer-open-source', category: 'corporate', categoryLabel: '企业与开源', complexity: 'rich', openSource: false, usageNote: 'Primer-inspired 风格参考，非官方模板；本项目未分发 Primer 模板文件' }
  ]
}

function normalizeOutputFormat(value) {
  return String(value || 'pptx').toLowerCase() === 'html' ? 'html' : 'pptx'
}

function outputFormatLabel(task) {
  return normalizeOutputFormat(task?.outputFormat || outputFormat.value) === 'html' ? 'HTML' : 'PPTX'
}

function templateCategoryKey(template) {
  if (template?.category) return template.category
  const design = template?.design || template?.key || ''
  if (['editorial', 'memphis'].some(value => design.includes(value))) return 'editorial'
  if (['report', 'data', 'swiss', 'primer-report'].some(value => design.includes(value))) return 'data'
  if (['pitch', 'product', 'glass', 'apple'].some(value => design.includes(value))) return 'product'
  if (['training'].some(value => design.includes(value))) return 'training'
  return 'core'
}

function templateCategoryLabel(key) {
  return {
    core: '基础风格',
    editorial: '杂志与创意',
    data: '数据与咨询',
    product: '产品与 SaaS',
    corporate: '企业与开源',
    training: '课程与培训'
  }[key] || '其他风格'
}

function previewSlideStyle(slide, index) {
  const palette = (previewData.value?.palette || ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937']).map(color => `#${String(color).replace('#', '')}`)
  const dark = ['section', 'thanks'].includes(slide.type) || index === 0
  return {
    '--preview-accent': palette[0],
    '--preview-deep': palette[1],
    '--preview-highlight': palette[2],
    '--preview-bg': dark ? palette[1] : palette[3],
    '--preview-text': dark ? '#ffffff' : palette[4]
  }
}

function recentTitle(item) {
  return item.sourceFileName || item.paperFileName || item.templateFileName || trimPrompt(item.prompt)
}

function isSupportedSource(name) {
  return /\.(pdf|docx|pptx|xlsx|txt|md|csv|html|htm)$/i.test(name || '')
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
  background: #eee9df;
}

.quota-line {
  margin-top: 6px;
  color: #8f2a22;
  font-size: 14px;
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
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.1);
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
  color: #b83126;
  background: #f0e5d9;
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

.output-format-picker {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.output-format-card {
  display: grid;
  gap: 5px;
  padding: 14px 16px;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  background: #fbf9f3;
  color: #334155;
  text-align: left;
  cursor: pointer;
  transition: border-color .18s ease, box-shadow .18s ease, background .18s ease;
}

.output-format-card strong {
  color: #0f172a;
  letter-spacing: .04em;
}

.output-format-card span {
  color: #64748b;
  font-size: 12px;
}

.output-format-card.active {
  border-color: #b83126;
  background: #f3eadf;
  box-shadow: 0 0 0 3px rgba(184, 49, 38, .1);
}

.output-format-card:focus-visible,
.template-group__header:focus-visible {
  outline: 3px solid rgba(37, 99, 235, .28);
  outline-offset: 3px;
}

.template-grid,
.upload-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.template-picker {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(340px, .95fr);
  gap: 18px;
  align-items: start;
}

.template-groups {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.template-group {
  min-width: 0;
  border: 1px solid #d2cabc;
  background: rgba(255, 255, 255, .38);
}

.template-group__header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 13px;
  border: 0;
  background: transparent;
  color: #334155;
  text-align: left;
  cursor: pointer;
}

.template-group__header > span:first-child {
  display: grid;
  gap: 3px;
}

.template-group__header small,
.template-group__meta {
  color: #64748b;
  font-size: 11px;
}

.template-group__meta {
  flex: 0 0 auto;
  white-space: nowrap;
}

.template-group__meta b {
  display: inline-grid;
  width: 18px;
  height: 18px;
  margin-left: 5px;
  place-items: center;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
  color: #8f2a22;
  font-size: 15px;
  line-height: 1;
}

.template-group .template-grid {
  padding: 0 10px 10px;
}

.template-showcase {
  min-width: 0;
  padding: 14px;
  border: 1px solid #d2cabc;
  background: #f4efe7;
}

.template-showcase__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.template-showcase__heading h3 {
  margin-top: 4px;
  font-size: 18px;
}

.template-showcase__heading p {
  max-width: 330px;
  font-size: 12px;
  line-height: 1.45;
}

.template-showcase__eyebrow {
  color: #8f2a22;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .12em;
}

.template-showcase__stage {
  display: grid;
  gap: 10px;
}

.template-showcase__main {
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: var(--template-bg);
  box-shadow: 0 8px 18px rgba(55, 45, 30, .16);
}

.template-preview-slide {
  position: relative;
  height: 100%;
  overflow: hidden;
  padding: 8% 9%;
  color: var(--template-text);
  background: var(--template-bg);
}

.template-preview-design--editorial {
  font-family: Georgia, "Songti SC", serif;
}

.template-preview-design--editorial h4 {
  letter-spacing: -.04em;
}

.template-preview-design--memphis {
  background-image: radial-gradient(circle at 88% 12%, color-mix(in srgb, var(--template-accent) 48%, transparent) 0 8%, transparent 8.5%), linear-gradient(135deg, transparent 0 72%, color-mix(in srgb, var(--template-highlight) 24%, transparent) 72% 82%, transparent 82%);
}

.template-preview-design--swiss-grid {
  background-image: linear-gradient(to right, rgba(15, 23, 42, .08) 1px, transparent 1px), linear-gradient(to bottom, rgba(15, 23, 42, .08) 1px, transparent 1px);
  background-size: 20% 100%, 100% 25%;
}

.template-preview-design--glass-saas,
.template-preview-design--gradient-pitch,
.template-preview-design--primer {
  background-image: radial-gradient(circle at 90% 5%, color-mix(in srgb, var(--template-accent) 42%, transparent), transparent 34%), linear-gradient(135deg, transparent 20%, color-mix(in srgb, var(--template-highlight) 12%, transparent));
}

.template-preview-design--primer,
.template-preview-design--primer-report,
.template-preview-design--primer-open-source {
  border-radius: 3px;
}

.template-preview-slide::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--template-accent);
}

.template-preview-slide--cover,
.template-preview-slide--section,
.template-preview-slide--closing {
  background: var(--template-deep);
}

.template-preview-slide--cover::after,
.template-preview-slide--section::after {
  content: '';
  position: absolute;
  right: -12%;
  bottom: -28%;
  width: 62%;
  height: 86%;
  border-radius: 50%;
  background: color-mix(in srgb, var(--template-accent) 32%, transparent);
}

.template-preview-kicker {
  position: relative;
  z-index: 1;
  color: var(--template-highlight);
  font-size: clamp(7px, .75vw, 11px);
  font-weight: 800;
  letter-spacing: .12em;
}

.template-preview-slide h4 {
  position: relative;
  z-index: 1;
  max-width: 88%;
  margin: 12% 0 0;
  color: var(--template-text);
  font-size: clamp(20px, 3.1vw, 44px);
  line-height: 1.08;
}

.template-preview-slide--section h4 {
  margin-top: 15%;
  font-size: clamp(32px, 5vw, 68px);
}

.template-preview-subtitle {
  position: relative;
  z-index: 1;
  max-width: 78%;
  margin-top: 12px;
  color: color-mix(in srgb, var(--template-text) 76%, transparent);
  font-size: clamp(9px, 1vw, 14px);
  line-height: 1.45;
}

.template-preview-bullets {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 7px;
  max-width: 84%;
  margin: 18px 0 0;
  padding-left: 16px;
  color: var(--template-text);
  font-size: clamp(9px, 1vw, 14px);
  line-height: 1.35;
}

.template-preview-chapter {
  position: absolute;
  right: 9%;
  bottom: 13%;
  z-index: 1;
  color: var(--template-highlight);
  font-size: clamp(30px, 5vw, 70px);
  font-weight: 800;
  letter-spacing: -.08em;
}

.template-preview-metric {
  position: absolute;
  right: 9%;
  bottom: 14%;
  z-index: 1;
  display: grid;
  gap: 3px;
  padding: 12px 16px;
  border-left: 3px solid var(--template-accent);
  color: var(--template-text);
  background: color-mix(in srgb, var(--template-text) 8%, transparent);
}

.template-preview-metric strong {
  color: var(--template-accent);
  font-size: clamp(24px, 3.8vw, 52px);
  line-height: 1;
}

.template-preview-metric span {
  font-size: 10px;
}

.template-preview-footer {
  position: absolute;
  right: 9%;
  bottom: 7%;
  z-index: 1;
  color: color-mix(in srgb, var(--template-text) 58%, transparent);
  font-size: 9px;
  letter-spacing: .1em;
}

.template-showcase__thumbs {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}

.template-preview-thumb {
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #64748b;
  text-align: left;
  cursor: pointer;
}

.template-preview-thumb__canvas {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  padding: 8px;
  border: 2px solid transparent;
  background: var(--template-bg);
  color: var(--template-text);
}

.template-preview-thumb__canvas::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--template-accent);
}

.template-preview-thumb__canvas span {
  display: block;
  font-size: 8px;
  font-weight: 800;
  opacity: .7;
}

.template-preview-thumb__canvas strong {
  display: block;
  max-height: 30px;
  margin-top: 8px;
  overflow: hidden;
  font-size: 9px;
  line-height: 1.2;
}

.template-preview-thumb small {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-preview-thumb.active .template-preview-thumb__canvas {
  border-color: #b83126;
  box-shadow: 0 0 0 2px rgba(184, 49, 38, .12);
}

.template-showcase__note {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.45;
}

.template-card,
.file-box {
  border: 1px solid #d2cabc;
  border-radius: 2px;
  background: #fbf9f3;
  padding: 16px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  min-width: 0;
}

.template-card.active {
  border-color: #b83126;
  background: #f3eadf;
  box-shadow: 0 0 0 3px rgba(184, 49, 38, 0.1);
}

.template-card:focus-visible,
.file-box:focus-within {
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

.template-source {
  display: block;
  margin-top: 8px;
  font-size: 11px;
}

.template-recommendation {
  color: #8f2a22 !important;
}

.template-source a {
  color: #2563eb;
  text-decoration: none;
}

.template-source a:hover {
  text-decoration: underline;
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
  color: #b83126;
  border-color: #d9b5ab;
  background: #f3eadf;
}

.result-panel {
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.result-panel--wide {
  align-items: stretch;
  text-align: left;
  min-height: 0;
}

.result-toolbar,
.result-heading,
.result-toolbar-actions,
.revision-box__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.result-heading {
  justify-content: flex-start;
}

.result-heading .result-mark {
  flex: 0 0 auto;
  width: 62px;
  height: 62px;
  margin: 0;
}

.result-toolbar-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.preview-alert {
  margin-top: 18px;
}

.preview-empty {
  margin: 20px 0;
  padding: 32px;
  border: 1px dashed #cbd5e1;
  color: #64748b;
  text-align: center;
}

.preview-workbench {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 16px;
  margin-top: 22px;
  min-width: 0;
}

.preview-rail {
  display: grid;
  align-content: start;
  gap: 10px;
  max-height: 700px;
  overflow-y: auto;
  padding-right: 2px;
}

.preview-thumb {
  position: relative;
  display: block;
  width: 100%;
  padding: 3px 3px 18px;
  border: 1px solid #d2cabc;
  background: #f4efe7;
  cursor: pointer;
}

.preview-thumb.active {
  border-color: #b83126;
  box-shadow: 0 0 0 2px rgba(184, 49, 38, .14);
}

.preview-thumb__canvas {
  display: block;
  width: 100%;
  height: auto;
  background: #fff;
}

.preview-thumb > span {
  position: absolute;
  right: 6px;
  bottom: 3px;
  color: #64748b;
  font-size: 10px;
}

.preview-stage {
  min-width: 0;
}

.preview-stage__canvas-wrap {
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border: 1px solid #d2cabc;
  background: #e7e1d8;
  box-shadow: 0 8px 24px rgba(55, 45, 30, .14);
}

.preview-canvas {
  display: block;
  width: 100%;
  height: 100%;
}

.preview-stage__caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  color: #475569;
  font-size: 12px;
}

.preview-stage__caption > div {
  display: grid;
  gap: 3px;
}

.preview-stage__caption span {
  color: #64748b;
}

.preview-stage__hint {
  white-space: nowrap;
}

.preview-editor {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: 1fr 1fr 1.6fr auto;
  gap: 10px;
  align-items: start;
  padding: 14px;
  border: 1px solid #d2cabc;
  background: #f4efe7;
}

.preview-editor__heading {
  display: grid;
  gap: 4px;
  color: #334155;
  font-size: 13px;
}

.preview-editor__heading span {
  color: #64748b;
  font-size: 11px;
}

.preview-editor__nav {
  display: flex;
  gap: 6px;
}

.preview-slide {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  padding: 22px 24px 18px;
  background: var(--preview-bg);
  color: var(--preview-text);
  border: 1px solid color-mix(in srgb, var(--preview-accent) 35%, #ffffff 65%);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);
}

.preview-slide::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--preview-accent);
}

.preview-slide-index {
  position: absolute;
  top: 10px;
  right: 14px;
  color: color-mix(in srgb, var(--preview-text) 60%, transparent);
  font-size: 10px;
  letter-spacing: .08em;
}

.preview-slide-section {
  color: var(--preview-highlight);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .1em;
  text-transform: uppercase;
}

.preview-slide h3 {
  max-width: 90%;
  margin-top: 12px;
  color: var(--preview-text);
  font-size: clamp(16px, 2.2vw, 27px);
  line-height: 1.15;
}

.preview-slide-headline {
  color: color-mix(in srgb, var(--preview-text) 72%, transparent);
  font-size: 12px;
  line-height: 1.4;
}

.preview-slide-bullets {
  display: grid;
  gap: 5px;
  margin: 14px 0 0;
  padding-left: 17px;
  color: color-mix(in srgb, var(--preview-text) 88%, transparent);
  font-size: 12px;
  line-height: 1.35;
}

.preview-slide-image {
  display: block;
  max-width: 54%;
  max-height: 44%;
  margin: 10px 0 0 auto;
  object-fit: contain;
  border: 1px solid color-mix(in srgb, var(--preview-accent) 35%, transparent);
}

.preview-slide-metrics {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.preview-slide-metrics span {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid color-mix(in srgb, var(--preview-accent) 36%, transparent);
  color: color-mix(in srgb, var(--preview-text) 74%, transparent);
  font-size: 9px;
}

.preview-slide-metrics strong {
  color: var(--preview-accent);
  font-size: 16px;
}

.preview-input {
  position: relative;
  z-index: 1;
  margin-top: 8px;
}

.preview-input--title :deep(.n-input__input-el) {
  font-weight: 700;
}

.revision-box {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #e2e8f0;
}

.revision-box__heading {
  align-items: flex-start;
  margin-bottom: 12px;
}

.revision-box__heading h3 {
  font-size: 18px;
}

.revision-box__heading p {
  font-size: 13px;
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
  border: 1px solid #d2cabc;
  border-radius: 2px;
  background: #fbf9f3;
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

  .template-picker {
    grid-template-columns: 1fr;
  }

  .preview-workbench {
    grid-template-columns: 78px minmax(0, 1fr);
    gap: 10px;
  }

  .preview-editor {
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

  .result-toolbar,
  .revision-box__heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .result-toolbar-actions {
    justify-content: flex-start;
  }

  .preview-stage__caption {
    align-items: flex-start;
    flex-direction: column;
  }

  .preview-stage__hint {
    white-space: normal;
  }

  .template-showcase__thumbs {
    gap: 4px;
  }
}
</style>
