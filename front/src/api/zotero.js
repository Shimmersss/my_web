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
