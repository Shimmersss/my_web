<template>
  <main class="image-studio">
    <header class="studio-heading">
      <div><p>GPT IMAGE STUDIO</p><h1>GPT 生图</h1><span>一句话生成，或沿着一组参考图继续迭代。</span></div>
      <div class="credit-pill">{{ auth.isRoot ? 'root 免费 · 全站只读审计' : `${auth.credits} 积分` }}</div>
    </header>

    <section class="studio-grid">
      <aside class="control-panel">
        <div class="mode-tabs" role="tablist">
          <button :class="{ active: form.mode === 'GENERATE' }" @click="setMode('GENERATE')">文生图</button>
          <button :class="{ active: form.mode === 'EDIT' }" @click="setMode('EDIT')">参考图编辑</button>
        </div>

        <label class="field"><span>描述你想要的画面</span>
          <n-input v-model:value="form.prompt" type="textarea" :maxlength="4000" show-count :autosize="{ minRows: 7, maxRows: 13 }" placeholder="例如：一张克制的杂志封面插画，晨雾中的未来城市，暖金色光线……" />
        </label>

        <div v-if="form.mode === 'EDIT'" class="reference-box">
          <div v-if="parentTask" class="selected-parent">
            <img :src="previewUrl(parentTask.taskId)" alt="历史参考图" />
            <div><strong>沿用历史结果</strong><span>{{ shortPrompt(parentTask.prompt) }}</span></div>
            <button @click="clearReference">移除</button>
          </div>
          <label v-else class="upload-drop">
            <input type="file" multiple accept="image/png,image/jpeg" @change="selectFiles" />
            <div v-if="localReferences.length" class="reference-previews">
              <figure v-for="(item, index) in localReferences" :key="item.url"><img :src="item.url" :alt="`参考图 ${index + 1}`" /><button type="button" @click.prevent="removeReference(index)">×</button><figcaption>参考 {{ index + 1 }}</figcaption></figure>
            </div>
            <template v-else><strong>上传 1–4 张参考图</strong><span>PNG / JPEG，每张最大 20 MB、1600 万像素；总计最多 40 MB</span></template>
          </label>
        </div>

        <div class="option-row">
          <label class="field compact"><span>画幅</span><n-select v-model:value="form.size" :options="sizeOptions" /></label>
          <label class="field compact"><span>质量</span><n-select v-model:value="form.quality" :options="qualityOptions" /></label>
        </div>
        <div class="cost-line"><span>每次生成 1 张 PNG</span><strong>{{ auth.isRoot ? '免费' : `${costs[form.quality] ?? fallbackCosts[form.quality]} 积分` }}</strong></div>
        <n-button type="primary" size="large" block :loading="submitting" :disabled="!canSubmit" @click="submit">开始生成</n-button>
        <p v-if="error" class="safe-error">{{ error }}</p>
      </aside>

      <section class="result-panel">
        <div v-if="current?.status === 'completed'" class="result-ready">
          <img :src="resultUrl(current.taskId)" :alt="current.prompt" />
          <div class="result-actions">
            <n-button @click="download(current)">下载 PNG</n-button>
            <n-button v-if="isOwnTask(current)" @click="regenerate(current)">重新生成</n-button>
            <n-button v-if="isOwnTask(current)" type="primary" @click="continueEdit(current)">作为参考继续编辑</n-button>
          </div>
        </div>
        <div v-else-if="current && ['queued', 'generating'].includes(current.status)" class="result-empty busy">
          <div class="orbit"><i></i></div>
          <h2>{{ current.status === 'queued' ? '正在排队' : '正在生成' }}</h2>
          <p>{{ current.status === 'queued' && current.queuePosition ? `前方还有 ${Math.max(0, current.queuePosition - 1)} 个任务` : '通常需要几十秒，请保持页面开启' }}</p>
          <n-button v-if="isOwnTask(current)" type="error" secondary @click="cancelCurrent">取消本次任务</n-button>
        </div>
        <div v-else class="result-empty">
          <div class="canvas-mark">✦</div><h2>画布等待灵感</h2><p>生成结果会显示在这里，并自动进入你的历史记录。</p>
        </div>
      </section>
    </section>

    <section class="history-section">
      <div class="history-heading"><div><p>ITERATION HISTORY</p><h2>{{ auth.isRoot ? '全站最近创作' : '我的最近创作' }}</h2></div><n-button quaternary :loading="loadingHistory" @click="loadHistory">刷新</n-button></div>
      <div v-if="history.length" class="history-grid">
        <article v-for="task in history" :key="task.taskId" class="history-card" :class="{ selected: current?.taskId === task.taskId }" @click="selectTask(task)">
          <div class="thumb"><img v-if="task.status === 'completed'" :src="previewUrl(task.taskId)" :alt="task.prompt" /><span v-else>{{ statusText(task.status) }}</span></div>
          <div class="card-copy"><small>{{ task.mode === 'EDIT' ? `编辑${task.parentTaskId ? ' · 继承 ' + task.parentTaskId.slice(0, 6) : ''}` : '文生图' }} · {{ task.quality }}<template v-if="auth.isRoot && !isOwnTask(task)"> · 用户 #{{ task.userId }}</template></small><p>{{ shortPrompt(task.prompt) }}</p></div>
          <button v-if="isOwnTask(task) && ['completed', 'failed'].includes(task.status)" class="delete-button" title="删除" @click.stop="remove(task)">×</button>
        </article>
      </div>
      <p v-else class="empty-history">还没有历史作品，从第一张开始吧。</p>
    </section>

    <section class="history-section presentation-gallery">
      <div class="history-heading"><div><p>PRESENTATION VISUALS</p><h2>PPT / HTML 演示生图</h2><small>每账号保留 {{ presentationRetention.maxPerUser || 20 }} 张；{{ auth.isRoot ? 'root 可审计全站素材' : '仅显示本人素材' }}</small></div><n-button quaternary :loading="loadingPresentationAssets" @click="loadPresentationAssets">刷新</n-button></div>
      <div v-if="presentationAssets.length" class="history-grid">
        <article v-for="asset in presentationAssets" :key="asset.assetId" class="history-card">
          <div class="thumb"><img :src="presentationPreviewUrl(asset.assetId)" :alt="asset.slideTitle || '演示生图'" /></div>
          <div class="card-copy"><small>{{ asset.outputFormat?.toUpperCase() }} · 第 {{ asset.slideIndex }} 页<template v-if="auth.isRoot && Number(asset.userId) !== Number(auth.user?.id)"> · 用户 #{{ asset.userId }}</template></small><p>{{ asset.slideTitle || '演示视觉素材' }}</p><a :href="presentationResultUrl(asset.assetId)" download>下载 PNG</a></div>
          <button v-if="Number(asset.userId) === Number(auth.user?.id)" class="delete-button" title="删除" @click="removePresentationAsset(asset)">×</button>
        </article>
      </div>
      <p v-else class="empty-history">尚无已交付演示的 GPT 图片。</p>
    </section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { NButton, NInput, NSelect, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import {
  createImageGenerationTask, deleteImageGenerationTask, cancelImageGenerationTask, getRecentImageGenerations,
  imageGenerationPreviewUrl, imageGenerationResultUrl, imageGenerationStreamUrl,
  deletePresentationImageAsset, getPresentationImageAssets, presentationImagePreviewUrl, presentationImageResultUrl
} from '@/api'

const auth = useAuthStore()
const message = useMessage()
const form = reactive({ prompt: '', mode: 'GENERATE', size: '1024x1024', quality: 'medium', referenceFiles: [], parentTaskId: '' })
const current = ref(null)
const history = ref([])
const costs = ref({})
const submitting = ref(false)
const loadingHistory = ref(false)
const error = ref('')
const presentationAssets = ref([])
const presentationRetention = ref({ maxPerUser: 20, maxTotal: 100 })
const loadingPresentationAssets = ref(false)
const localReferences = ref([])
let eventSource = null

const fallbackCosts = { low: 2, medium: 4, high: 8 }
const sizeOptions = [
  { label: '方形 · 1024×1024', value: '1024x1024' },
  { label: '横版 · 1536×1024', value: '1536x1024' },
  { label: '竖版 · 1024×1536', value: '1024x1536' }
]
const qualityOptions = computed(() => ['low', 'medium', 'high'].map(value => ({ label: `${value} · ${auth.isRoot ? '免费' : (costs.value[value] ?? fallbackCosts[value]) + ' 积分'}`, value })))
const parentTask = computed(() => history.value.find(task => task.taskId === form.parentTaskId))
const canSubmit = computed(() => form.prompt.trim() && (form.mode === 'GENERATE' || form.referenceFiles.length || form.parentTaskId))

onMounted(async () => { await auth.refresh().catch(() => {}); await Promise.all([loadHistory(), loadPresentationAssets()]) })
onBeforeUnmount(() => { closeStream(); revokeLocalPreview() })

function setMode(mode) { form.mode = mode; clearReference(); error.value = '' }
function selectFiles(event) {
  const files = Array.from(event.target.files || [])
  const combined = [...form.referenceFiles, ...files].slice(0, 4)
  if (files.length + form.referenceFiles.length > 4) message.warning('一次最多上传 4 张参考图')
  const total = combined.reduce((sum, file) => sum + file.size, 0)
  if (total > 40 * 1024 * 1024) { message.error('参考图总大小不能超过 40 MB'); event.target.value = ''; return }
  revokeLocalPreview(); form.referenceFiles = combined; form.parentTaskId = ''
  localReferences.value = combined.map(file => ({ file, url: URL.createObjectURL(file) }))
  event.target.value = ''
}
function removeReference(index) { const files = form.referenceFiles.filter((_, itemIndex) => itemIndex !== index); revokeLocalPreview(); form.referenceFiles = files; localReferences.value = files.map(file => ({ file, url: URL.createObjectURL(file) })) }
function clearReference() { form.referenceFiles = []; form.parentTaskId = ''; revokeLocalPreview() }
function revokeLocalPreview() { localReferences.value.forEach(item => URL.revokeObjectURL(item.url)); localReferences.value = [] }

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true; error.value = ''
  try {
    const response = await createImageGenerationTask(form)
    current.value = response.data.task
    auth.updateCredits(response.data.credits)
    history.value = [current.value, ...history.value.filter(item => item.taskId !== current.value.taskId)]
    watchTask(current.value.taskId)
  } catch (e) { error.value = e.message || '提交失败，请稍后重试' }
  finally { submitting.value = false }
}

