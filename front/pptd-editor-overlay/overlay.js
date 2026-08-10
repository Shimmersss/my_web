const params = new URLSearchParams(location.search)
const taskId = params.get('taskId') || ''
const state = { version: 0, files: {}, original: {}, selected: '' }
const filesEl = document.querySelector('#files')
const editor = document.querySelector('#editor')
const status = document.querySelector('#status')
const meta = document.querySelector('#meta')

function setStatus(text, kind = '') { status.textContent = text; status.className = kind }
function selectFile(name) {
  if (state.selected) state.files[state.selected] = editor.value
  state.selected = name; editor.value = state.files[name] || ''
  for (const button of filesEl.querySelectorAll('button')) button.classList.toggle('active', button.dataset.path === name)
}
function renderFiles() {
  filesEl.replaceChildren(...Object.keys(state.files).sort().map(name => {
    const button = document.createElement('button'); button.textContent = name; button.dataset.path = name
    button.onclick = () => selectFile(name); return button
  }))
  selectFile(Object.keys(state.files)[0] || '')
}
async function load() {
  if (!/^[a-zA-Z0-9-]{4,64}$/.test(taskId)) throw new Error('缺少有效 taskId')
  const response = await fetch(`/api/ppt-generate/tasks/${encodeURIComponent(taskId)}/project`, { credentials: 'same-origin' })
  const result = await response.json(); if (!response.ok) throw new Error(result.message || '项目加载失败')
  const project = result.data || {}; state.version = Number(project.version || 1)
  state.files = { ...(project.files || {}) }; state.original = { ...state.files }
  meta.textContent = `任务 ${taskId} · v${state.version}`; renderFiles(); setStatus('已加载。保存会创建不可变子版本。', 'ok')
}
document.querySelector('#save').onclick = async () => {
  if (state.selected) state.files[state.selected] = editor.value
  const changes = Object.entries(state.files).filter(([name, content]) => content !== state.original[name]).map(([path, content]) => ({ path, content }))
  if (!changes.length) return setStatus('没有需要保存的更改。')
  setStatus('正在创建新版本…')
  try {
    const csrf = localStorage.getItem('csrfToken') || ''
    const response = await fetch(`/api/ppt-generate/tasks/${encodeURIComponent(taskId)}/versions`, { method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{})}, body:JSON.stringify({baseVersion:state.version,changes}) })
    const result = await response.json(); if (!response.ok) throw new Error(result.message || '保存失败')
    setStatus(`新版本 ${result.data.taskId} 已进入导出队列。`, 'ok')
    parent.postMessage({ type:'pptd-version-created', task:result.data }, location.origin)
  } catch (error) { setStatus(error.message || '保存失败', 'error') }
}
document.querySelector('#upstream').onclick = () => window.open('./upstream/', '_blank', 'noopener')
load().catch(error => { meta.textContent = '加载失败'; setStatus(error.message || '项目加载失败', 'error') })
