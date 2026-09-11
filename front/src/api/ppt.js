import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


// ==================== PPT 生成 API ====================

export async function createPptGenerationTask({
  prompt,
  templateKey,
  outputFormat = "pptx",
  researchMode = "auto",
  visualMode = "best_effort",
  motionMode = "auto",
  imageGenerationMode = "off",
  pageCount = 0,
  imageGenerationCount = 0,
  fontFamily = "Microsoft YaHei",
  templateFile,
  sourceFile,
  paperFile,
  idempotencyKey,
}) {
  const formData = new FormData();
  if (prompt?.trim()) formData.append("prompt", prompt.trim());
  if (templateKey) formData.append("templateKey", templateKey);
  if (outputFormat) formData.append("outputFormat", outputFormat);
  formData.append("researchMode", researchMode === "off" ? "off" : "auto");
  formData.append(
    "visualMode",
    visualMode === "strict" ? "strict" : "best_effort",
  );
  formData.append(
    "motionMode",
    outputFormat === "html" &&
      ["subtle", "expressive", "off"].includes(motionMode)
      ? motionMode
      : "auto",
  );
  formData.append(
    "imageGenerationMode",
    ["supplement", "prefer"].includes(imageGenerationMode) &&
      outputFormat === "pptx"
      ? imageGenerationMode
      : "off",
  );
  if (
    Number.isInteger(Number(pageCount)) &&
    Number(pageCount) >= 3 &&
    Number(pageCount) <= 30
  )
    formData.append("pageCount", String(pageCount));
  if (
    outputFormat === "pptx" &&
    Number.isInteger(Number(imageGenerationCount)) &&
    Number(imageGenerationCount) >= 1 &&
    Number(imageGenerationCount) <= 10
  )
    formData.append("imageGenerationCount", String(imageGenerationCount));
  if (fontFamily) formData.append("fontFamily", fontFamily);
  if (templateFile) formData.append("templateFile", templateFile);
  if (sourceFile || paperFile)
    formData.append("sourceFile", sourceFile || paperFile);

  const csrfToken = localStorage.getItem("csrfToken");
  const res = await fetch(apiUrl("/ppt-generate/tasks"), {
    method: "POST",
    credentials: /^https?:\/\//i.test(apiUrl("")) ? "include" : "same-origin",
    headers: {
      ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
      ...(idempotencyKey ? { "X-Ppt-Idempotency-Key": idempotencyKey } : {}),
    },
    body: formData,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.message || `HTTP error! status: ${res.status}`);
  }
  return data;
}

export function getPptTemplates() {
  return get("/ppt-generate/templates");
}

export function getPptPreview(taskId, accessToken, options = {}) {
  return requestWithOptions(
    `/ppt-generate/preview/${encodeURIComponent(taskId)}`,
    {
      ...options,
      method: "GET",
      headers: pptTaskHeaders(accessToken),
      cache: "no-store",
    },
  );
}

export async function getPptHtmlPreview(taskId, accessToken, options = {}) {
  const res = await fetch(
    apiUrl(`/ppt-generate/preview-html/${encodeURIComponent(taskId)}`),
    {
      ...options,
      method: "GET",
      credentials: /^https?:\/\//i.test(apiUrl("")) ? "include" : "same-origin",
      headers: pptTaskHeaders(accessToken),
      cache: "no-store",
    },
  );
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || `HTML 预览加载失败（HTTP ${res.status}）`);
  }
  return URL.createObjectURL(await res.blob());
}

export async function getPptPreviewImage(
  taskId,
  fileName,
  accessToken,
  options = {},
) {
  const res = await fetch(
    apiUrl(
      `/ppt-generate/preview/${encodeURIComponent(taskId)}/images/${encodeURIComponent(fileName)}`,
    ),
    {
      ...options,
      method: "GET",
      credentials: /^https?:\/\//i.test(apiUrl("")) ? "include" : "same-origin",
      headers: pptTaskHeaders(accessToken),
      cache: "no-store",
    },
  );
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || `预览素材加载失败（HTTP ${res.status}）`);
  }
  return URL.createObjectURL(await res.blob());
}

function pptTaskHeaders(accessToken) {
  return accessToken ? { "X-Ppt-Task-Token": accessToken } : {};
}

export function getPptGenerationStatus(taskId, accessToken, options = {}) {
  return requestWithOptions(`/ppt-generate/status/${encodeURIComponent(taskId)}`, {
    ...options, timeoutMs: options.timeoutMs ?? 15000,
    method: "GET",
    headers: pptTaskHeaders(accessToken),
  });
}

export function cancelPptGenerationTask(taskId) {
  return post(`/ppt-generate/tasks/${encodeURIComponent(taskId)}/cancel`, {});
}

export async function revisePptGenerationTask(
  taskId,
  accessToken,
  { prompt = "", idempotencyKey } = {},
) {
  const csrfToken = localStorage.getItem("csrfToken");
  const res = await fetch(
    apiUrl(`/ppt-generate/tasks/${encodeURIComponent(taskId)}/revise`),
    {
      method: "POST",
      credentials: /^https?:\/\//i.test(apiUrl("")) ? "include" : "same-origin",
      headers: {
        "Content-Type": "application/json",
        ...(accessToken ? { "X-Ppt-Task-Token": accessToken } : {}),
        ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
        ...(idempotencyKey ? { "X-Ppt-Idempotency-Key": idempotencyKey } : {}),
      },
      body: JSON.stringify({ prompt: prompt.trim() }),
    },
  );
  const data = await res.json().catch(() => ({}));
  if (!res.ok)
    throw new Error(data.message || `二次修改提交失败（HTTP ${res.status}）`);
  return data;
}

export function getRecentPptGenerations(accessTokens = []) {
  return requestWithOptions("/ppt-generate/recent", {
    method: "GET",
    headers: accessTokens.length
      ? { "X-Ppt-Task-Tokens": accessTokens.join(",") }
      : {},
    cache: "no-store",
  });
}

export async function downloadGeneratedPpt(
  taskId,
  accessToken,
  outputFormat = "pptx",
  artifact = "pptx",
) {
  const csrfToken = localStorage.getItem("csrfToken");
  const headers = {
    ...(accessToken ? { "X-Ppt-Task-Token": accessToken } : {}),
    ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
  };
  const query = artifact === "pptd" ? "?artifact=pptd" : "";
  const fallbackFilename =
    artifact === "pptd"
      ? `PPTD项目-${taskId}.zip`
      : `AI生成PPT-${taskId}.${String(outputFormat).toLowerCase() === "html" ? "html" : "pptx"}`;
  const fallbackMimeType =
    artifact === "pptd"
      ? "application/zip"
      : String(outputFormat).toLowerCase() === "html"
        ? "text/html"
        : "application/vnd.openxmlformats-officedocument.presentationml.presentation";
  if (
    requestAndroidDownload({
      url: apiUrl(
        `/ppt-generate/download/${encodeURIComponent(taskId)}${query}`,
      ),
      filename: fallbackFilename,
      mimeType: fallbackMimeType,
    })
  )
    return;
  const res = await fetch(
    apiUrl(`/ppt-generate/download/${encodeURIComponent(taskId)}${query}`),
    {
      method: "GET",
      credentials: /^https?:\/\//i.test(apiUrl("")) ? "include" : "same-origin",
      headers,
    },
  );
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || `下载失败（HTTP ${res.status}）`);
  }
  const blob = await res.blob();
  const disposition = res.headers.get("Content-Disposition") || "";
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const filename = encoded ? decodeURIComponent(encoded) : fallbackFilename;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
