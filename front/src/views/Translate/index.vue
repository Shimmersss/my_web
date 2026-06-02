<template>
  <div class="translate-page">
    <div class="container">
      <!-- 上传态 -->
      <div v-if="step === 'upload'" class="upload-section">
        <div class="upload-header">
          <h1>论文翻译</h1>
          <p>上传英文 PDF 论文，AI 自动翻译为中文</p>
        </div>

        <div
          class="drop-zone"
          :class="{ dragging: isDragging }"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          @click="triggerFileInput"
        >
          <div class="drop-icon">
            <n-icon size="64" color="#1890ff">
              <CloudUploadOutline />
            </n-icon>
          </div>
          <p class="drop-text">拖拽 PDF 到此处，或点击选择文件</p>
          <p class="drop-hint">支持 .pdf 格式，最大 50MB</p>
          <input
            ref="fileInput"
            type="file"
            accept=".pdf"
            style="display: none"
            @change="handleFileSelect"
          />
        </div>

        <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" style="margin-top: 16px" />
      </div>

      <!-- 配置态：选择页面范围 -->
      <div v-else-if="step === 'config'" class="config-section">
        <div class="config-card">
          <div class="config-header">
            <n-icon size="32" color="#1890ff">
              <DocumentTextOutline />
            </n-icon>
            <div>
              <h2>{{ fileName }}</h2>
              <p>共 {{ totalPages }} 页</p>
            </div>
          </div>

          <div class="config-body">
            <div class="range-label">翻译页面范围</div>
            <div class="range-row">
              <n-input-number
                v-model:value="startPage"
                :min="1"
                :max="endPage"
                placeholder="起始页"
                style="width: 120px"
              />
              <span class="range-sep">至</span>
              <n-input-number
                v-model:value="endPage"
                :min="startPage"
                :max="totalPages"
                placeholder="结束页"
                style="width: 120px"
              />
              <n-button text type="primary" @click="selectAll" style="margin-left: 8px">
                全选
              </n-button>
            </div>
            <p class="range-hint">将翻译第 {{ startPage }} 到 {{ endPage }} 页的内容</p>

            <n-button
              type="primary"
              size="large"
              :loading="isStarting"
              @click="handleStartTranslate"
              style="margin-top: 24px; width: 100%"
            >
              开始翻译
            </n-button>

            <n-button text @click="resetToUpload" style="margin-top: 12px; width: 100%">
              重新选择文件
            </n-button>
          </div>
        </div>

        <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" style="margin-top: 16px" />
      </div>

      <!-- 翻译中态 -->
      <div v-else-if="step === 'translating'" class="progress-section">
        <div class="progress-header">
          <n-icon size="32" color="#1890ff">
            <DocumentTextOutline />
          </n-icon>
          <div>
            <h2>{{ fileName }}</h2>
            <p>第 {{ translateStartPage }}-{{ translateEndPage }} 页 · 共 {{ totalParagraphs }} 个段落</p>
          </div>
        </div>

        <div class="progress-bar-wrap">
          <n-progress
            type="line"
            :percentage="progressPercent"
            :processing="progressPercent < 100"
            :indicator-text-color="progressPercent < 100 ? '#1890ff' : '#52c41a'"
          />
          <p class="progress-text">
            {{ progressPercent < 100 ? `正在翻译第 ${completedParagraphs + 1} / ${totalParagraphs} 段...` : '翻译完成！' }}
          </p>
        </div>

        <!-- 实时预览已翻译段落 -->
        <div class="preview-list">
          <div v-for="(item, i) in translatedParagraphs" :key="i" class="preview-item">
            <div class="preview-original">{{ item.original }}</div>
            <div class="preview-translated">{{ item.translated }}</div>
          </div>
        </div>

        <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" style="margin-top: 16px">
          <template #default>
            <n-button size="small" @click="resetToUpload" style="margin-top: 8px">重新上传</n-button>
          </template>
        </n-alert>
      </div>

      <!-- 结果态 -->
      <div v-else-if="step === 'result'" class="result-section">
        <div class="result-toolbar">
          <div class="result-info">
            <n-icon size="24" color="#52c41a">
              <CheckmarkCircleOutline />
            </n-icon>
            <span>翻译完成 · 第 {{ translateStartPage }}-{{ translateEndPage }} 页 · {{ totalParagraphs }} 个段落</span>
          </div>

          <div class="result-actions">
            <n-radio-group v-model:value="displayMode" size="small">
              <n-radio-button value="bilingual">双语对照</n-radio-button>
              <n-radio-button value="translated">纯中文</n-radio-button>
            </n-radio-group>

            <n-button size="small" @click="copyAll">
              <template #icon><n-icon><CopyOutline /></n-icon></template>
              复制全文
            </n-button>
            <n-button size="small" @click="downloadResult">
              <template #icon><n-icon><DownloadOutline /></n-icon></template>
              下载 TXT
            </n-button>
            <n-button size="small" type="primary" @click="downloadPdf">
              <template #icon><n-icon><DownloadOutline /></n-icon></template>
              下载翻译 PDF
            </n-button>
            <n-button size="small" @click="resetToUpload">
              <template #icon><n-icon><RefreshOutline /></n-icon></template>
              重新翻译
            </n-button>
          </div>
        </div>

        <div class="result-list">
          <div v-for="(item, i) in translatedParagraphs" :key="i" class="result-item">
            <template v-if="displayMode === 'bilingual'">
              <div class="result-page-tag">第 {{ item.pageNumber }} 页 · 段落 {{ i + 1 }}</div>
              <div class="result-original">{{ item.original }}</div>
              <div class="result-translated">{{ item.translated }}</div>
            </template>
            <template v-else>
              <div class="result-translated-only">{{ item.translated }}</div>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useMessage } from 'naive-ui'