function watchTask(taskId) {
  closeStream(); eventSource = new EventSource(imageGenerationStreamUrl(taskId), { withCredentials: true })
  eventSource.addEventListener('status', event => {
    try {
      const task = JSON.parse(event.data); current.value = task
      const index = history.value.findIndex(item => item.taskId === task.taskId)
      if (index >= 0) history.value[index] = task; else history.value.unshift(task)
      if (['completed', 'failed'].includes(task.status)) {
        closeStream(); loadHistory()
        if (task.status === 'failed') error.value = task.error || '生图失败，请稍后重试'
      }
    } catch {}
  })
  eventSource.onerror = () => { closeStream(); loadHistory() }
}
function closeStream() { if (eventSource) eventSource.close(); eventSource = null }

async function loadHistory() {
  loadingHistory.value = true
  try {
    const response = await getRecentImageGenerations()
    history.value = response.data?.tasks || []
    costs.value = response.data?.costs || {}
    auth.updateCredits(response.data?.credits)
    const active = history.value.find(task => ['queued', 'generating'].includes(task.status))
    if (active && !eventSource) { current.value = active; watchTask(active.taskId) }
  } catch (e) { if (auth.isLoggedIn) error.value = e.message || '历史记录加载失败' }
  finally { loadingHistory.value = false }
}
async function loadPresentationAssets() {
  loadingPresentationAssets.value = true
  try { const response = await getPresentationImageAssets(); presentationAssets.value = response.data?.assets || []; presentationRetention.value = response.data?.retention || presentationRetention.value }
  catch (e) { if (auth.isLoggedIn) error.value = e.message || '演示生图素材加载失败' }
  finally { loadingPresentationAssets.value = false }
}
function presentationPreviewUrl(assetId) { return `${presentationImagePreviewUrl(assetId)}?v=${Date.now()}` }
function presentationResultUrl(assetId) { return presentationImageResultUrl(assetId) }
async function removePresentationAsset(asset) { if (!window.confirm('删除此演示生图素材？已交付演示不会受影响。')) return; try { await deletePresentationImageAsset(asset.assetId); presentationAssets.value = presentationAssets.value.filter(item => item.assetId !== asset.assetId) } catch (e) { message.error(e.message || '删除失败') } }
function selectTask(task) { current.value = task; error.value = task.status === 'failed' ? task.error : '' }
function isOwnTask(task) { return Number(task?.userId) === Number(auth.user?.id) }
function continueEdit(task) { form.mode = 'EDIT'; form.parentTaskId = task.taskId; form.referenceFiles = []; revokeLocalPreview(); form.prompt = ''; window.scrollTo({ top: 0, behavior: 'smooth' }) }
function regenerate(task) { form.mode = task.mode; form.prompt = task.prompt; form.size = task.size; form.quality = task.quality; form.referenceFiles = []; revokeLocalPreview(); form.parentTaskId = task.mode === 'EDIT' ? (task.parentTaskId || task.taskId) : ''; window.scrollTo({ top: 0, behavior: 'smooth' }) }
function download(task) { const anchor = document.createElement('a'); anchor.href = resultUrl(task.taskId); anchor.download = `gpt-image-${task.taskId}.png`; anchor.click() }
async function remove(task) { if (!window.confirm('删除这条创作记录和图片？')) return; try { await deleteImageGenerationTask(task.taskId); history.value = history.value.filter(item => item.taskId !== task.taskId); if (current.value?.taskId === task.taskId) current.value = null } catch (e) { message.error(e.message || '删除失败') } }
async function cancelCurrent() { if (!current.value || !window.confirm('取消本次生图？未完成任务的额度将退回。')) return; try { const res = await cancelImageGenerationTask(current.value.taskId); if (res.code !== 200) throw new Error(res.message || '取消失败'); if (typeof res.data?.credits !== 'undefined') auth.updateCredits(res.data.credits); current.value = { ...current.value, status: 'cancelled' }; eventSource?.close(); eventSource = null; message.success('生图任务已取消'); loadHistory() } catch (e) { message.error(e.message || '取消失败') } }
function resultUrl(taskId) { return `${imageGenerationResultUrl(taskId)}?v=${encodeURIComponent(history.value.find(t => t.taskId === taskId)?.updatedAt || '')}` }
function previewUrl(taskId) { return `${imageGenerationPreviewUrl(taskId)}?v=${encodeURIComponent(history.value.find(t => t.taskId === taskId)?.updatedAt || '')}` }
function shortPrompt(prompt) { return prompt?.length > 72 ? `${prompt.slice(0, 72)}…` : prompt }
function statusText(status) { return ({ queued: '排队中', generating: '生成中', failed: '未完成' })[status] || status }
</script>

