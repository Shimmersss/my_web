<template>
  <div class="openclaw-page">
    <section v-if="!adminKey" class="login-card">
      <div class="brand-mark"><n-icon><ChatbubbleEllipsesOutline /></n-icon></div>
      <p class="eyebrow">OPENCLAW GATEWAY</p>
      <h1>OpenClaw 对话</h1>
      <p class="subtitle">输入管理员密钥后，在网页中直接与本机 OpenClaw 对话。</p>
      <n-form @submit.prevent="handleLogin">
        <n-input v-model:value="loginKey" type="password" show-password-on="click"
          placeholder="请输入 ADMIN_KEY" size="large" @keyup.enter="handleLogin" />
        <n-button type="primary" size="large" block :loading="loggingIn" @click="handleLogin">进入对话</n-button>
      </n-form>
      <p class="login-hint">Gateway token 仅由后端读取，不会发送到浏览器。</p>
    </section>

    <section v-else class="chat-shell">
      <aside class="sidebar" :class="{ open: sidebarOpen }">
        <div class="sidebar-top">
          <div class="sidebar-brand">
            <div class="compact-mark"><n-icon><ChatbubbleEllipsesOutline /></n-icon></div>
            <strong>OpenClaw</strong>
            <n-button quaternary circle class="mobile-close" @click="sidebarOpen = false">
              <template #icon><n-icon><CloseOutline /></n-icon></template>
            </n-button>
          </div>
          <n-button type="primary" block :loading="creatingSession" @click="createSession">
            <template #icon><n-icon><AddOutline /></n-icon></template>
            新建对话
          </n-button>
          <n-input v-model:value="sessionSearch" clearable placeholder="搜索对话" size="small">
            <template #prefix><n-icon><SearchOutline /></n-icon></template>
          </n-input>
        </div>

        <div class="session-list">
          <button v-for="session in filteredSessions" :key="session.key" class="session-item"
            :class="{ active: session.key === activeSessionKey }" @click="switchSession(session.key)">
            <n-icon><ChatbubbleOutline /></n-icon>
            <span class="session-copy">
              <strong>{{ sessionTitle(session) }}</strong>
              <small>{{ sessionMeta(session) }}</small>
            </span>
          </button>
          <p v-if="!sessionsLoading && filteredSessions.length === 0" class="sidebar-empty">没有匹配的对话</p>
        </div>

        <div class="sidebar-footer">
          <span class="status-dot" :class="{ error: loadError }"></span>
          <span>{{ loadError ? 'Gateway 异常' : 'Gateway 已连接' }}</span>
          <n-button text class="logout-button" @click="logout">退出</n-button>
        </div>
      </aside>
      <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>

      <main class="conversation">
        <header class="chat-header">
          <div class="header-title">
            <n-button quaternary circle class="mobile-menu" @click="sidebarOpen = true">
              <template #icon><n-icon><MenuOutline /></n-icon></template>
            </n-button>
            <div>
              <h1>{{ currentSessionTitle }}</h1>
              <p>{{ activeSessionKey }}</p>
            </div>
          </div>

          <div class="chat-actions">
            <n-select v-model:value="selectedModel" class="model-select" size="small"
              :options="modelOptions" placeholder="选择模型" :loading="workspaceLoading"
              @update:value="model => patchCurrentSession({ model })" />
            <n-select v-model:value="selectedThinking" class="thinking-select" size="small"
              :options="thinkingOptions" placeholder="思考级别"
              @update:value="thinkingLevel => patchCurrentSession({ thinkingLevel })" />
            <n-button quaternary circle :loading="loading || workspaceLoading" title="刷新消息、会话和模型" @click="refreshWorkspace">
              <template #icon><n-icon><RefreshOutline /></n-icon></template>
            </n-button>
            <n-button quaternary circle title="会话文件" @click="toggleArtifacts">
              <template #icon><n-icon><DocumentsOutline /></n-icon></template>
            </n-button>
            <n-button quaternary circle title="重置当前对话" @click="resetSession">
              <template #icon><n-icon><TrashOutline /></n-icon></template>
            </n-button>
          </div>
        </header>

        <div ref="messagesRef" class="messages" @scroll="handleMessagesScroll">
          <div v-if="loading && messages.length === 0" class="empty-state">
            <n-spin size="large" /><p>正在读取 OpenClaw 会话...</p>
          </div>
          <div v-else-if="loadError && messages.length === 0" class="empty-state error-text">
            <n-icon size="34"><AlertCircleOutline /></n-icon><p>{{ loadError }}</p>
            <n-button secondary @click="loadWorkspace">重新连接</n-button>
          </div>
          <div v-else-if="messages.length === 0" class="empty-state welcome">
            <div class="brand-mark small"><n-icon><ChatbubbleEllipsesOutline /></n-icon></div>
            <h2>开始一段新对话</h2>
            <p>输入消息，或键入 <code>/</code> 查看 OpenClaw 指令。</p>
          </div>

          <article v-for="item in messages" :key="item.id" class="message" :class="item.role">
            <div class="avatar">{{ item.role === 'user' ? '你' : 'OC' }}</div>
            <div class="message-main">
              <div class="message-author">{{ item.role === 'user' ? '你' : 'OpenClaw' }}</div>
              <div v-if="item.attachments?.length" class="message-attachments">
                <div v-for="attachment in item.attachments" :key="attachment.id" class="message-attachment">
                  <img v-if="attachment.previewUrl" :src="attachment.previewUrl" :alt="attachment.fileName" />
                  <span v-else><n-icon><DocumentsOutline /></n-icon>{{ attachment.fileName }}</span>
                </div>
              </div>
              <div v-if="item.role === 'assistant'" class="message-bubble markdown-body" v-html="item.html"></div>
              <div v-else-if="item.text" class="message-bubble">{{ item.text }}</div>
            </div>
          </article>

          <article v-if="sending || streamingText" class="message assistant pending">
            <div class="avatar">OC</div>
            <div class="message-main">
              <div class="message-author">OpenClaw</div>
              <div v-if="streamingText" class="message-bubble markdown-body streaming" v-html="streamingHtml"></div>
              <div v-else class="message-bubble typing"><i></i><i></i><i></i></div>
            </div>
          </article>
        </div>

        <footer class="composer-area">
          <button v-if="hasUnreadMessages" class="new-message-button" @click="scrollToBottom(true)">
            有新消息，回到底部
          </button>
          <div v-if="showArtifacts" class="artifact-panel">
            <div class="artifact-header">
              <strong>会话文件</strong>
              <n-button text :loading="artifactsLoading" @click="loadArtifacts">刷新</n-button>
            </div>
            <p v-if="!artifactsLoading && artifacts.length === 0">当前会话还没有可下载产物。</p>
            <button v-for="artifact in artifacts" :key="artifact.id" class="artifact-item" @click="downloadArtifact(artifact)">
              <span><strong>{{ artifact.title }}</strong><small>{{ artifact.mimeType || artifact.type }}</small></span>
              <n-icon><DownloadOutline /></n-icon>
            </button>
          </div>
          <div v-if="showCommandHints" class="command-panel">
            <button v-for="(command, index) in filteredCommands" :key="command.name"
              :class="{ selected: index === commandIndex }" @mousedown.prevent="insertCommand(command)">
              <code>{{ commandAlias(command) }}</code>
              <span>{{ command.description }}</span>
              <small>{{ command.category }}</small>
            </button>
            <p v-if="filteredCommands.length === 0">没有匹配的指令</p>
          </div>
          <div v-if="attachments.length" class="attachment-preview">
            <div v-for="attachment in attachments" :key="attachment.id" class="attachment-chip">
              <img v-if="attachment.previewUrl" :src="attachment.previewUrl" :alt="attachment.fileName" />
              <span><strong>{{ attachment.fileName }}</strong><small>{{ formatBytes(attachment.sizeBytes) }}</small></span>
              <button title="移除附件" @click="removeAttachment(attachment.id)">×</button>
            </div>
          </div>
          <div class="composer">
            <input ref="fileInputRef" class="file-input" type="file" multiple @change="handleFiles" />
            <n-button quaternary circle title="上传附件" :disabled="sending || attachments.length >= 5" @click="fileInputRef?.click()">
              <template #icon><n-icon><AttachOutline /></n-icon></template>
            </n-button>
            <n-input v-model:value="draft" type="textarea" :autosize="{ minRows: 1, maxRows: 7 }"
              placeholder="给 OpenClaw 发消息，输入 / 查看指令" :disabled="sending"
              @keydown="handleComposerKeydown" />
            <n-button type="primary" circle :loading="sending" :disabled="!draft.trim() && attachments.length === 0" @click="sendMessage">
              <template #icon><n-icon><SendOutline /></n-icon></template>
            </n-button>
          </div>
          <p class="composer-hint">Enter 发送 · Shift + Enter 换行 · Gateway 会话由本机 OpenClaw 保存</p>
        </footer>
      </main>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { NButton, NForm, NIcon, NInput, NSelect, NSpin } from 'naive-ui'
