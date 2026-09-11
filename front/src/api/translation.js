import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


// ==================== PDF / 图片翻译 API ====================

/**
 * 上传 PDF 文件（获取页数信息，不立即翻译）
 * @param {File} file - PDF 文件
 * @returns {Promise}
 */
export async function uploadTranslationFile(file) {
  const formData = new FormData();
  formData.append("file", file);
  const csrfToken = localStorage.getItem("csrfToken");
  const res = await fetch("/api/translate/upload", {
    method: "POST",
    credentials: "same-origin",
    headers: csrfToken ? { "X-CSRF-Token": csrfToken } : {},
    body: formData,
  });
  return res.json();
}

// 兼容旧调用方；新页面使用 uploadTranslationFile 支持 PDF 和常见图片。
export const uploadPdf = uploadTranslationFile;

/**
 * 开始翻译（指定页面范围）
 * @param {string} taskId
 * @param {number} startPage - 起始页码（从 1 开始）
 * @param {number} endPage - 结束页码
 * @returns {Promise}
 */
export async function startTranslation(
  taskId,
  startPage,
  endPage,
  fontFamily = "auto",
  qps = 4,
) {
  const csrfToken = localStorage.getItem("csrfToken");
  const res = await fetch(
    `/api/translate/start/${taskId}?startPage=${startPage}&endPage=${endPage}&fontFamily=${encodeURIComponent(fontFamily)}&qps=${qps}`,
    {
      method: "POST",
      credentials: "same-origin",
      headers: csrfToken ? { "X-CSRF-Token": csrfToken } : {},
    },
  );
  return res.json();
}
export function cancelTranslation(taskId) {
  return post(`/translate/cancel/${encodeURIComponent(taskId)}`, {});
}

/**
 * 获取翻译任务状态（断线重连用）
 * @param {string} taskId
 * @returns {Promise}
 */
export function getTranslationStatus(taskId, options = {}) {
  return requestWithOptions(`/translate/status/${encodeURIComponent(taskId)}`, {
    ...options, method: "GET", timeoutMs: options.timeoutMs ?? 15000,
  });
}

/**
 * 获取最近翻译任务
 * @returns {Promise}
 */
export function getRecentTranslations(options = {}) {
  return requestWithOptions("/translate/recent", {
    ...options, timeoutMs: options.timeoutMs ?? 15000,
    method: "GET",
    cache: "no-store",
  });
}

/**
 * 下载翻译结果
 * @param {string} taskId
 */
export function downloadTranslation(taskId) {
  if (
    requestAndroidDownload({
      url: `/api/translate/download/${encodeURIComponent(taskId)}`,
      filename: `翻译结果-${taskId}.txt`,
      mimeType: "text/plain",
    })
  )
    return;
  window.open(`/api/translate/download/${taskId}`);
}

/**
 * 获取翻译后的 PDF Blob（用于页面内预览）
 * @param {string} taskId
 * @returns {Promise<Blob>}
 */
export async function getTranslatedPdfBlob(taskId, mode = "translated", options = {}) {
  const res = await fetch(
    `/api/translate/download-pdf/${taskId}?mode=${encodeURIComponent(mode)}`,
    {
      credentials: "same-origin",
      signal: options.signal,
    },
  );
  if (!res.ok) {
    let message = "生成翻译 PDF 失败";
    try {
      const data = await res.json();
      message = data.message || message;
    } catch {}
    throw new Error(message);
  }
  return res.blob();
}

/**
 * 获取图片翻译后的 PNG Blob（用于页面内预览）
 */
export async function getTranslatedImageBlob(taskId, mode = "translated", options = {}) {
  const res = await fetch(
    `/api/translate/download-image/${taskId}?mode=${encodeURIComponent(mode)}`,
    {
      credentials: "same-origin",
      signal: options.signal,
    },
  );
  if (!res.ok) {
    let message = "生成翻译图片失败";
    try {
      const data = await res.json();
      message = data.message || message;
    } catch {}
    throw new Error(message);
  }
  return res.blob();
}

export function downloadTranslatedImage(taskId, mode = "translated") {
  if (
    requestAndroidDownload({
      url: `/api/translate/download-image/${encodeURIComponent(taskId)}?mode=${encodeURIComponent(mode)}`,
      filename: `翻译图片-${taskId}-${mode}.png`,
      mimeType: "image/png",
    })
  )
    return;
  window.open(
    `/api/translate/download-image/${taskId}?mode=${encodeURIComponent(mode)}`,
  );
}

/**
 * 下载翻译后的 PDF（译文填回原位置）
 * @param {string} taskId
 */
export function downloadTranslatedPdf(taskId, mode = "translated") {
  if (
    requestAndroidDownload({
      url: `/api/translate/download-pdf/${encodeURIComponent(taskId)}?mode=${encodeURIComponent(mode)}`,
      filename: `翻译PDF-${taskId}-${mode}.pdf`,
      mimeType: "application/pdf",
    })
  )
    return;
  window.open(
    `/api/translate/download-pdf/${taskId}?mode=${encodeURIComponent(mode)}`,
  );
}
