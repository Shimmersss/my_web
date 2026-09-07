import assert from "node:assert/strict";
import { routeAccessDecision } from "../src/utils/routeAccess.js";
import {
  trialCodeStatus,
  trialDeleteWarning,
  trialWorkspaceState,
} from "../src/utils/matchmakingTrial.js";

assert.equal(routeAccessDecision({ visibility: "Matchmaking", trialEntry: true, isLoggedIn: false }), "allow");
assert.equal(routeAccessDecision({ visibility: "Translate", isLoggedIn: false }), "login");
assert.equal(routeAccessDecision({ visibility: "Matchmaking", isLoggedIn: true, isMatchmakingTrial: true }), "allow");
assert.equal(routeAccessDecision({ visibility: "Translate", isLoggedIn: true, isMatchmakingTrial: true }), "forbidden");
assert.equal(routeAccessDecision({ visibility: "Admin", isLoggedIn: true, isRoot: true }), "allow");
assert.equal(routeAccessDecision({ visibility: "Admin", isLoggedIn: true, isRoot: false }), "forbidden");

assert.equal(trialWorkspaceState({ status: "CLAIMED" }), "questionnaire");
assert.equal(trialWorkspaceState({ status: "RETRYABLE" }), "questionnaire");
assert.equal(trialWorkspaceState({ status: "RUNNING" }), "progress");
assert.equal(trialWorkspaceState({ status: "COMPLETED", reportId: "r1", reportAvailable: true }), "report");
assert.equal(trialWorkspaceState({ status: "COMPLETED", reportId: "" }), "unavailable");

const now = new Date("2026-08-31T00:00:00Z");
assert.equal(trialCodeStatus({ enabled: false, status: "UNUSED" }, now), "已撤销");
assert.equal(trialCodeStatus({ enabled: true, status: "UNUSED", expiresAt: "2026-08-30T00:00:00Z" }, now), "已过期");
assert.equal(trialCodeStatus({ enabled: true, status: "UNUSED" }, now), "未兑换");
assert.equal(trialCodeStatus({ enabled: true, status: "CLAIMED" }, now), "已兑换待生成");
assert.equal(trialCodeStatus({ enabled: true, status: "RUNNING" }, now), "生成中");
assert.equal(trialCodeStatus({ enabled: true, status: "RETRYABLE" }, now), "可重试");
assert.equal(trialCodeStatus({ enabled: true, status: "COMPLETED" }, now), "已完成");
assert.match(trialDeleteWarning(true), /不能重新生成/);
assert.doesNotMatch(trialDeleteWarning(false), /不能重新生成/);

console.log("matchmaking trial access checks passed");