import {
  AddOutline, AlertCircleOutline, AttachOutline, ChatbubbleEllipsesOutline, ChatbubbleOutline,
  CloseOutline, DocumentsOutline, DownloadOutline, MenuOutline, RefreshOutline, SearchOutline,
  SendOutline, TrashOutline
} from '@vicons/ionicons5'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  createOpenClawSession, downloadOpenClawArtifact, getOpenClawArtifacts, getOpenClawCommands,
  getOpenClawHistory, getOpenClawInboundMedia, getOpenClawModels, getOpenClawSessions, loginOpenClaw,
  patchOpenClawSession, resetOpenClawSession, sendOpenClawMessage
} from '@/api'

const STORAGE_KEY = 'openclaw-admin-key'
const SESSION_STORAGE_KEY = 'openclaw-session-key'
const POLL_INTERVAL_MS = 5000
const REPLY_POLL_INTERVAL_MS = 750
const message = useMessage()

const loginKey = ref('')
const adminKey = ref(sessionStorage.getItem(STORAGE_KEY) || '')
const activeSessionKey = ref(sessionStorage.getItem(SESSION_STORAGE_KEY) || '')
const sessions = ref([])
const models = ref([])
const commands = ref([])
const defaults = ref({})
const rawMessages = ref([])
const optimisticMessages = ref([])
const sentAttachmentsByRawId = ref({})
const historicalAttachmentsByRawId = ref({})
const attachments = ref([])
const artifacts = ref([])
const draft = ref('')
const sessionSearch = ref('')
const loadError = ref('')
const loggingIn = ref(false)
const loading = ref(false)
const sessionsLoading = ref(false)
const workspaceLoading = ref(false)
const sending = ref(false)
const creatingSession = ref(false)
const artifactsLoading = ref(false)
const showArtifacts = ref(false)
const sidebarOpen = ref(false)
const messagesRef = ref(null)
const fileInputRef = ref(null)
const commandIndex = ref(0)
const streamingText = ref('')
const streamingRawId = ref('')
const hasUnreadMessages = ref(false)
const isNearBottom = ref(true)
let pollTimer = null
let pollCount = 0

