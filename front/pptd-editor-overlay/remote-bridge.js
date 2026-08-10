const remoteTaskId = new URL(location.href).searchParams.get('taskId') || '';
let remoteProject = null;

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
  window.parent.postMessage({ type: 'pptd-version-created', task: result.data }, location.origin);
}
