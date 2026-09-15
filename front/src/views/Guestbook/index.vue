<template>
  <main class="tool-page guestbook-page">
    <div class="container guestbook-shell">
      <header class="tool-page__header">
        <p class="eyebrow">GUESTBOOK / 留言板</p>
        <h1>留下你的足迹</h1>
        <p>欢迎交流研究、创作与网站使用体验。留言公开展示；回复与点赞会通过站内通知提醒作者。</p>
      </header>

      <section class="tool-page__surface composer" aria-labelledby="new-message-title">
        <div class="section-heading"><div><span class="eyebrow">NEW MESSAGE</span><h2 id="new-message-title">写一条留言</h2></div><span v-if="auth.isLoggedIn" class="account-name">{{ auth.user?.username }}</span></div>
        <template v-if="auth.isLoggedIn">
          <n-input v-model:value="messageDraft" type="textarea" maxlength="1000" show-count :autosize="{ minRows: 4, maxRows: 8 }" placeholder="分享你的想法、建议或问题…" @keydown.meta.enter.prevent="submitMessage" @keydown.ctrl.enter.prevent="submitMessage" />
          <div class="composer-actions"><small>公开展示 · 支持纯文本 · ⌘/Ctrl + Enter 发布</small><n-button type="primary" :loading="posting" @click="submitMessage">发布留言</n-button></div>
        </template>
        <div v-else class="login-callout"><p>登录后即可发布留言、回复和点赞。</p><n-button type="primary" @click="requestLogin">登录后留言</n-button></div>
      </section>

      <section class="thread-section" aria-labelledby="message-list-title">
        <div class="list-heading"><div><span class="eyebrow">CONVERSATION</span><h2 id="message-list-title">大家在说</h2></div><n-button text :loading="loading" @click="loadMessages(1)">刷新</n-button></div>
        <n-alert v-if="errorMessage" type="error" closable :title="errorMessage" @close="errorMessage = ''" />
        <n-spin :show="loading">
          <p v-if="!messages.length && !loading" class="empty-thread">还没有留言，来写下第一条吧。</p>
          <div class="message-list">
            <article v-for="message in messages" :id="`entry-${message.id}`" :key="message.id" class="message-card" :class="{ highlighted: highlightedId === Number(message.id) }">
              <EntryCard :entry="message" :is-root="true" :expanded="expandedId === Number(message.id)" :reply-draft="replyDrafts[message.id] || ''" :replies="replies[message.id]?.items || []" :reply-page="replies[message.id]?.page || 1" :reply-total-pages="replies[message.id]?.totalPages || 1" :loading-replies="Boolean(replyLoading[message.id])" :posting-reply="Boolean(replyPosting[message.id])" @toggle-replies="toggleReplies(message)" @update:reply-draft="value => replyDrafts[message.id] = value" @submit-reply="submitReply(message)" @toggle-like="toggleLike" @delete="removeEntry" @request-login="requestLogin" @load-more-replies="loadReplies(message.id, (replies[message.id]?.page || 1) + 1)" />
            </article>
          </div>
        </n-spin>
        <n-pagination v-if="totalPages > 1" v-model:page="page" :page-count="totalPages" :page-size="20" show-quick-jumper @update:page="loadMessages" />
      </section>
    </div>
  </main>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NAlert, NButton, NInput, NPagination, NSpin, useMessage } from 'naive-ui'
import { createGuestbookMessage, createGuestbookReply, deleteGuestbookEntry, getGuestbookContext, getGuestbookMessages, getGuestbookReplies, likeGuestbookEntry, unlikeGuestbookEntry } from '@/api'
import { useAuthStore } from '@/stores/auth'
import EntryCard from './EntryCard.vue'

const auth = useAuthStore()
const route = useRoute()
const message = useMessage()
const messages = ref([])
const page = ref(1)
const totalPages = ref(1)
const loading = ref(false)
const posting = ref(false)
const messageDraft = ref('')
const errorMessage = ref('')
const expandedId = ref(null)
const highlightedId = ref(null)
const replies = reactive({})
const replyDrafts = reactive({})
const replyLoading = reactive({})
const replyPosting = reactive({})

onMounted(async () => {
  await loadMessages(1)
  await focusRequestedEntry()
})

watch(() => route.query.entry, () => focusRequestedEntry())

async function loadMessages(nextPage = 1) {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = (await getGuestbookMessages(nextPage)).data || {}
    messages.value = data.items || []
    page.value = Number(data.page || nextPage)
    totalPages.value = Number(data.totalPages || 1)
  } catch (error) { errorMessage.value = error.message || '留言加载失败' }
  finally { loading.value = false }
}

async function submitMessage() {
  if (!auth.isLoggedIn) return requestLogin()
  if (!messageDraft.value.trim()) return message.warning('请先写下留言内容')
  posting.value = true
  try {
    const created = (await createGuestbookMessage(messageDraft.value)).data
    messageDraft.value = ''
    if (page.value === 1) messages.value.unshift(created)
    else await loadMessages(1)
    message.success('留言已发布')
    await auth.refreshUnreadNotifications().catch(() => {})
  } catch (error) { message.error(error.message || '发布失败') }
  finally { posting.value = false }
}

