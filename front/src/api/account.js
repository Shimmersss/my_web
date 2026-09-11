import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


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
