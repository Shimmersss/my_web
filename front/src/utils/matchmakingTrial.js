const STATUS_LABELS = {
  UNUSED: "未兑换",
  CLAIMED: "已兑换待生成",
  RUNNING: "生成中",
  RETRYABLE: "可重试",
  COMPLETED: "已完成",
};

export function trialWorkspaceState(access = {}) {
  if (access.status === "RUNNING") return "progress";
  if (access.status === "COMPLETED") {
    return access.reportId && access.reportAvailable !== false ? "report" : "unavailable";
  }
  return access.status === "CLAIMED" || access.status === "RETRYABLE"
    ? "questionnaire"
    : "unavailable";
}

export function trialCodeStatus(code = {}, now = new Date()) {
  if (!code.enabled) return "已撤销";
  if (
    code.status === "UNUSED" &&
    code.expiresAt &&
    new Date(code.expiresAt).getTime() <= now.getTime()
  ) return "已过期";
  return STATUS_LABELS[code.status] || "未知";
}

export function trialDeleteWarning(isTrial) {
  return isTrial
    ? "确定删除这份报告吗？删除后不能重新生成，也无法再凭邀请码恢复。"
    : "确定删除这份报告吗？删除后无法恢复。";
}