<style scoped lang="scss">
.image-studio { min-height: calc(100vh - 72px); padding: 48px clamp(20px, 4vw, 72px) 80px; color: #25251f; background: radial-gradient(circle at 78% 3%, rgba(204,111,77,.16), transparent 28%), #eee9df; }
.studio-heading, .history-heading { max-width: 1440px; margin: 0 auto 24px; display:flex; align-items:flex-end; justify-content:space-between; gap:20px; }
.studio-heading p,.history-heading p { margin:0 0 8px; font-size:11px; letter-spacing:.18em; color:#9f4935; font-weight:800; }
.studio-heading h1 { margin:0; font-family:Georgia,serif; font-size:clamp(36px,5vw,64px); font-weight:500; line-height:1; }
.studio-heading span { display:block; margin-top:12px; color:#706b61; }
.credit-pill { padding:10px 16px; border:1px solid #d4c9b7; border-radius:999px; background:rgba(255,255,255,.45); font-weight:700; white-space:nowrap; }
.studio-grid { max-width:1440px; margin:auto; display:grid; grid-template-columns:minmax(300px,420px) minmax(0,1fr); gap:20px; }
.control-panel,.result-panel { border:1px solid #d8cebd; background:rgba(249,246,239,.88); box-shadow:0 18px 50px rgba(63,50,31,.08); }
.control-panel { padding:24px; }
.mode-tabs { display:grid; grid-template-columns:1fr 1fr; gap:4px; padding:4px; background:#e7dfd2; margin-bottom:22px; }
.mode-tabs button { min-height:40px; border:0; background:transparent; color:#746d61; cursor:pointer; font-weight:700; }
.mode-tabs button.active { color:#25251f; background:#fffdf7; box-shadow:0 3px 12px rgba(56,43,25,.08); }
.field { display:grid; gap:9px; margin-bottom:18px; }.field>span { font-size:13px; font-weight:700; }.option-row { display:grid; grid-template-columns:1fr 1fr; gap:12px; }.compact { min-width:0; }
.reference-box { margin:-4px 0 18px; }.upload-drop { min-height:150px; border:1px dashed #baaa92; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:6px; overflow:hidden; cursor:pointer; color:#6e675b; }.upload-drop input { position:absolute; opacity:0; pointer-events:none; }.upload-drop img { width:100%; height:190px; object-fit:contain; background:#ded7cc; }.upload-drop span { font-size:12px; }.reference-previews{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;width:100%;padding:8px;box-sizing:border-box}.reference-previews figure{position:relative;margin:0;min-width:0}.reference-previews img{width:100%;height:92px;object-fit:cover;background:#ded7cc}.reference-previews button{position:absolute;right:2px;top:2px;border:0;border-radius:50%;background:#25251f;color:#fff;width:22px;height:22px;cursor:pointer}.reference-previews figcaption{font-size:11px;text-align:center;padding-top:3px}
.selected-parent { display:grid; grid-template-columns:78px 1fr auto; align-items:center; gap:12px; padding:10px; border:1px solid #d3c7b5; }.selected-parent img { width:78px; height:70px; object-fit:cover; }.selected-parent div { min-width:0; display:grid; gap:5px; }.selected-parent span { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-size:12px; color:#756e62; }.selected-parent button { border:0; background:none; color:#a54a38; cursor:pointer; }
.cost-line { display:flex; justify-content:space-between; margin:-2px 0 18px; color:#776f63; font-size:12px; }.cost-line strong { color:#9f4935; }.safe-error { padding:10px 12px; margin:14px 0 0; color:#9a382b; background:#f3dfd8; font-size:13px; }
.result-panel { min-height:620px; display:grid; place-items:center; overflow:hidden; background:linear-gradient(135deg,rgba(255,255,255,.35),rgba(229,218,201,.62)); }.result-ready { width:100%; height:100%; min-height:620px; display:flex; flex-direction:column; }.result-ready>img { width:100%; height:560px; object-fit:contain; padding:20px; box-sizing:border-box; }.result-actions { display:flex; justify-content:center; flex-wrap:wrap; gap:10px; padding:16px; border-top:1px solid #d8cebd; }
.result-empty { text-align:center; color:#756e62; padding:30px; }.result-empty h2 { color:#353129; font-family:Georgia,serif; font-weight:500; }.canvas-mark { width:86px; height:86px; display:grid; place-items:center; margin:auto; border:1px solid #cfc2ae; border-radius:50%; font-size:32px; color:#b45740; }.orbit { width:78px; height:78px; margin:auto; border:1px solid #c9baa4; border-radius:50%; position:relative; animation:spin 2s linear infinite; }.orbit i { width:12px;height:12px;background:#b45740;border-radius:50%;position:absolute;top:-6px;left:33px; }@keyframes spin{to{transform:rotate(360deg)}}
.history-section { max-width:1440px; margin:54px auto 0; }.history-heading { margin-bottom:18px; }.history-heading h2 { margin:0; font-family:Georgia,serif; font-size:30px; font-weight:500; }.history-grid { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:14px; }.history-card { min-width:0; position:relative; cursor:pointer; border:1px solid #d7ccba; background:#f8f4ec; transition:.2s; }.history-card:hover,.history-card.selected { transform:translateY(-3px); border-color:#ad5a43; box-shadow:0 12px 28px rgba(65,48,28,.1); }.thumb { aspect-ratio:1; display:grid; place-items:center; background:#ddd5c8; color:#766e62; }.thumb img { width:100%;height:100%;object-fit:cover; }.card-copy { padding:12px; }.card-copy small { color:#a04b37; }.card-copy p { margin:7px 0 0; font-size:13px; line-height:1.45; min-height:38px; }.delete-button { position:absolute; top:6px; right:6px; width:28px;height:28px;border:0;border-radius:50%;background:rgba(37,37,31,.75);color:#fff;font-size:20px;cursor:pointer; }.empty-history { color:#776f63; padding:30px 0; border-top:1px solid #d7ccba; }
@media (max-width:1000px){.studio-grid{grid-template-columns:1fr}.result-panel,.result-ready{min-height:520px}.result-ready>img{height:460px}.history-grid{grid-template-columns:repeat(3,1fr)}}
@media (max-width:600px){.image-studio{padding:28px 14px 60px}.studio-heading{align-items:flex-start;flex-direction:column}.studio-heading h1{font-size:42px}.control-panel{padding:16px}.option-row{grid-template-columns:1fr}.result-panel,.result-ready{min-height:390px}.result-ready>img{height:330px;padding:10px}.history-grid{grid-template-columns:repeat(2,1fr)}.history-heading{align-items:center}.card-copy p{font-size:12px}}
</style>
