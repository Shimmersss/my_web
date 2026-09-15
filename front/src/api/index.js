import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";

// ==================== Zotero 文献 API ====================

/**
 * 获取 Zotero 文献列表（已精简字段）
 * @param {boolean} refresh - 是否异步触发一次完整同步
 */
export function getZoteroItems(refresh = false) {
  return get("/zotero/items", refresh ? { refresh: true } : {});
}

/**
 * 获取 Zotero 文献集合（folder）
 */
export function getZoteroCollections(refresh = false) {
  return get("/zotero/collections", refresh ? { refresh: true } : {});
}
export function submitZoteroAttachmentForTranslation(
  attachmentKey,
  fileName = "",
) {
  return post(
    `/translate/from-zotero/${encodeURIComponent(attachmentKey)}${fileName ? `?fileName=${encodeURIComponent(fileName)}` : ""}`,
    {},
  );
}

// ==================== GitHub 开源项目 API ====================

export function getGithubProjects() {
  return get("/github-projects");
}

export function getGithubRankings() {
  return get("/github-projects/rankings");
}
export function getHomeDailyStatus() {
  return get("/home/daily-status");
}

export function loginGithubProjectsAdmin(key) {
  return post("/github-projects/login", { key });
}

export function saveGithubProjects(projects, adminKey) {
  return requestWithOptions("/github-projects", {
    method: "PUT",
    headers: {
      "X-Admin-Key": adminKey,
    },
    body: JSON.stringify(projects),
  });
}

// ==================== 账号与额度 API ====================

export function getCurrentUser() {
  return get("/auth/me");
}

export function loginAccount(username, password) {
  return post("/auth/login", { username, password });
}

export function registerAccount(username, password, inviteCode) {
  return post("/auth/register", { username, password, inviteCode });
}

export function logoutAccount() {
  return post("/auth/logout", {});
}

export function changeOwnPassword(currentPassword, newPassword) {
  return put("/auth/password", { currentPassword, newPassword });
}

export function claimDailyCheckin() {
  return post("/auth/daily-checkin", {});
}

export function getDailyCheckinLeaderboard() {
  return get("/auth/daily-checkin/leaderboard");
}

export function getQuotaSettings() {
  return get("/auth/quota-settings");
}

export function getSiteSettings() {
  return get("/auth/site-settings");
}

export function getLatestAndroidApp() {
  return get("/app-update/latest");
}

// ==================== 婚恋条件账本 API ====================
export function getMatchmakingCatalogue() {
  return get("/matchmaking/catalogue");
}
export function getMatchmakingStatus() {
  return get("/matchmaking/status");
}
export function getLatestMatchmakingReport() {
  return get("/matchmaking/reports/latest");
}
export function createMatchmakingReport(profile) {
  return post("/matchmaking/reports", profile);
}
export function getMatchmakingTasks() {
  return get("/matchmaking/tasks");
}
export function getMatchmakingTask(taskId, options = {}) {
  return requestWithOptions(`/matchmaking/tasks/${encodeURIComponent(taskId)}`, {
    ...options, method: 'GET', timeoutMs: 15000,
  });
}
export function getMatchmakingReports() {
  return get("/matchmaking/reports");
}
export function getMatchmakingReportArchive(page = 1, pageSize = 10, owner = "") {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (owner) params.set("owner", owner);
  return get(`/matchmaking/reports/archive?${params.toString()}`);
}
export function getMatchmakingReport(reportId) {
  return get(`/matchmaking/reports/${reportId}`);
}
export function deleteMatchmakingReport(reportId) {
  return requestWithOptions(`/matchmaking/reports/${reportId}`, { method: "DELETE" });
}
export function deleteMatchmakingData() {
  return requestWithOptions("/matchmaking/data", { method: "DELETE" });
}
export function redeemMatchmakingTrial(code) {
  return post("/matchmaking/trial/redeem", { code });
}
export function drawMatchmakingTarot() {
  return post("/matchmaking/tarot-draws", {});
}
export function getCurrentMatchmakingTarot() {
  return get("/matchmaking/tarot-draws/current");
}

// ==================== 留言板与站内通知 API ====================

export function getGuestbookMessages(page = 1) {
  return get("/guestbook/messages", { page });
}

export function getGuestbookReplies(messageId, page = 1) {
  return get(`/guestbook/messages/${encodeURIComponent(messageId)}/replies`, {
    page,
  });
}

export function getGuestbookContext(entryId) {
  return get(`/guestbook/entries/${encodeURIComponent(entryId)}/context`);
}

export function createGuestbookMessage(content) {
  return post("/guestbook/messages", { content });
}

export function createGuestbookReply(messageId, content) {
  return post(`/guestbook/messages/${encodeURIComponent(messageId)}/replies`, {
    content,
  });
}

export function likeGuestbookEntry(entryId) {
  return put(`/guestbook/entries/${encodeURIComponent(entryId)}/like`, {});
}