import {
  CloudUploadOutline,
  DocumentTextOutline,
  CheckmarkCircleOutline,
  CopyOutline,
  DownloadOutline,
  RefreshOutline
} from '@vicons/ionicons5'
import { uploadPdf, startTranslation, getTranslationStatus, downloadTranslation, downloadTranslatedPdf } from '@/api'

const message = useMessage()

// 状态
const step = ref('upload') // 'upload' | 'config' | 'translating' | 'result'
const isDragging = ref(false)
const errorMsg = ref('')
const fileInput = ref(null)
const fileName = ref('')
const taskId = ref('')
const totalPages = ref(0)
const startPage = ref(1)
const endPage = ref(1)
const translateStartPage = ref(1)
const translateEndPage = ref(1)
const totalParagraphs = ref(0)
const completedParagraphs = ref(0)
const translatedParagraphs = ref([])
const displayMode = ref('bilingual')
const isStarting = ref(false)
let eventSource = null

const progressPercent = computed(() => {
  if (totalParagraphs.value === 0) return 0
  return Math.round((completedParagraphs.value / totalParagraphs.value) * 100)
})

// 触发文件选择
function triggerFileInput() {
  fileInput.value?.click()
}

// 处理拖拽
function handleDrop(e) {
  isDragging.value = false
  const files = e.dataTransfer?.files
  if (files && files.length > 0) {
    processFile(files[0])
  }
}

// 处理文件选择
function handleFileSelect(e) {
  const files = e.target.files
  if (files && files.length > 0) {
    processFile(files[0])
  }
  e.target.value = ''
}

// 处理文件上传（只获取页数，不开始翻译）
async function processFile(file) {
  errorMsg.value = ''

  if (!file.name.toLowerCase().endsWith('.pdf')) {
    errorMsg.value = '仅支持 PDF 文件格式'
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    errorMsg.value = '文件大小超过 50MB 限制'
    return
  }

  fileName.value = file.name

  try {
    const res = await uploadPdf(file)
    if (res.code !== 200) {
      throw new Error(res.message || '上传失败')
    }

    taskId.value = res.data.taskId
    totalPages.value = res.data.totalPages
    startPage.value = 1
    endPage.value = res.data.totalPages

    // 保存 taskId 用于断线重连
    sessionStorage.setItem('translateTaskId', taskId.value)

    // 进入配置态
    step.value = 'config'
  } catch (e) {
    errorMsg.value = e.message || '上传失败，请重试'
  }
}

// 全选页面
function selectAll() {
  startPage.value = 1
  endPage.value = totalPages.value
}

// 开始翻译
async function handleStartTranslate() {
  isStarting.value = true
  errorMsg.value = ''

  try {
    const res = await startTranslation(taskId.value, startPage.value, endPage.value)
    if (res.code !== 200) {
      throw new Error(res.message || '启动翻译失败')
    }

    totalParagraphs.value = res.data.paragraphCount
    translateStartPage.value = startPage.value
    translateEndPage.value = endPage.value
    translatedParagraphs.value = []
    completedParagraphs.value = 0

    // 进入翻译态并开始 SSE
    step.value = 'translating'
    startSSE()
  } catch (e) {
    errorMsg.value = e.message || '启动翻译失败，请重试'
  } finally {
    isStarting.value = false
  }
}

