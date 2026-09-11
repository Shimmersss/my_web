import { apiUrl, get, post, put, requestWithOptions } from "@/utils/request";
import { requestAndroidDownload } from "@/utils/androidBridge";


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