const currentSession = computed(() => sessions.value.find(item => item.key === activeSessionKey.value) || {})
const currentSessionTitle = computed(() => sessionTitle(currentSession.value))
const selectedModel = ref(null)
const selectedThinking = ref(null)

const filteredSessions = computed(() => {
  const query = sessionSearch.value.trim().toLowerCase()
  return query
    ? sessions.value.filter(item => `${sessionTitle(item)} ${item.key}`.toLowerCase().includes(query))
    : sessions.value
})
const modelOptions = computed(() => models.value.map(item => ({
  label: `${item.name || item.id} · ${item.provider}`,
  value: `${item.provider}/${item.id}`
})))
const thinkingOptions = computed(() => {
  const values = currentSession.value.thinkingOptions || defaults.value.thinkingOptions || []
  return values.map(value => ({ label: value, value }))
})
const showCommandHints = computed(() => draft.value.startsWith('/') && !draft.value.includes('\n'))
const filteredCommands = computed(() => {
  const query = draft.value.slice(1).split(/\s/)[0].toLowerCase()
  return commands.value.filter(command => {
    const aliases = command.textAliases || []
    return [command.name, command.description, ...aliases].join(' ').toLowerCase().includes(query)
  }).slice(0, 9)
})
const streamingHtml = computed(() => DOMPurify.sanitize(marked.parse(streamingText.value, { breaks: true, gfm: true })))
const messages = computed(() => [...rawMessages.value
  .filter(item => item?.role === 'user' || item?.role === 'assistant')
  .map((item, index) => {
    const text = extractText(item.content)
    const id = rawMessageId(item, index)
    return {
      id,
      role: item.role,
      text,
      attachments: sentAttachmentsByRawId.value[id] || historicalAttachmentsByRawId.value[id] || [],
      html: item.role === 'assistant' ? DOMPurify.sanitize(marked.parse(text, { breaks: true, gfm: true })) : ''
    }
  })
  .filter(item => (item.text || item.attachments.length) && item.id !== streamingRawId.value),
...optimisticMessages.value])

function extractText(content) {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content.filter(block => block?.type === 'text').map(block => block.text || '').join('\n').trim()
}

function rawMessageId(item, index) {
  return item.__openclaw?.id || `${item.role}-${item.timestamp || index}-${index}`
}

function sessionTitle(session = {}) {
  return session.label || session.derivedTitle || session.title || session.lastMessagePreview || session.key?.split(':').pop() || '新对话'
}

function sessionMeta(session) {
  const model = session.model || defaults.value.model || '默认模型'
  return session.updatedAt ? `${model} · ${new Date(session.updatedAt).toLocaleDateString()}` : model
}

function commandAlias(command) {
  return command.textAliases?.[0] || `/${command.name}`
}

function syncSessionSettings() {
  const session = currentSession.value
  selectedModel.value = session.modelProvider && session.model ? `${session.modelProvider}/${session.model}` : null
  selectedThinking.value = session.thinkingLevel || session.thinkingDefault || defaults.value.thinkingDefault || null
}