// 开始 SSE 连接
function startSSE() {
  if (eventSource) {
    eventSource.close()
  }

  eventSource = new EventSource(`/api/translate/stream/${taskId.value}`)

  eventSource.addEventListener('progress', (e) => {
    try {
      const data = JSON.parse(e.data)
      translatedParagraphs.value.push({
        pageNumber: data.pageNumber,
        original: data.original,
        translated: data.translated
      })
      completedParagraphs.value = data.completed
    } catch (err) {
      console.error('解析 SSE 进度事件失败:', err)
    }
  })

  eventSource.addEventListener('done', () => {
    step.value = 'result'
    eventSource?.close()
    eventSource = null
    message.success('翻译完成！')
  })

  eventSource.addEventListener('error', (e) => {
    try {
      const data = JSON.parse(e.data)
      errorMsg.value = data.message || '翻译过程中发生错误'
    } catch {
      if (step.value === 'translating' && translatedParagraphs.value.length === 0) {
        errorMsg.value = '翻译连接中断，请重试'
      }
    }
    eventSource?.close()
    eventSource = null
  })

  eventSource.onerror = () => {
    if (step.value === 'translating' && translatedParagraphs.value.length < totalParagraphs.value) {
      if (translatedParagraphs.value.length > 0) {
        message.warning('连接中断，尝试重连...')
        setTimeout(() => {
          if (step.value === 'translating') {
            reconnectSSE()
          }
        }, 2000)
      } else {
        errorMsg.value = '连接失败，请检查网络后重试'
      }
    }
    eventSource?.close()
    eventSource = null
  }
}

// 断线重连
async function reconnectSSE() {
  try {
    const res = await getTranslationStatus(taskId.value)
    if (res.code === 200) {
      const data = res.data
      completedParagraphs.value = data.completedParagraphs

      if (data.status === 'completed') {
        step.value = 'result'
        message.success('翻译已完成')
        return
      }

      if (data.status === 'error') {
        errorMsg.value = data.errorMessage || '翻译失败'
        return
      }

      startSSE()
    }
  } catch {
    errorMsg.value = '重连失败，请刷新页面重试'
  }
}

// 复制全文
async function copyAll() {
  const text = translatedParagraphs.value
    .map((item, i) => {
      if (displayMode.value === 'bilingual') {
        return `[段落 ${i + 1} - 第 ${item.pageNumber} 页]\n原文: ${item.original}\n译文: ${item.translated}`
      }
      return item.translated
    })
    .join('\n\n')

  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选择文本')
  }
}

// 下载结果
function downloadResult() {
  downloadTranslation(taskId.value, displayMode.value)
}

// 下载翻译 PDF
function downloadPdf() {
  downloadTranslatedPdf(taskId.value)
}

// 重置到上传态
function resetToUpload() {
  eventSource?.close()
  eventSource = null
  step.value = 'upload'
  errorMsg.value = ''
  translatedParagraphs.value = []
  completedParagraphs.value = 0
  totalParagraphs.value = 0
  totalPages.value = 0
  taskId.value = ''
  fileName.value = ''
  sessionStorage.removeItem('translateTaskId')
}

// 页面加载时检查是否有进行中的任务
onMounted(async () => {
  const savedTaskId = sessionStorage.getItem('translateTaskId')
  if (savedTaskId) {
    try {
      const res = await getTranslationStatus(savedTaskId)
      if (res.code === 200) {
        const data = res.data
        taskId.value = savedTaskId
        fileName.value = data.fileName
        totalPages.value = data.totalPages || 0
        totalParagraphs.value = data.totalParagraphs
        completedParagraphs.value = data.completedParagraphs

        if (data.status === 'completed') {
          sessionStorage.removeItem('translateTaskId')
          message.info('之前的翻译会话已过期，请重新上传')
        } else if (data.status === 'translating') {
          step.value = 'translating'
          message.info('恢复翻译进度...')
          startSSE()
        } else if (data.status === 'preview') {
          step.value = 'config'
        } else {
          sessionStorage.removeItem('translateTaskId')
        }
      } else {
        sessionStorage.removeItem('translateTaskId')
      }
    } catch {
      sessionStorage.removeItem('translateTaskId')
    }
  }
})