export function unlikeGuestbookEntry(entryId) {
  return requestWithOptions(
    `/guestbook/entries/${encodeURIComponent(entryId)}/like`,
    { method: "DELETE" },
  );
}

export function deleteGuestbookEntry(entryId) {
  return requestWithOptions(
    `/guestbook/entries/${encodeURIComponent(entryId)}`,
    { method: "DELETE" },
  );
}

export function getNotifications(page = 1) {
  return get("/notifications", { page });
}

export function getUnreadNotificationCount() {
  return get("/notifications/unread-count");
}

export function markNotificationRead(notificationId) {
  return requestWithOptions(
    `/notifications/${encodeURIComponent(notificationId)}/read`,
    { method: "PATCH" },
  );
}

export function markAllNotificationsRead() {
  return post("/notifications/read-all", {});
}

export function getAdminGuestbookEntries(type = "all", page = 1) {
  return get("/admin/guestbook/entries", { type, page });
}

export function deleteAdminGuestbookEntry(entryId) {
  return requestWithOptions(
    `/admin/guestbook/entries/${encodeURIComponent(entryId)}`,
    { method: "DELETE" },
  );
}

export function getAdminAccounts() {
  return get("/admin/accounts");
}

export function createInviteCode({ code, credits, maxUses, expiresAt = "" }) {
  return post("/admin/accounts/invites", { code, credits, maxUses, expiresAt });
}

export function createMatchmakingTrialCode(expiresAt = "") {
  return post("/admin/accounts/matchmaking-trial-codes", { expiresAt });
}

export function updateMatchmakingTrialCode(id, enabled, expiresAt = "") {
  return requestWithOptions(`/admin/accounts/matchmaking-trial-codes/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ enabled, expiresAt }),
  });
}

export function adjustUserCredits({ userId, amount, note }) {
  return post("/admin/accounts/credits", { userId, amount, note });
}

export function updateQuotaSettings({
  translationCreditPerPage,
  pptCreditPerTask,
  matchmakingCreditPerReport,
  imageLowCredits,
  imageMediumCredits,
  imageHighCredits,
  dailyCheckinEnabled,
  dailyCheckinCredits,
  dailyCheckinMinCredits,
  dailyCheckinMaxCredits,
}) {
  const fallback = dailyCheckinCredits ?? 2;
  return put("/admin/accounts/settings", {
    translationCreditPerPage,
    pptCreditPerTask,
    matchmakingCreditPerReport,
    imageLowCredits,
    imageMediumCredits,
    imageHighCredits,
    dailyCheckinEnabled,
    dailyCheckinMinCredits: dailyCheckinMinCredits ?? fallback,
    dailyCheckinMaxCredits: dailyCheckinMaxCredits ?? fallback,
  });
}

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

export function getRecentImageGenerations() {
  return requestWithOptions("/image-generate/recent", {
    method: "GET",
    cache: "no-store",
  });
}
export function getImageGenerationStatus(taskId) {
  return get(`/image-generate/status/${encodeURIComponent(taskId)}`);
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

export function updateAdminApiSettings(settings) {
  return put("/admin/accounts/api-settings", settings);
}

export function testAdminApiSettings(provider, config) {
  return post("/admin/accounts/api-settings/test", { provider, config });
}

export function requestGithubRankingRefresh() {
  return post("/admin/accounts/github-ranking/refresh");
}

export function updateInviteStatus(id, enabled, expiresAt = "") {
  return requestWithOptions(`/admin/accounts/invites/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ enabled, expiresAt }),
  });
}

export function deleteInviteCode(id) {
  return requestWithOptions(`/admin/accounts/invites/${id}`, {
    method: "DELETE",
  });
}

export function updateAdminUserStatus(id, enabled) {
  return requestWithOptions(`/admin/accounts/users/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ enabled }),
  });
}

export function resetAdminUserPassword(id, password) {
  return put(`/admin/accounts/users/${id}/password`, { password });
}

export async function getGithubProjectReadme(fullName) {
  const res = await fetch(`/api/github-projects/${fullName}/readme`);
  if (!res.ok) throw new Error(await res.text());
  return res.text();
}

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
export function getTranslationStatus(taskId) {
  return get(`/translate/status/${taskId}`);
}

/**
 * 获取最近翻译任务
 * @returns {Promise}
 */
export function getRecentTranslations() {
  return requestWithOptions("/translate/recent", {
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
export async function getTranslatedPdfBlob(taskId, mode = "translated") {
  const res = await fetch(
    `/api/translate/download-pdf/${taskId}?mode=${encodeURIComponent(mode)}`,
    {
      credentials: "same-origin",
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
export async function getTranslatedImageBlob(taskId, mode = "translated") {
  const res = await fetch(
    `/api/translate/download-image/${taskId}?mode=${encodeURIComponent(mode)}`,
    {
      credentials: "same-origin",
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

export function getPptGenerationStatus(taskId, accessToken) {
  return requestWithOptions(`/ppt-generate/status/${taskId}`, {
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