async function scrollToBottom(force = false) {
  await nextTick()
  if (!messagesRef.value || (!force && !isNearBottom.value)) return
  messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  isNearBottom.value = true
  hasUnreadMessages.value = false
}

function handleMessagesScroll() {
  const container = messagesRef.value
  if (!container) return
  isNearBottom.value = container.scrollHeight - container.scrollTop - container.clientHeight < 96
  if (isNearBottom.value) hasUnreadMessages.value = false
}

function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = null
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    await loadHistory(false)
    pollCount += 1
    if (pollCount % 3 === 0) await loadSessions()
    if (pollCount % 12 === 0) await loadModels()
  }, POLL_INTERVAL_MS)
}

function handleUnauthorized() {
  sessionStorage.removeItem(STORAGE_KEY)
  adminKey.value = ''
  stopPolling()
  message.error('登录已失效，请重新输入管理员密钥')
}

async function loadSessions() {
  if (!adminKey.value || sessionsLoading.value) return
  sessionsLoading.value = true
  try {
    const result = await getOpenClawSessions(adminKey.value)
    sessions.value = result.data?.sessions || []
    defaults.value = result.data?.defaults || {}
    if (!sessions.value.some(item => item.key === activeSessionKey.value)) {
      activeSessionKey.value = sessions.value.find(item => item.key.endsWith(':main'))?.key || sessions.value[0]?.key || 'main'
      sessionStorage.setItem(SESSION_STORAGE_KEY, activeSessionKey.value)
    }
    syncSessionSettings()
  } catch (error) {
    if (String(error).includes('401')) handleUnauthorized()
    else loadError.value = '无法连接 OpenClaw Gateway，请确认后端和 Gateway 已启动。'
  } finally {
    sessionsLoading.value = false
  }
}

async function loadModels() {
  if (!adminKey.value) return
  try {
    const result = await getOpenClawModels(adminKey.value)
    models.value = result.data?.models || []
  } catch (error) {
    if (String(error).includes('401')) handleUnauthorized()
  }
}

async function loadHistory(showSpinner = true) {
  if (!adminKey.value || !activeSessionKey.value || loading.value) return
  if (showSpinner) loading.value = true
  try {
    const result = await getOpenClawHistory(adminKey.value, activeSessionKey.value)
    const nextMessages = result.data?.messages || []
    const changed = historySignature(nextMessages) !== historySignature(rawMessages.value)
    reconcileOptimisticMessages(nextMessages)
    if (changed) {
      rawMessages.value = nextMessages
      hydrateHistoricalAttachments(nextMessages)
      if (!isNearBottom.value) hasUnreadMessages.value = true
    }
    loadError.value = ''
    if (changed) await scrollToBottom()
  } catch (error) {
    if (String(error).includes('401')) handleUnauthorized()
    else loadError.value = '无法连接 OpenClaw Gateway，请确认后端和 Gateway 已启动。'
  } finally {
    loading.value = false
  }
}

function historySignature(items) {
  return items.map((item, index) => {
    const media = (item.MediaPaths || (item.MediaPath ? [item.MediaPath] : [])).join(',')
    return `${rawMessageId(item, index)}:${extractText(item.content)}:${media}`
  }).join('|')
}

function mediaRefs(item, index) {
  const paths = item.MediaPaths || (item.MediaPath ? [item.MediaPath] : [])
  const types = item.MediaTypes || (item.MediaType ? [item.MediaType] : [])
  return paths.map((path, mediaIndex) => ({
    id: `${rawMessageId(item, index)}-media-${mediaIndex}`,
    fileName: path.split('/').pop(),
    mimeType: types[mediaIndex] || 'application/octet-stream',
    sizeBytes: 0,
    previewUrl: ''
  }))
}

async function hydrateHistoricalAttachments(nextMessages) {
  for (const [index, item] of nextMessages.entries()) {
    if (item?.role !== 'user') continue
    const id = rawMessageId(item, index)
    if (sentAttachmentsByRawId.value[id] || historicalAttachmentsByRawId.value[id]) continue
    const refs = mediaRefs(item, index)
    if (!refs.length) continue
    historicalAttachmentsByRawId.value = { ...historicalAttachmentsByRawId.value, [id]: refs }
    const hydrated = await Promise.all(refs.map(async ref => {
      if (!ref.mimeType.startsWith('image/')) return ref
      try {
        const blob = await getOpenClawInboundMedia(adminKey.value, ref.fileName)
        return { ...ref, previewUrl: URL.createObjectURL(blob) }
      } catch {
        return ref
      }
    }))
    historicalAttachmentsByRawId.value = { ...historicalAttachmentsByRawId.value, [id]: hydrated }
  }
}