async function toggleReplies(entry) {
  const id = Number(entry.id)
  if (expandedId.value === id) { expandedId.value = null; return }
  expandedId.value = id
  if (!replies[id]) await loadReplies(id, 1)
}

async function loadReplies(messageId, nextPage = 1) {
  replyLoading[messageId] = true
  try {
    const data = (await getGuestbookReplies(messageId, nextPage)).data || {}
    const existing = replies[messageId]?.items || []
    replies[messageId] = { ...data, items: nextPage === 1 ? (data.items || []) : [...existing, ...(data.items || [])] }
  } catch (error) { message.error(error.message || '回复加载失败') }
  finally { replyLoading[messageId] = false }
}

async function submitReply(parent) {
  if (!auth.isLoggedIn) return requestLogin()
  const content = replyDrafts[parent.id] || ''
  if (!content.trim()) return message.warning('请先写下回复内容')
  replyPosting[parent.id] = true
  try {
    const created = (await createGuestbookReply(parent.id, content)).data
    replyDrafts[parent.id] = ''
    const state = replies[parent.id] || { items: [], page: 1, totalPages: 1 }
    replies[parent.id] = { ...state, items: [...state.items, created] }
    parent.replyCount = Number(parent.replyCount || 0) + 1
    expandedId.value = Number(parent.id)
    message.success('回复已发布')
    await auth.refreshUnreadNotifications().catch(() => {})
  } catch (error) { message.error(error.message || '回复发布失败') }
  finally { replyPosting[parent.id] = false }
}

async function toggleLike(entry) {
  if (!auth.isLoggedIn) return requestLogin()
  try {
    const updated = (entry.likedByMe ? await unlikeGuestbookEntry(entry.id) : await likeGuestbookEntry(entry.id)).data
    Object.assign(entry, updated)
    await auth.refreshUnreadNotifications().catch(() => {})
  } catch (error) { message.error(error.message || '操作失败') }
}

async function removeEntry(entry) {
  const text = entry.parentId ? '确定删除这条回复吗？' : '确定删除这条留言及其全部回复吗？此操作不可恢复。'
  if (!window.confirm(text)) return
  try {
    await deleteGuestbookEntry(entry.id)
    if (entry.parentId) {
      const state = replies[entry.parentId]
      if (state) state.items = state.items.filter(item => Number(item.id) !== Number(entry.id))
      const parent = messages.value.find(item => Number(item.id) === Number(entry.parentId))
      if (parent) parent.replyCount = Math.max(0, Number(parent.replyCount || 0) - 1)
    } else {
      messages.value = messages.value.filter(item => Number(item.id) !== Number(entry.id))
    }
    message.success('留言已删除')
  } catch (error) { message.error(error.message || '删除失败') }
}

async function focusRequestedEntry() {
  const entryId = Number(route.query.entry)
  if (!entryId || !Number.isInteger(entryId)) return
  try {
    const context = (await getGuestbookContext(entryId)).data || {}
    const root = context.rootEntry
    if (!root) return
    const rootId = Number(context.rootId || root.id)
    const existingIndex = messages.value.findIndex(item => Number(item.id) === rootId)
    if (existingIndex === -1) messages.value = [root, ...messages.value.filter(item => Number(item.id) !== rootId)]
    if (Number(context.entry?.parentId || 0)) {
      expandedId.value = rootId
      await loadReplies(rootId, 1)
    }
    highlightedId.value = rootId
    await nextTick()
    document.getElementById(`entry-${rootId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    window.setTimeout(() => { highlightedId.value = null }, 2400)
  } catch (error) { errorMessage.value = error.message || '目标留言已不可用' }
}

function requestLogin() { auth.requestLogin(route.fullPath) }
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;
.guestbook-shell { max-width:940px; display:grid; gap:26px; }.eyebrow { margin:0 0 8px; color:var(--desk-accent); font:700 12px/1.2 $font-en; letter-spacing:.13em; }.composer,.thread-section { padding:24px; }.section-heading,.list-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }.section-heading h2,.list-heading h2 { margin:0; font:500 26px/1.2 Georgia, 'Noto Serif SC', serif; }.account-name { color:#8e2d24; font-weight:700; }.composer :deep(.n-input) { margin-top:18px; }.composer-actions { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-top:12px; }.composer-actions small { color:#80776b; }.login-callout { display:flex; align-items:center; justify-content:space-between; gap:16px; padding-top:14px; }.login-callout p { margin:0; color:#70685e; }.thread-section { display:grid; gap:18px; }.message-list { display:grid; gap:12px; }.message-card { border:1px solid #d6cdbd; background:var(--desk-surface); transition:box-shadow .25s, border-color .25s; }.message-card.highlighted { border-color:var(--desk-accent); box-shadow:0 0 0 4px rgba(184,49,38,.14); }.empty-thread { padding:50px 0; text-align:center; color:#81776a; margin:0; }.thread-section :deep(.n-pagination) { justify-content:center; }
@media (max-width:768px) { .guestbook-shell{gap:18px}.composer,.thread-section{padding:16px}.section-heading h2,.list-heading h2{font-size:23px}.composer-actions,.login-callout{align-items:stretch; flex-direction:column}.composer-actions :deep(.n-button),.login-callout :deep(.n-button){width:100%; min-height:44px;} }
</style>
