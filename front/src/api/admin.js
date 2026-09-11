import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


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

export function getOperations(options = {}) {
  return requestWithOptions('/admin/operations', { ...options, method: 'GET', timeoutMs: 15000 });
}
export function updateAdmission(action, options = {}) {
  return requestWithOptions('/admin/operations/admission', {
    ...options, method: 'PUT', body: JSON.stringify({ action }), timeoutMs: 15000,
  });
}