function clearHistoricalAttachmentUrls() {
  Object.values(historicalAttachmentsByRawId.value).flat().forEach(attachment => {
    if (attachment.previewUrl?.startsWith('blob:')) URL.revokeObjectURL(attachment.previewUrl)
  })
  historicalAttachmentsByRawId.value = {}
}

function reconcileOptimisticMessages(nextMessages) {
  if (!optimisticMessages.value.length) return
  const matched = new Set(Object.keys(sentAttachmentsByRawId.value))
  const users = nextMessages
    .map((item, index) => ({ item, id: rawMessageId(item, index) }))
    .filter(({ item, id }) => item?.role === 'user' && !matched.has(id))
    .reverse()
  const remaining = []
  for (const local of [...optimisticMessages.value].reverse()) {
    const index = users.findIndex(({ item }) => extractText(item.content) === local.text)
    if (index === -1) {
      remaining.unshift(local)
      continue
    }
    const [{ id }] = users.splice(index, 1)
    sentAttachmentsByRawId.value = { ...sentAttachmentsByRawId.value, [id]: local.attachments }
  }
  optimisticMessages.value = remaining
}

async function loadWorkspace() {
  if (!adminKey.value || workspaceLoading.value) return
  workspaceLoading.value = true
  try {
    const [, commandResult] = await Promise.all([
      loadModels(),
      getOpenClawCommands(adminKey.value),
      loadSessions()
    ])
    commands.value = commandResult.data?.commands || []
    await loadHistory()
  } catch (error) {
    if (String(error).includes('401')) handleUnauthorized()
    else loadError.value = '无法连接 OpenClaw Gateway，请确认后端和 Gateway 已启动。'
  } finally {
    workspaceLoading.value = false
  }
}

async function refreshWorkspace() {
  await Promise.all([loadHistory(), loadSessions(), loadModels()])
}

async function handleLogin() {
  if (!loginKey.value.trim() || loggingIn.value) return
  loggingIn.value = true
  try {
    const result = await loginOpenClaw(loginKey.value.trim())
    adminKey.value = result.data.token
    sessionStorage.setItem(STORAGE_KEY, adminKey.value)
    loginKey.value = ''
    await loadWorkspace()
    startPolling()
  } catch {
    message.error('管理员密钥错误')
  } finally {
    loggingIn.value = false
  }
}

async function switchSession(key) {
  if (key === activeSessionKey.value) return
  activeSessionKey.value = key
  sessionStorage.setItem(SESSION_STORAGE_KEY, key)
  rawMessages.value = []
  optimisticMessages.value = []
  sentAttachmentsByRawId.value = {}
  clearHistoricalAttachmentUrls()
  attachments.value = []
  streamingText.value = ''
  streamingRawId.value = ''
  hasUnreadMessages.value = false
  isNearBottom.value = true
  showArtifacts.value = false
  sidebarOpen.value = false
  syncSessionSettings()
  await loadHistory()
}

async function createSession() {
  if (creatingSession.value) return
  creatingSession.value = true
  try {
    const result = await createOpenClawSession(adminKey.value)
    await loadSessions()
    await switchSession(result.data?.key)
  } catch {
    message.error('新建对话失败')
  } finally {
    creatingSession.value = false
  }
}

async function resetSession() {
  if (!activeSessionKey.value || !window.confirm('清空当前对话并重新开始？')) return
  try {
    await resetOpenClawSession(adminKey.value, activeSessionKey.value)
    rawMessages.value = []
    await loadSessions()
    await loadHistory()
    message.success('当前对话已重置')
  } catch {
    message.error('重置对话失败')
  }
}

async function patchCurrentSession(patch) {
  if (!activeSessionKey.value) return
  try {
    await patchOpenClawSession(adminKey.value, { sessionKey: activeSessionKey.value, ...patch })
    await loadSessions()
  } catch {
    message.error('更新会话设置失败')
    syncSessionSettings()
  }
}

async function sendMessage() {
  const content = draft.value.trim()
  if ((!content && attachments.value.length === 0) || sending.value) return
  const outgoingAttachments = attachments.value
  const localAttachments = outgoingAttachments.map(attachment => ({
    id: attachment.id,
    fileName: attachment.fileName,
    mimeType: attachment.mimeType,
    sizeBytes: attachment.sizeBytes,
    previewUrl: attachment.previewUrl
  }))
  const baselineSeq = Math.max(0, ...rawMessages.value.map(item => item.__openclaw?.seq || 0))
  const localId = `local-${Date.now()}`
  optimisticMessages.value.push({ id: localId, role: 'user', text: content, attachments: localAttachments, html: '' })
  draft.value = ''
  attachments.value = []
  sending.value = true
  await scrollToBottom(true)
  try {
    await sendOpenClawMessage(content, adminKey.value, activeSessionKey.value, outgoingAttachments)
    await trackAssistantReply(baselineSeq)
    await loadSessions()
  } catch (error) {
    draft.value = content
    attachments.value = outgoingAttachments
    optimisticMessages.value = optimisticMessages.value.filter(item => item.id !== localId)
    if (String(error).includes('401')) handleUnauthorized()
    else message.error('发送失败，请确认 OpenClaw Gateway 已启动')
  } finally {
    sending.value = false
    await loadHistory(false)
  }
}