onBeforeUnmount(() => {
  eventSource?.close()
})
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.translate-page {
  padding-top: 80px;
  padding-bottom: 60px;
  min-height: 100vh;
  background: #f6f8fb;
}

.container {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 $spacing-lg;
}

/* ===== 上传态 ===== */
.upload-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.upload-header {
  text-align: center;
  margin-bottom: 32px;

  h1 {
    font-size: 32px;
    font-weight: 700;
    color: $text-color;
    margin: 0 0 8px;
  }

  p {
    font-size: 16px;
    color: #888;
    margin: 0;
  }
}

.drop-zone {
  width: 100%;
  max-width: 600px;
  padding: 60px 40px;
  border: 2px dashed #d0d7de;
  border-radius: 16px;
  background: #fff;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s ease;

  &:hover,
  &.dragging {
    border-color: $primary-color;
    background: #f0f7ff;
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(24, 144, 255, 0.1);
  }

  .drop-icon {
    margin-bottom: 16px;
  }

  .drop-text {
    font-size: 18px;
    color: $text-color;
    margin: 0 0 8px;
    font-weight: 500;
  }

  .drop-hint {
    font-size: 14px;
    color: #aaa;
    margin: 0;
  }
}

/* ===== 配置态 ===== */
.config-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.config-card {
  width: 100%;
  max-width: 480px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.config-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px 28px;
  border-bottom: 1px solid #f0f0f0;

  h2 {
    font-size: 18px;
    font-weight: 600;
    margin: 0;
    color: $text-color;
    word-break: break-all;
  }

  p {
    font-size: 14px;
    color: #888;
    margin: 4px 0 0;
  }
}

.config-body {
  padding: 24px 28px;
}

.range-label {
  font-size: 14px;
  font-weight: 500;
  color: $text-color;
  margin-bottom: 12px;
}

.range-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.range-sep {
  color: #888;
  font-size: 14px;
}

.range-hint {
  font-size: 13px;
  color: #aaa;
  margin: 12px 0 0;
}

/* ===== 翻译中态 ===== */
.progress-section {
  background: #fff;
  border-radius: 16px;
  padding: 32px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.progress-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;

  h2 {
    font-size: 20px;
    font-weight: 600;
    margin: 0;
    color: $text-color;
  }

  p {
    font-size: 14px;
    color: #888;
    margin: 4px 0 0;
  }
}

.progress-bar-wrap {
  margin-bottom: 32px;

  .progress-text {
    font-size: 14px;
    color: #666;
    margin: 8px 0 0;
    text-align: center;
  }
}

.preview-list {
  max-height: 400px;
  overflow-y: auto;
  border-top: 1px solid #f0f0f0;
  padding-top: 16px;
}

.preview-item {
  padding: 12px 0;
  border-bottom: 1px solid #f5f5f5;

  .preview-original {
    font-size: 13px;
    color: #999;
    margin-bottom: 6px;
    line-height: 1.5;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .preview-translated {
    font-size: 14px;
    color: $text-color;
    line-height: 1.6;
  }
}

/* ===== 结果态 ===== */
.result-section {
  background: #fff;
  border-radius: 16px;
  padding: 24px 32px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.result-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 500;
  color: $text-color;
}

.result-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.result-list {
  max-height: 70vh;
  overflow-y: auto;
}

.result-item {
  padding: 16px 0;
  border-bottom: 1px solid #f5f5f5;

  &:last-child {
    border-bottom: none;
  }
}

.result-page-tag {
  font-size: 12px;
  color: #aaa;
  margin-bottom: 8px;
}

.result-original {
  font-size: 14px;
  color: #666;
  line-height: 1.7;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  margin-bottom: 8px;
  border-left: 3px solid #d9d9d9;
}

.result-translated {
  font-size: 15px;
  color: $text-color;
  line-height: 1.8;
  padding: 12px 16px;
  background: #f6ffed;
  border-radius: 8px;
  border-left: 3px solid #52c41a;
}

.result-translated-only {
  font-size: 15px;
  color: $text-color;
  line-height: 1.8;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .translate-page {
    padding-top: 72px;
  }

  .drop-zone {
    padding: 40px 24px;
  }

  .config-card,
  .progress-section,
  .result-section {
    padding: 20px 16px;
  }

  .result-toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .range-row {
    flex-wrap: wrap;
  }
}
</style>
