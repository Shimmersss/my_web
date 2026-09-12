import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


// ==================== 婚恋条件账本 API ====================
export function getMatchmakingCatalogue() {
  return get("/matchmaking/catalogue");
}
export function getMatchmakingStatus() {
  return get("/matchmaking/status");
}
export function drawMatchmakingTarot() {
  return post("/matchmaking/tarot-draws", {});
}
export function getCurrentMatchmakingTarot() {
  return get("/matchmaking/tarot-draws/current");
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