async function trackAssistantReply(baselineSeq) {
  const expiresAt = Date.now() + 120000
  while (Date.now() < expiresAt) {
    await loadHistory(false)
    const candidate = [...rawMessages.value].reverse().find(item =>
      item?.role === 'assistant' &&
      (item.__openclaw?.seq || 0) > baselineSeq &&
      extractText(item.content)
    )
    if (candidate) {
      streamingRawId.value = rawMessageId(candidate, rawMessages.value.indexOf(candidate))
      await animateStreamingText(extractText(candidate.content))
      await new Promise(resolve => window.setTimeout(resolve, 180))
      streamingText.value = ''
      streamingRawId.value = ''
      return
    }
    await new Promise(resolve => window.setTimeout(resolve, REPLY_POLL_INTERVAL_MS))
  }
  message.warning('回复仍在处理中，页面会继续同步历史消息')
}

async function animateStreamingText(fullText) {
  streamingText.value = ''
  for (let index = 0; index < fullText.length; index += 3) {
    streamingText.value = fullText.slice(0, index + 3)
    if (index % 18 === 0) await scrollToBottom()
    await new Promise(resolve => window.setTimeout(resolve, 16))
  }
  await scrollToBottom()
}

function handleFiles(event) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  if (attachments.value.length + files.length > 5) {
    message.warning('一次最多上传 5 个附件')
    return
  }
  files.forEach(file => {
    if (file.size > 20 * 1024 * 1024) {
      message.warning(`${file.name} 超过 20 MB，已跳过`)
      return
    }
    const reader = new FileReader()
    reader.addEventListener('load', () => {
      attachments.value.push({
        id: `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
        previewUrl: file.type.startsWith('image/') ? reader.result : '',
        content: reader.result
      })
    })
    reader.readAsDataURL(file)
  })
}

function removeAttachment(id) {
  attachments.value = attachments.value.filter(item => item.id !== id)
}

function formatBytes(bytes = 0) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function loadArtifacts() {
  if (!activeSessionKey.value || artifactsLoading.value) return
  artifactsLoading.value = true
  try {
    const result = await getOpenClawArtifacts(adminKey.value, activeSessionKey.value)
    artifacts.value = result.data?.artifacts || []
  } catch {
    message.error('读取会话文件失败')
  } finally {
    artifactsLoading.value = false
  }
}

async function toggleArtifacts() {
  showArtifacts.value = !showArtifacts.value
  if (showArtifacts.value) await loadArtifacts()
}

async function downloadArtifact(artifact) {
  try {
    const result = await downloadOpenClawArtifact(adminKey.value, activeSessionKey.value, artifact.id)
    if (result.data?.url) window.open(result.data.url, '_blank', 'noopener')
  } catch {
    message.error('下载文件失败')
  }
}

function insertCommand(command) {
  draft.value = `${commandAlias(command)}${command.acceptsArgs ? ' ' : ''}`
  commandIndex.value = 0
}

function handleComposerKeydown(event) {
  if (showCommandHints.value && filteredCommands.value.length) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      commandIndex.value = (commandIndex.value + delta + filteredCommands.value.length) % filteredCommands.value.length
      return
    }
    if (event.key === 'Tab') {
      event.preventDefault()
      insertCommand(filteredCommands.value[commandIndex.value])
      return
    }
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function logout() {
  sessionStorage.removeItem(STORAGE_KEY)
  adminKey.value = ''
  rawMessages.value = []
  optimisticMessages.value = []
  sentAttachmentsByRawId.value = {}
  clearHistoricalAttachmentUrls()
  stopPolling()
}

onMounted(() => {
  if (adminKey.value) {
    loadWorkspace()
    startPolling()
  }
})
onUnmounted(() => {
  stopPolling()
  clearHistoricalAttachmentUrls()
})
</script>

<style scoped lang="scss">
.openclaw-page { min-height: calc(100vh - 72px); background: #f7f8fa; }
.login-card { width: min(420px, calc(100vw - 40px)); margin: 8vh auto; padding: 38px; border: 1px solid #e5e7eb; border-radius: 22px; background: white; text-align: center; box-shadow: 0 18px 50px rgba(15, 23, 42, .08); }
.login-card h1 { margin: 8px 0; font-size: 28px; }
.login-card .n-form { display: grid; gap: 12px; margin-top: 24px; }
.brand-mark, .compact-mark { display: grid; place-items: center; color: white; background: linear-gradient(135deg, #161b22, #425466); }
.brand-mark { width: 54px; height: 54px; margin: auto; border-radius: 18px; font-size: 28px; }
.brand-mark.small { width: 48px; height: 48px; font-size: 22px; }
.compact-mark { width: 32px; height: 32px; border-radius: 11px; font-size: 18px; }
.eyebrow { margin: 14px 0 0; color: #667085; font-size: 10px; font-weight: 700; letter-spacing: .18em; }
.subtitle, .login-hint { color: #667085; font-size: 13px; line-height: 1.7; }
.login-hint { margin: 18px 0 0; font-size: 12px; }
.chat-shell { display: flex; height: calc(100vh - 72px); overflow: hidden; background: white; }
.sidebar { z-index: 4; display: flex; width: 272px; flex: 0 0 272px; flex-direction: column; border-right: 1px solid #e8eaed; background: #f8f9fb; }
.sidebar-top { display: grid; gap: 14px; padding: 16px 14px 12px; }
.sidebar-brand { display: flex; align-items: center; gap: 9px; color: #1d2939; }
.sidebar-brand strong { flex: 1; }
.mobile-close, .mobile-menu { display: none; }
.session-list { flex: 1; overflow-y: auto; padding: 2px 8px 12px; }
.session-item { display: flex; width: 100%; gap: 9px; align-items: center; padding: 10px 9px; border: 0; border-radius: 9px; color: #475467; background: transparent; cursor: pointer; text-align: left; }
.session-item:hover { background: #eef1f5; }
.session-item.active { color: #101828; background: #e8ecf2; }
.session-copy { display: grid; min-width: 0; gap: 2px; }
.session-copy strong, .session-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-copy strong { font-size: 13px; font-weight: 600; }
.session-copy small { color: #98a2b3; font-size: 11px; }
.sidebar-empty { padding: 16px 8px; color: #98a2b3; font-size: 12px; }
.sidebar-footer { display: flex; gap: 7px; align-items: center; padding: 13px 14px; border-top: 1px solid #e8eaed; color: #667085; font-size: 12px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: #12b76a; }.status-dot.error { background: #f04438; }
.logout-button { margin-left: auto; }
.conversation { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.chat-header { display: flex; min-height: 65px; gap: 16px; align-items: center; justify-content: space-between; padding: 0 20px; border-bottom: 1px solid #eaecf0; }
.header-title { display: flex; min-width: 0; align-items: center; gap: 6px; }
.header-title h1 { max-width: 340px; overflow: hidden; margin: 0; color: #101828; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.header-title p { max-width: 360px; overflow: hidden; margin: 2px 0 0; color: #98a2b3; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.chat-actions { display: flex; align-items: center; gap: 7px; }
.model-select { width: 215px; }.thinking-select { width: 104px; }
.messages { flex: 1; overflow-y: auto; padding: 24px max(24px, calc((100% - 820px) / 2)); }
.empty-state { display: grid; min-height: 100%; place-content: center; justify-items: center; color: #667085; text-align: center; }
.welcome h2 { margin: 16px 0 0; color: #344054; font-size: 20px; }.welcome p { font-size: 13px; }.welcome code { color: #344054; }
.error-text { color: #d92d20; }
.message { display: flex; gap: 12px; margin-bottom: 24px; }
.message.user { flex-direction: row-reverse; }
.avatar { display: grid; width: 31px; height: 31px; flex: 0 0 31px; place-items: center; border-radius: 10px; color: white; background: #344054; font-size: 11px; font-weight: 700; }
.message.user .avatar { color: #344054; background: #e4e7ec; }
.message-main { min-width: 0; flex: 1; }.message.user .message-main { display: flex; flex-direction: column; align-items: flex-end; }
.message-author { margin-bottom: 5px; color: #475467; font-size: 12px; font-weight: 700; }
.message-bubble { max-width: min(760px, 92%); padding: 11px 14px; border: 1px solid #e4e7ec; border-radius: 4px 15px 15px; color: #1d2939; background: #fff; box-shadow: 0 3px 10px rgba(15, 23, 42, .045); font-size: 14px; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
.message.user .message-bubble { max-width: min(680px, 86%); border-color: #c7d7fe; border-radius: 15px 4px 15px 15px; background: #eef4ff; box-shadow: 0 3px 10px rgba(53, 92, 180, .07); }
.typing { display: flex; gap: 4px; padding-top: 7px; }.typing i { width: 6px; height: 6px; border-radius: 50%; background: #98a2b3; animation: blink 1.2s infinite; }.typing i:nth-child(2) { animation-delay: .2s; }.typing i:nth-child(3) { animation-delay: .4s; }
.composer-area { position: relative; padding: 0 max(20px, calc((100% - 820px) / 2)) 13px; }
.new-message-button { position: absolute; right: 50%; bottom: calc(100% + 12px); transform: translateX(50%); padding: 8px 13px; border: 1px solid #d0d5dd; border-radius: 18px; color: #344054; background: white; box-shadow: 0 5px 16px rgba(15, 23, 42, .1); cursor: pointer; font-size: 12px; }
.file-input { display: none; }
.attachment-preview { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 8px; }
.attachment-chip { display: flex; max-width: 240px; gap: 8px; align-items: center; padding: 7px 9px; border: 1px solid #e4e7ec; border-radius: 9px; background: #f9fafb; }
.attachment-chip img { width: 36px; height: 36px; border-radius: 6px; object-fit: cover; }
.attachment-chip span, .artifact-item span { display: grid; min-width: 0; gap: 1px; }.attachment-chip strong, .artifact-item strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.attachment-chip small, .artifact-item small { color: #98a2b3; font-size: 10px; }
.attachment-chip button { border: 0; color: #98a2b3; background: transparent; cursor: pointer; font-size: 17px; }
.artifact-panel { position: absolute; right: max(20px, calc((100% - 820px) / 2)); bottom: calc(100% + 7px); left: max(20px, calc((100% - 820px) / 2)); max-height: 330px; overflow-y: auto; padding: 9px; border: 1px solid #e4e7ec; border-radius: 12px; background: white; box-shadow: 0 16px 34px rgba(15, 23, 42, .13); }
.artifact-header, .artifact-item { display: flex; align-items: center; justify-content: space-between; }.artifact-header { padding: 3px 4px 7px; color: #344054; font-size: 13px; }.artifact-panel p { margin: 12px 4px; color: #98a2b3; font-size: 12px; }
.artifact-item { width: 100%; gap: 10px; padding: 9px 7px; border: 0; border-radius: 7px; color: #475467; background: white; cursor: pointer; text-align: left; }.artifact-item:hover { background: #f2f4f7; }
.message-attachments { display: flex; flex-wrap: wrap; gap: 8px; margin: 5px 0 8px; }.message.user .message-attachments { justify-content: flex-end; }
.message-attachment img { display: block; max-width: min(360px, 62vw); max-height: 300px; border: 1px solid #e4e7ec; border-radius: 10px; object-fit: contain; background: #f9fafb; }
.message-attachment span { display: flex; gap: 5px; align-items: center; padding: 7px 9px; border: 1px solid #e4e7ec; border-radius: 8px; color: #667085; background: #f9fafb; font-size: 12px; }
.streaming::after { display: inline-block; width: 6px; height: 14px; margin-left: 3px; background: #667085; content: ''; animation: blink .8s infinite; vertical-align: -2px; }
.composer { display: flex; gap: 10px; align-items: end; padding: 10px; border: 1px solid #d0d5dd; border-radius: 15px; background: white; box-shadow: 0 4px 18px rgba(15, 23, 42, .05); }
.composer :deep(.n-input) { background: transparent; }.composer :deep(.n-input__border), .composer :deep(.n-input__state-border) { display: none; }
.composer-hint { margin: 7px 4px 0; color: #98a2b3; font-size: 11px; text-align: center; }
.command-panel { position: absolute; right: max(20px, calc((100% - 820px) / 2)); bottom: calc(100% + 7px); left: max(20px, calc((100% - 820px) / 2)); max-height: 330px; overflow-y: auto; padding: 7px; border: 1px solid #e4e7ec; border-radius: 12px; background: white; box-shadow: 0 16px 34px rgba(15, 23, 42, .13); }
.command-panel button { display: grid; width: 100%; grid-template-columns: 130px 1fr auto; gap: 12px; padding: 9px; border: 0; border-radius: 7px; background: white; color: #475467; cursor: pointer; text-align: left; }
.command-panel button:hover, .command-panel button.selected { background: #f2f4f7; }.command-panel code { color: #175cd3; }.command-panel small { color: #98a2b3; }
@keyframes blink { 0%, 60%, 100% { opacity: .35; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-2px); } }
@media (max-width: 760px) {
  .sidebar { position: fixed; top: 72px; bottom: 0; left: 0; transform: translateX(-100%); transition: transform .2s ease; }.sidebar.open { transform: translateX(0); }
  .sidebar-mask { position: fixed; z-index: 3; inset: 72px 0 0; background: rgba(15, 23, 42, .3); }
  .mobile-close, .mobile-menu { display: inline-flex; }.chat-header { padding: 0 10px; }.header-title p { display: none; }
  .model-select { width: 128px; }.thinking-select { display: none; }.chat-actions { gap: 2px; }
  .messages { padding: 18px 14px; }.composer-area { padding: 0 12px 9px; }.command-panel, .artifact-panel { right: 12px; left: 12px; }
}
</style>
