const remoteTaskId = new URL(location.href).searchParams.get('taskId') || '';
let remoteProject = null;

function postToHost(type, payload = {}) {
  if (window.parent !== window) window.parent.postMessage({ type, ...payload }, location.origin);
}

function prepareRemoteChrome() {
  if (!remoteTaskId) return;
  document.documentElement.classList.add('nd-remote-mode');
  document.title = 'PPTD 项目编辑器';
  const brand = document.querySelector('.nd-brand');
  if (brand) brand.innerHTML = '<strong>PPTD 编辑器</strong><small>项目模式</small>';
  document.querySelector('#nd-open')?.remove();
  document.querySelector('#nd-folder')?.remove();
  const actions = document.querySelector('.nd-actions');
  if (actions && !document.querySelector('#nd-close')) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'nd-btn nd-btn-quiet';
    button.id = 'nd-close';
    button.textContent = '返回预览';
    button.addEventListener('click', () => postToHost('pptd-editor-close'));
    actions.append(button);
  }
  if (!document.querySelector('#nd-remote-style')) {
    const style = document.createElement('style');
    style.id = 'nd-remote-style';
    style.textContent = `
      html.nd-remote-mode .nd-topbar { gap: 10px; padding: 0 14px; }
      html.nd-remote-mode .nd-brand { min-width: 118px; }
      html.nd-remote-mode .nd-brand small { color: #0f766e; }
      html.nd-remote-mode .nd-actions { flex: 0 0 auto; }
      html.nd-remote-mode .nd-btn-quiet { border-color: #d9e0e8; background: #fff; color: #475569; }
      html.nd-remote-mode .nd-meta { flex: 1; align-items: flex-end; min-width: 0; }
      @media (max-width: 680px) {
        html.nd-remote-mode .nd-topbar { padding: 0 8px; }
        html.nd-remote-mode .nd-brand { min-width: 92px; }
        html.nd-remote-mode .nd-brand small { display: none; }
        html.nd-remote-mode #nd-title { display: none; }
        html.nd-remote-mode .nd-meta { min-width: 0; }
        html.nd-remote-mode .nd-status { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 145px; }
      }
    `;
    document.head.append(style);
  }
}

prepareRemoteChrome();

function apiPath(path) {
  return `/api/ppt-generate/tasks/${encodeURIComponent(remoteTaskId)}${path}`;
}

function encodedProjectFile(path) {
  return path.split('/').filter(Boolean).map(encodeURIComponent).join('/');
}

async function loadRemoteProject() {
  if (!/^[a-zA-Z0-9-]{4,64}$/.test(remoteTaskId)) throw new Error('缺少有效的 PPTD 任务标识');
  setStatus('正在读取网站 PPTD 项目…');
  const response = await fetch(apiPath('/project'), { credentials: 'same-origin', cache: 'no-store' });
  const result = await response.json();
  if (!response.ok) throw new Error(result?.message || 'PPTD 项目读取失败');
  remoteProject = result.data || {};
  state.directoryHandle = null;
  state.fileIndex = new Map();
  state.memoryFiles = new Map();
  state.imageCache = new Map();
  state.imageMap = Object.create(null);
  for (const [filePath, content] of Object.entries(remoteProject.files || {})) {
    const normalized = normalizeRelativePath(filePath);
    state.memoryFiles.set(normalized, String(content ?? ''));
    state.fileIndex.set(normalized, { kind: 'file', getFile: async () => new File([state.memoryFiles.get(normalized)], basename(normalized), { type: 'text/plain' }) });
  }
  for (const media of remoteProject.media || []) {
    const normalized = normalizeRelativePath(media.path);
    state.fileIndex.set(normalized, {
      kind: 'file',
      getFile: async () => {
        const mediaResponse = await fetch(apiPath(`/project/files/${encodedProjectFile(normalized)}`), { credentials: 'same-origin', cache: 'no-store' });
        if (!mediaResponse.ok) throw new Error(`媒体读取失败：${normalized}`);
        return new File([await mediaResponse.blob()], basename(normalized), { type: media.contentType || 'application/octet-stream' });
      },
    });
  }
  const manifests = [...state.fileIndex.keys()].filter(path => path.toLowerCase().endsWith('.pptd'));
  if (manifests.length !== 1) throw new Error('网站 PPTD 项目缺少唯一清单文件');
  await loadDeckFromIndex(manifests[0], `网站任务 · v${remoteProject.version || 1} · 保存将创建新版本`, { readOnly: true, editable: true });
  postToHost('pptd-editor-ready', { version: remoteProject.version || 1 });
}

async function remoteAwareSave(payload) {
  if (!remoteTaskId) return onSave(payload);
  const changes = Array.isArray(payload?.changes || payload?.files) ? (payload.changes || payload.files) : [];
  const safeChanges = changes
    .filter(change => change?.path && /\.(pptd|page)$/i.test(String(change.path)))
    .map(change => ({ path: normalizeRelativePath(change.path), content: String(change.content ?? '') }));
  if (!safeChanges.length) return;
  setStatus('正在创建不可变版本…');
  const csrf = localStorage.getItem('csrfToken') || '';
  const response = await fetch(apiPath('/versions'), {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-CSRF-Token': csrf } : {}) },
    body: JSON.stringify({ baseVersion: Number(remoteProject?.version || 1), changes: safeChanges }),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result?.message || '版本保存失败');
  setStatus(`已创建新版本 ${result.data?.taskId || ''}，正在导出…`);
  postToHost('pptd-version-created', { task: result.data });
}
