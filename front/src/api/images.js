import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


// ==================== GPT 生图 API ====================

export async function createImageGenerationTask(payload) {
  const form = new FormData();
  form.append("prompt", payload.prompt);
  form.append("mode", payload.mode);
  form.append("size", payload.size);
  form.append("quality", payload.quality);
  if (payload.parentTaskId) form.append("parentTaskId", payload.parentTaskId);
  for (const file of payload.referenceFiles || [])
    form.append("referenceFiles", file);
  // Keep a single-file client compatible with the old server contract.
  if (!payload.referenceFiles?.length && payload.referenceFile)
    form.append("referenceFile", payload.referenceFile);
  const csrf = localStorage.getItem("csrfToken");
  const response = await fetch(apiUrl("/image-generate/tasks"), {
    method: "POST",
    credentials: /^https?:\/\//i.test(apiUrl("")) ? "include" : "same-origin",
    headers: csrf ? { "X-CSRF-Token": csrf } : {},
    body: form,
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new Error(data.message || `提交失败（HTTP ${response.status}）`);
  return data;
}

export function getRecentImageGenerations(options = {}) {
  return requestWithOptions("/image-generate/recent", {
    ...options, timeoutMs: options.timeoutMs ?? 15000,
    method: "GET",
    cache: "no-store",
  });
}
export function getImageGenerationStatus(taskId, options = {}) {
  return requestWithOptions(`/image-generate/status/${encodeURIComponent(taskId)}`, {
    ...options, method: "GET", timeoutMs: options.timeoutMs ?? 15000,
  });
}
export function imageGenerationResultUrl(taskId) {
  return apiUrl(`/image-generate/result/${encodeURIComponent(taskId)}`);
}
export function imageGenerationPreviewUrl(taskId) {
  return apiUrl(`/image-generate/preview/${encodeURIComponent(taskId)}`);
}
export function imageGenerationStreamUrl(taskId) {
  return apiUrl(`/image-generate/stream/${encodeURIComponent(taskId)}`);
}
export function deleteImageGenerationTask(taskId) {
  return requestWithOptions(
    `/image-generate/tasks/${encodeURIComponent(taskId)}`,
    { method: "DELETE" },
  );
}
export function cancelImageGenerationTask(taskId) {
  return post(`/image-generate/tasks/${encodeURIComponent(taskId)}/cancel`, {});
}
export function getPresentationImageAssets() {
  return get("/image-generate/presentation-assets");
}
export function presentationImagePreviewUrl(assetId) {
  return apiUrl(
    `/image-generate/presentation-assets/${encodeURIComponent(assetId)}/preview`,
  );
}
export function presentationImageResultUrl(assetId) {
  return apiUrl(
    `/image-generate/presentation-assets/${encodeURIComponent(assetId)}/result`,
  );
}
export function deletePresentationImageAsset(assetId) {
  return requestWithOptions(
    `/image-generate/presentation-assets/${encodeURIComponent(assetId)}`,
    { method: "DELETE" },
  );
}
