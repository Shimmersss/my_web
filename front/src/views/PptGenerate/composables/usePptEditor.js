import { ref, computed, onMounted, onScopeDispose } from 'vue'

/** Owns embedded-editor UI and same-origin version-created events. */
export function usePptEditor({ taskId, activeTask, rememberTaskToken, clearPreview, setActiveTask, step, openStream, loadRecent }) {
  const editorVisible = ref(false)

  const editorExpanded = ref(false)

  const editorStatus = ref('正在加载项目编辑器…')

  const editorUrl = computed(() => taskId.value ? `/pptd-editor/upstream/index.html?taskId=${encodeURIComponent(taskId.value)}` : '')

  function handleEditorMessage(event) {
    if (event.origin !== window.location.origin) return
    if (event.data?.type === 'pptd-editor-ready') {
      editorStatus.value = `项目已加载：v${event.data?.version || activeTask.value?.version || 1}。可直接在画布中编辑，保存会创建新版本。`
      return
    }
    if (event.data?.type === 'pptd-editor-close') {
      editorVisible.value = false
      editorExpanded.value = false
      return
    }
    if (event.data?.type !== 'pptd-version-created') return
    const task = event.data.task
    if (!task?.taskId) return
    rememberTaskToken(task.taskId, task.accessToken)
    editorVisible.value = false
    editorExpanded.value = false
    clearPreview()
    setActiveTask(task)
    step.value = 'running'
    openStream(task.taskId)
    loadRecent()
  }

  function toggleEditor() {
    editorVisible.value = !editorVisible.value
    if (editorVisible.value) editorStatus.value = '正在加载项目编辑器…'
    else editorExpanded.value = false
  }
  onMounted(() => window.addEventListener('message', handleEditorMessage))
  onScopeDispose(() => window.removeEventListener('message', handleEditorMessage))
  return { editorVisible, editorExpanded, editorStatus, editorUrl, toggleEditor };
}
