# 婚恋报告一次性内测邀请码 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 root 生成独立的一次性婚恋内测邀请码，使未注册访客免费生成一份含可选伴侣画像的报告，并能凭原码跨设备恢复查看。

**Architecture:** 新建 `matchmaking_trial_codes` 状态表和聚焦的邀请码服务；首次兑换创建内部 `MATCHMAKING_TRIAL` 用户并复用现有 HttpOnly 会话、CSRF、异步任务和报告所有权。通用 `requireUser` 明确拒绝该角色，只有婚恋控制器通过专用访问检查放行；任务状态机负责免费资格的占用、失败恢复和成功封存。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、MySQL/H2、JUnit 5、Vue 3、Pinia、Vue Router、Naive UI、Node.js 门禁脚本。

**Spec:** `docs/superpowers/specs/2026-08-31-matchmaking-trial-codes-design.md`

## Global Constraints

- 婚恋内测邀请码与账号注册邀请码完全分离，互相不可兑换。
- 每枚邀请码只允许成功生成一份完整报告，伴侣画像可选且不扣积分。
- 同步失败、队列拒绝、worker 失败和重启中断允许原访客重试；成功后永不恢复生成资格。
- 邀请码首次兑换默认 7 天有效；绑定后可跨设备恢复，root 撤销后新旧会话立即失效。
- 报告文案必须写“最长保留 30 天”，并继续服从现有每用户和全站数量上限。
- 数据库只保存邀请码 SHA-256 和后四位；完整邀请码仅在创建响应中显示一次，日志不得记录原码或问卷内容。
- `MATCHMAKING_TRIAL` 不出现在注册用户列表、用户统计、签到榜、额度管理中，也不能访问其他受保护节目。
- 保留当前工作区所有无关改动；每次 `git add` 只列本任务文件，不得使用 reset、checkout 或清理命令。

---

### Task 1: 内测邀请码数据表与核心服务

**Files:**
- Modify: `backen/src/main/resources/schema.sql`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingSchemaMigration.java`
- Create: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java`
- Create: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialServiceTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`、现有 `users` 表、`AuthException`。
- Produces: `createCode(long rootId, String expiresAt)`、`codes()`、`updateCode(long id, boolean enabled, String expiresAt)`、`redeemRecord(String rawCode)`、`accessForUser(long userId)` 以及邀请码状态常量。

- [ ] **Step 1: 写建表、随机码和默认有效期的失败测试**

在 `MatchmakingTrialServiceTest` 使用独立 H2 `schema.sql`，固定 `Clock` 后断言：

```java
@Test
void createsHashedCodeWithSevenDayDefaultAndNeverListsPlaintext() {
    Map<String, Object> created = service.createCode(rootId, "");
    String code = String.valueOf(created.get("code"));
    assertTrue(code.matches("MM-[A-Z2-9]{24}"));
    assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM matchmaking_trial_codes WHERE code_hash=?",
            Integer.class, code));
    Map<String, Object> stored = service.codes().get(0);
    assertFalse(stored.containsKey("code"));
    assertEquals(code.substring(code.length() - 4), stored.get("codeSuffix"));
    assertEquals("UNUSED", stored.get("status"));
    assertEquals(now.plus(Duration.ofDays(7)), ((Timestamp) stored.get("expiresAt")).toInstant());
}
```

测试中的 hash 查询必须用原码，确保数据库不存在明文；再查询 `code_hash` 长度为 64。

- [ ] **Step 2: 运行测试确认因表或服务不存在而失败**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialServiceTest test`

Expected: FAIL，编译器找不到 `MatchmakingTrialService` 或 H2 找不到 `matchmaking_trial_codes`。

- [ ] **Step 3: 添加幂等表结构与迁移**

在 `schema.sql` 和 `MatchmakingSchemaMigration` 创建以下等价结构：

```sql
CREATE TABLE IF NOT EXISTS matchmaking_trial_codes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    code_suffix VARCHAR(4) NOT NULL,
    guest_user_id BIGINT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'UNUSED',
    active_task_id VARCHAR(36) NULL,
    report_id VARCHAR(36) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP NOT NULL,
    redeemed_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (guest_user_id) REFERENCES users(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);
```

迁移方法命名为 `ensureTrialCodesTable()`，由 `@PostConstruct` 入口调用，使用 `CREATE TABLE IF NOT EXISTS`，不更改现有报告列迁移。

- [ ] **Step 4: 实现创建、列表和管理逻辑**

`MatchmakingTrialService` 使用构造注入的 `JdbcTemplate`、`Clock`（生产构造器回退 `Clock.systemUTC()`）和 `SecureRandom`。邀请码生成规则固定为：

```java
private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
private static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);
```

`createCode` 返回包含完整 `code` 的单次响应；`codes()` SQL 显式选择管理字段并转换成 camelCase，不选择 `code_hash`；`updateCode` 不允许把截止时间解析为无效值。所有摘要都使用 `LinkedHashMap`，避免 `Map.of` 遇到 nullable 时间字段。

- [ ] **Step 5: 增加过期、撤销和注册邀请码隔离测试并实现 hash 查找**

新增测试：错误码、过期未兑换码、`enabled=false` 都由 `redeemRecord` 返回同一条 400 文案；向 `invite_codes` 插入相同文本不应被 trial 服务识别。生产实现规范化为 `trim().toUpperCase(Locale.ROOT)` 后 SHA-256，错误统一为：

```java
throw new AuthException(400, "内测邀请码无效、已过期或已撤销");
```

- [ ] **Step 6: 运行核心服务测试并提交**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialServiceTest test`

Expected: PASS。

Commit:

```bash
git add backen/src/main/resources/schema.sql \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingSchemaMigration.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialServiceTest.java
git commit -m "feat: add matchmaking trial code storage"
```

---

### Task 2: 受限访客身份、兑换会话与权限隔离

**Files:**
- Modify: `backen/src/main/java/com/web/backen/auth/AuthUser.java`
- Modify: `backen/src/main/java/com/web/backen/auth/AuthService.java`
- Modify: `backen/src/main/java/com/web/backen/auth/AuthController.java`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java`
- Create: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialController.java`
- Create: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialAuthTest.java`

**Interfaces:**
- Consumes: Task 1 的 hash 查找和状态记录、`AuthService.AuthSession`、现有 `rd_session` Cookie。
- Produces: `AuthUser.isMatchmakingTrial()`、`AuthService.requireSessionUser(request)`、`AuthService.createInternalTrialUser(String)`、`AuthService.createSessionForUser(long)`、`MatchmakingTrialService.redeem(String)`、`requireEnabled(long)`。

- [ ] **Step 1: 写首次兑换、重复兑换和通用权限拒绝的失败测试**

测试真实数据库行为，不 mock JdbcTemplate：

```java
@Test
void firstAndRepeatedRedemptionReuseOneRestrictedUser() {
    String code = String.valueOf(trials.createCode(rootId, "").get("code"));
    MatchmakingTrialService.Redemption first = trials.redeem(code);
    MatchmakingTrialService.Redemption second = trials.redeem(code);
    assertEquals(first.session().user().id(), second.session().user().id());
    assertNotEquals(first.session().token(), second.session().token());
    assertTrue(first.session().user().isMatchmakingTrial());
    assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role='MATCHMAKING_TRIAL'", Integer.class));
    assertThrows(AuthException.class, () -> auth.requireUser(requestFor(second.session())));
    assertEquals(second.session().user().id(), auth.requireSessionUser(requestFor(second.session())).id());
}
```

另测首次兑换后把 `expires_at` 改为过去仍可恢复；把 `enabled=false` 后新兑换和已有会话的 `requireEnabled` 都失败。

- [ ] **Step 2: 运行测试确认缺少受限身份接口**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialAuthTest test`

Expected: FAIL，缺少 `Redemption`、`isMatchmakingTrial` 或 `requireSessionUser`。

- [ ] **Step 3: 扩展 AuthService 而不放宽现有控制器**

在 `AuthUser` 添加：

```java
public boolean isMatchmakingTrial() {
    return "MATCHMAKING_TRIAL".equalsIgnoreCase(role);
}
```

把当前 `requireUser` 改为先调用 `requireSessionUser`，再拒绝 trial：

```java
public AuthUser requireSessionUser(HttpServletRequest request) {
    return currentUser(request).orElseThrow(() -> new AuthException(401, "请先登录"));
}

public AuthUser requireUser(HttpServletRequest request) {
    AuthUser user = requireSessionUser(request);
    if (user.isMatchmakingTrial()) throw new AuthException(403, "该内测身份只能使用婚恋报告");
    return user;
}
```

提取登录中的会话创建逻辑为 `createSessionForUser(long userId)`；新增 `createInternalTrialUser(String username)`，生成不可见随机 BCrypt 密码，角色固定为 `MATCHMAKING_TRIAL`、credits 固定为 0。

- [ ] **Step 4: 实现事务化首次绑定和重复恢复**

`MatchmakingTrialService` 注入 `AuthService`，`redeem` 使用 `SELECT id, guest_user_id, status, enabled, expires_at, code_suffix, report_id FROM matchmaking_trial_codes WHERE code_hash=? FOR UPDATE`：未绑定时检查首次兑换截止时间、创建内部用户名 `trial_<12位随机小写>`、更新 `guest_user_id/status/redeemed_at`；已绑定时跳过截止时间检查。两条路径最后调用 `auth.createSessionForUser(userId)` 并返回：

```java
public record Redemption(AuthService.AuthSession session, Map<String, Object> access) {}
```

`access` 至少包含 `status`、`canGenerate`、`reportId`、`reportAvailable`、`codeSuffix`。

- [ ] **Step 5: 实现兑换控制器和 `/auth/me` trial 摘要**

`POST /api/matchmaking/trial/redeem` 接收 `{code}`，响应设置和登录相同的 `SET_COOKIE`，data 结构为：

```json
{
  "id": 12,
  "username": "婚恋内测",
  "role": "MATCHMAKING_TRIAL",
  "credits": 0,
  "root": false,
  "matchmakingTrial": true,
  "csrfToken": "csrf-test-token",
  "trialAccess": { "status": "CLAIMED", "canGenerate": true, "reportId": "" }
}
```

`AuthController.userData` 对 trial 不调用签到服务，固定显示名“婚恋内测”，并从 trial 服务加入 `trialAccess`；普通用户响应不变。

- [ ] **Step 6: 验证 Cookie、跨设备会话和权限测试并提交**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialServiceTest,MatchmakingTrialAuthTest test`

Expected: PASS，且两个 session token 不同、userId 相同。

Commit:

```bash
git add backen/src/main/java/com/web/backen/auth/AuthUser.java \
  backen/src/main/java/com/web/backen/auth/AuthService.java \
  backen/src/main/java/com/web/backen/auth/AuthController.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialController.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialAuthTest.java
git commit -m "feat: redeem restricted matchmaking trial sessions"
```

---

### Task 3: 免费一次性任务状态机与失败重试

**Files:**
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingTask.java`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingService.java`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingController.java`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java`
- Modify: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingTaskRetentionTest.java`
- Modify: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingProfileValidationTest.java`
- Modify: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingReportAccessTest.java`
- Create: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialTaskTest.java`

**Interfaces:**
- Consumes: `MatchmakingTrialService.reserveTask(userId, taskId)`、`markRetryable(userId, taskId)`、`markCompleted(userId, taskId, reportId)` 和 `AuthService.requireSessionUser`。
- Produces: trial-aware `MatchmakingTask.trial` snapshot 字段、零积分任务分流、现有接口对有效 trial 的受限放行。

- [ ] **Step 1: 写单次占用、并发拒绝和零积分的失败测试**

测试先创建并兑换 trial code，再提交最小有效资料。测试 helper 必须返回 `new LinkedHashMap<>(Map.of("city", "上海", "education", "本科", "industry", "软件", "incomeBand", "8千-1万", "includePartnerImage", false))`，不依赖 mock profile。断言：

```java
Map<String, Object> queued = matchmaking.createTask(trialUser, validProfile(false));
assertEquals(0, queued.get("credits"));
assertEquals("RUNNING", trials.accessForUser(trialUser.id()).get("status"));
assertEquals(0, jdbc.queryForObject(
        "SELECT COUNT(*) FROM credit_transactions WHERE user_id=?",
        Integer.class, trialUser.id()));
AuthException duplicate = assertThrows(AuthException.class,
        () -> matchmaking.createTask(trialUser, validProfile(false)));
assertEquals(409, duplicate.getStatus());
```

用可控 fake agent 分别触发成功和失败；失败后状态为 `RETRYABLE`，第二次提交成功；完成后第三次提交永久 409。

- [ ] **Step 2: 运行测试确认 trial 仍走积分或缺少状态机**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialTaskTest test`

Expected: FAIL，表现为额度不足、状态未变化或方法不存在。

- [ ] **Step 3: 实现 trial 状态原子转换**

在 `MatchmakingTrialService` 中使用条件更新：

```sql
UPDATE matchmaking_trial_codes
SET status='RUNNING', active_task_id=?, updated_at=CURRENT_TIMESTAMP
WHERE guest_user_id=? AND enabled=TRUE AND status IN ('CLAIMED','RETRYABLE')
```

更新数不是 1 时返回 409。`markRetryable` 只允许匹配当前 `active_task_id` 的 RUNNING 记录；`markCompleted` 同样匹配 user/task，并写 `report_id/completed_at`。

- [ ] **Step 4: 在 MatchmakingService 分流费用和生命周期**

生成 `taskId` 后、入队前调用 `reserveTask`。trial 分支不调用 `quota.spend`，构造 `transactionId=0`、`credits=0`、`trial=true` 的任务；正式用户路径保持原逻辑。队列拒绝和 create 同步异常调用 `markRetryable`。

worker catch：trial 调 `markRetryable`，正式用户调现有 `refundQuietly`。报告 INSERT 和 `trials.markCompleted(userId, taskId, reportId)` 必须放在同一个 `transactions.executeWithoutResult` 回调中；若完成状态更新失败，事务整体回滚，catch 再把资格恢复为 `RETRYABLE`，避免出现“报告已落库但邀请码还能生成第二份”的窗口。事务提交后才把内存任务置 done。`MatchmakingTask.snapshot/fromSnapshot` 必须持久化 `trial`，以便重启恢复时把 trial 任务恢复为 `RETRYABLE` 而不是尝试退款。

新增构造参数后，同步更新 `MatchmakingTaskRetentionTest`、`MatchmakingProfileValidationTest` 和 `MatchmakingReportAccessTest` 中所有 `MatchmakingService` 构造调用，传入真实 trial service 或不涉及 trial 的显式 `null`。

- [ ] **Step 5: 收紧控制器访问和删除行为**

`MatchmakingController.access` 使用 `auth.requireSessionUser`：

```java
if (user.isMatchmakingTrial()) {
    trials.requireEnabled(user.id());
    return user;
}
```

正式用户继续执行节目可见性判断。`DELETE /data` 对 trial 返回 403；单份报告删除保留。`status` 对 trial 返回 `creditCost=0`、`credits=0`、`matchmakingTrial=true`。

- [ ] **Step 6: 补重启恢复和队列拒绝测试**

扩展 `MatchmakingTaskRetentionTest`：trial snapshot 中断恢复后 `transactionId` 保持 0，trial code 状态为 `RETRYABLE`；另测 queue offer 失败后可再次 reserve。不能只断言 mock 调用，必须查询数据库状态。

- [ ] **Step 7: 运行婚恋后端定向测试并提交**

Run:

```bash
cd backen && mvn -q -Dtest=MatchmakingTrialTaskTest,MatchmakingTaskRetentionTest,MatchmakingReportAccessTest,MatchmakingProfileValidationTest test
```

Expected: PASS。

Commit:

```bash
git add backen/src/main/java/com/web/backen/matchmaking/MatchmakingTask.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingService.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingController.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingTaskRetentionTest.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingProfileValidationTest.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingReportAccessTest.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialTaskTest.java
git commit -m "feat: enforce one free matchmaking trial report"
```

---

### Task 4: root 管理接口、统计隔离和全局报告别名

**Files:**
- Modify: `backen/src/main/java/com/web/backen/auth/AdminAccountController.java`
- Modify: `backen/src/main/java/com/web/backen/auth/QuotaService.java`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingService.java`
- Modify: `backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java`
- Create: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialAdminTest.java`
- Modify: `backen/src/test/java/com/web/backen/matchmaking/MatchmakingReportAccessTest.java`

**Interfaces:**
- Consumes: Task 1 管理方法和 trial code 状态字段。
- Produces: dashboard `matchmakingTrialCodes`、root create/patch endpoints、普通用户统计过滤、报告 `ownerLabel`。

- [ ] **Step 1: 写后台管理和统计隔离失败测试**

插入 ROOT、USER、MATCHMAKING_TRIAL 三类用户，断言：

```java
assertEquals(List.of("root", "alice"), quota.users().stream()
        .map(row -> String.valueOf(row.get("username"))).toList());
assertEquals(2, quota.stats().get("users"));
assertEquals("内测访客 · ****" + suffix,
        matchmaking.reportSummaries(root).get(0).get("ownerLabel"));
```

控制器测试还要断言非 root 创建/撤销返回 403，创建响应有完整 code，dashboard 列表没有 `code_hash` 和完整 code。

- [ ] **Step 2: 运行测试确认后台尚未暴露 trial 管理**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialAdminTest,MatchmakingReportAccessTest test`

Expected: FAIL，dashboard 缺字段、统计包含 trial 或报告仍显示内部用户名。

- [ ] **Step 3: 实现后台 endpoints 和 dashboard 摘要**

`AdminAccountController` 注入 `MatchmakingTrialService`：

```java
@PostMapping("/matchmaking-trial-codes")
public ResponseEntity<?> createMatchmakingTrialCode(
        HttpServletRequest request, @RequestBody Map<String, Object> body)

@PatchMapping("/matchmaking-trial-codes/{id}")
public ResponseEntity<?> updateMatchmakingTrialCode(
        HttpServletRequest request, @PathVariable long id,
        @RequestBody Map<String, Object> body)
```

两者先 `requireCsrf` 再 `requireRoot`。dashboard data 增加 `"matchmakingTrialCodes", trials.codes()`。创建 endpoint 只在本次响应返回完整 code；patch 返回安全摘要列表。

- [ ] **Step 4: 从普通账号运营数据排除 trial**

`QuotaService.users()` SQL 增加 `WHERE role<>'MATCHMAKING_TRIAL'`。`stats()` 的 users、activeUsers 采用同一过滤；签到榜和流水天然无 trial 记录，但增加 role 过滤作为防御。不能改变 ROOT/USER 的既有排序和计数。

- [ ] **Step 5: 为 root 报告查询添加 trial 别名**

`reportSummaries` 和 `report` 左连接 `matchmaking_trial_codes tc ON tc.guest_user_id=r.user_id`，构造：

```java
String ownerLabel = row.get("code_suffix") == null
        ? String.valueOf(row.get("username"))
        : "内测访客 · ****" + row.get("code_suffix");
```

正式用户自己读取时仍返回自己的用户名；root UI 使用 `ownerLabel`。删除权限继续按 `user_id == viewer.id`，不因 root 或 trial 改变。

- [ ] **Step 6: 运行后台与报告访问测试并提交**

Run: `cd backen && mvn -q -Dtest=MatchmakingTrialAdminTest,MatchmakingReportAccessTest,RuntimeConfigImageTest test`

Expected: PASS。

Commit:

```bash
git add backen/src/main/java/com/web/backen/auth/AdminAccountController.java \
  backen/src/main/java/com/web/backen/auth/QuotaService.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingService.java \
  backen/src/main/java/com/web/backen/matchmaking/MatchmakingTrialService.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingTrialAdminTest.java \
  backen/src/test/java/com/web/backen/matchmaking/MatchmakingReportAccessTest.java
git commit -m "feat: manage matchmaking trial codes in root admin"
```

---

### Task 5: 前端受限身份、路由放行与兑换入口

**Files:**
- Modify: `front/src/api/index.js`
- Modify: `front/src/stores/auth.js`
- Modify: `front/src/router/index.js`
- Modify: `front/src/components/common/AppHeader.vue`
- Create: `front/src/utils/routeAccess.js`
- Create: `front/src/utils/matchmakingTrial.js`
- Create: `front/scripts/check-matchmaking-trial.mjs`
- Modify: `front/package.json`
- Modify: `front/src/views/MatchmakingReport/index.vue`

**Interfaces:**
- Consumes: `POST /matchmaking/trial/redeem` 返回的 auth-compatible data 和 `trialAccess`。
- Produces: `redeemMatchmakingTrial(code)` API、store `isMatchmakingTrial`/`redeemTrial`、纯函数 `routeAccessDecision(input)`、未登录邀请码 gate。

- [ ] **Step 1: 写路由和身份行为的失败门禁**

`routeAccess.js` 预期导出：

```js
export function routeAccessDecision({ visibility, trialEntry, isLoggedIn, isRoot, isMatchmakingTrial })
```

`check-matchmaking-trial.mjs` 使用 Node assert 覆盖：未登录可进入 `trialEntry`；未登录不能进入普通 USER 页面；trial 可进入 Matchmaking detail；trial 不能进入 Translate、Contact、ImageGenerate、Guestbook mutation 或 Admin；ROOT 行为不变。`matchmakingTrial.js` 导出 `trialWorkspaceState(access)`，用真实状态对象断言 CLAIMED/RETRYABLE 显示问卷、RUNNING 显示进度、COMPLETED+reportId 跳转报告、COMPLETED 无报告显示不可恢复状态。文案和真实点击不做源码字符串断言，由 Task 7 浏览器回归验证。

- [ ] **Step 2: 运行门禁确认工具和文案不存在**

Run: `cd front && node scripts/check-matchmaking-trial.mjs`

Expected: FAIL，找不到 `routeAccess.js` 或 trial 文案。

- [ ] **Step 3: 实现 API、store 和纯路由决策**

`api/index.js` 新增：

```js
export function redeemMatchmakingTrial(code) {
  return post('/matchmaking/trial/redeem', { code })
}
```

auth store 新增 `isMatchmakingTrial = computed(() => Boolean(user.value?.matchmakingTrial))` 和：

```js
async function redeemTrial(code) {
  const response = await redeemMatchmakingTrial(code)
  applyUser(response.data)
  return user.value
}
```

`refresh` 和 `login` 只在非 trial 时请求未读通知。`canView` 对 trial 只允许 PUBLIC 和 Matchmaking；共享顶栏不显示 trial 的通知、credits 和签到。

- [ ] **Step 4: 让婚恋入口公开但详情仍受控**

婚恋入口 route meta 改为 `{ visibility:'Matchmaking', trialEntry:true }`；详情保留 visibility。router guard 调用 `routeAccessDecision`，对入口返回 allow，对未登录详情返回 login/redirect-to-entry，对 trial 访问非 Matchmaking USER 页面返回 forbidden。保留 Android 下载与 Admin 的现有特殊判断。

- [ ] **Step 5: 在婚恋页增加未登录 gate 和状态恢复**

模板最外层按 `auth.isLoggedIn` 分支。未登录显示邀请码卡，不渲染问卷和历史报告；提交时：

```js
await auth.redeemTrial(trialCode.value)
const access = auth.user?.trialAccess || {}
if (access.status === 'COMPLETED' && access.reportId) {
  return router.replace(`/matchmaking-report/${access.reportId}`)
}
await loadWorkspace()
```

把现有 onMounted 的四接口加载提取为 `loadWorkspace()`，只有正式用户或 trial 会话才调用。trial `CLAIMED/RETRYABLE` 显示问卷，`RUNNING` 恢复轮询，`COMPLETED` 无报告时显示“报告已过期或已删除，内测次数不会恢复”。trial 隐藏批量删除按钮，提交按钮显示“免费生成本次内测报告”。

- [ ] **Step 6: 更新共享顶栏 trial 体验**

trial 账户区显示“婚恋内测”和“退出”，不渲染 NotificationPanel、签到按钮和 credits；桌面/手机菜单只保留 PUBLIC 项与婚恋报告。直接 URL 的后端权限仍是最终边界。

- [ ] **Step 7: 接入构建门禁并运行**

在 `package.json` 增加：

```json
"check:matchmaking-trial": "node scripts/check-matchmaking-trial.mjs"
```

并在 `build` 的 navigation gate 后调用它。

Run: `cd front && npm run check:matchmaking-trial && npm run check:navigation`

Expected: PASS。

Commit:

```bash
git add front/src/api/index.js front/src/stores/auth.js front/src/router/index.js \
  front/src/components/common/AppHeader.vue front/src/utils/routeAccess.js front/src/utils/matchmakingTrial.js \
  front/src/views/MatchmakingReport/index.vue front/scripts/check-matchmaking-trial.mjs \
  front/package.json
git commit -m "feat: add passwordless matchmaking trial entry"
```

---

### Task 6: 后台内测码面板与报告恢复文案

**Files:**
- Modify: `front/src/api/index.js`
- Modify: `front/src/views/Admin/index.vue`
- Modify: `front/src/views/MatchmakingReport/ReportView.vue`
- Modify: `front/src/views/MatchmakingReport/index.vue`
- Modify: `front/scripts/check-matchmaking-trial.mjs`

**Interfaces:**
- Consumes: dashboard `matchmakingTrialCodes`、POST/PATCH 管理接口、报告 `ownerLabel` 和 `viewerCanDelete`。
- Produces: root 生成/复制/撤销 UI、安全状态列表、trial 删除不可恢复提示。

- [ ] **Step 1: 扩展失败门禁覆盖后台唯一展示和状态文案**

在 `matchmakingTrial.js` 增加 `trialCodeStatus(code, now)` 和 `trialDeleteWarning(isTrial)`，脚本以固定时间和状态对象断言：disabled=已撤销、过期 UNUSED=已过期、UNUSED/CLAIMED/RUNNING/RETRYABLE/COMPLETED 映射正确；trial 删除警告明确不可重新生成，正式用户返回原删除警告。后台实际标签、复制和报告确认框由 Task 7 浏览器回归验证。先运行确认函数不存在而失败。

Run: `cd front && npm run check:matchmaking-trial`

Expected: FAIL，缺少后台和删除文案。

- [ ] **Step 2: 增加后台 API 函数和状态映射**

`api/index.js` 新增：

```js
export function createMatchmakingTrialCode(expiresAt) {
  return post('/admin/accounts/matchmaking-trial-codes', { expiresAt })
}
export function updateMatchmakingTrialCode(id, enabled, expiresAt) {
  return requestWithOptions(`/admin/accounts/matchmaking-trial-codes/${id}`, {
    method: 'PATCH', body: JSON.stringify({ enabled, expiresAt })
  })
}
```

Admin 状态映射固定为：UNUSED=未兑换、CLAIMED=已兑换待生成、RUNNING=生成中、RETRYABLE=可重试、COMPLETED=已完成；disabled 优先显示已撤销，未兑换且超过 expiresAt 显示已过期。

- [ ] **Step 3: 实现后台生成和列表面板**

在账号与账本分区新增锚点 `#matchmaking-trial-panel`。表单默认值由上海时区当前时间加 7 天生成；创建成功后先调用 `navigator.clipboard.writeText(code)`，再用不可自动消失的弹窗显示完整 code 和“完整邀请码仅显示这一次”。dashboard 加载和 patch 响应更新 `trialCodes`。

列表只显示 `****后四位`、状态、截止/兑换/完成时间、报告入口和启用切换，不尝试从服务端恢复完整 code。

- [ ] **Step 4: 调整报告归属和删除提示**

root 报告列表使用 `item.ownerLabel || item.ownerUsername`；详情封面同样使用 `ownerLabel`。trial 自己的报告允许单份删除，但 confirm 文案改为“删除后不能重新生成，也无法再凭邀请码恢复”。正式用户和 root 的现有文案不变。

- [ ] **Step 5: 运行前端门禁与生产构建并提交**

Run: `cd front && npm run check:matchmaking-trial && npm run build`

Expected: PASS，HTML/PWA/DAL/Android WebView/PDF 门禁全部通过；只允许现有 Sass deprecation 和 chunk size warning。

Commit:

```bash
git add front/src/api/index.js front/src/views/Admin/index.vue \
  front/src/views/MatchmakingReport/index.vue front/src/views/MatchmakingReport/ReportView.vue \
  front/scripts/check-matchmaking-trial.mjs
git commit -m "feat: manage matchmaking trial invitations"
```

---

### Task 7: 全量验证、浏览器回归与项目记录

**Files:**
- Modify: `AGENTS.md`
- Modify: `WORKLOG.md`

**Interfaces:**
- Consumes: Tasks 1–6 的全部后端和前端行为。
- Produces: 可复现的测试证据、桌面/手机 QA 结果和本地未部署记录。

- [ ] **Step 1: 运行完整后端测试**

Run: `cd backen && mvn -q test`

Expected: 所有测试 PASS；测试场景主动产生的失败日志可以存在，但 Surefire failures/errors 必须为 0。

- [ ] **Step 2: 从干净依赖安装运行完整前端构建**

Run: `cd front && npm ci && npm run build`

Expected: navigation、matchmaking-trial、HTML template、Android WebView、Vite、PWA、DAL、PDF preview 全部 PASS。

- [ ] **Step 3: 启动本地后端和生产预览完成真实兑换回归**

用独立测试数据生成一枚内测码，覆盖：未登录入口、首次兑换、含伴侣画像选项、生成失败后的可重试状态、成功后禁止再次生成、退出后原码恢复报告、撤销后已有会话 403。不得使用或覆盖生产邀请码与报告。

- [ ] **Step 4: 完成 1280px 和 390×844 浏览器检查**

桌面检查后台生成/复制/状态列表、婚恋入口和报告恢复；手机检查邀请码输入、问卷、任务进度和共享抽屉。两种尺寸都确认无横向溢出，浏览器 warning/error 日志为空。

- [ ] **Step 5: 更新项目记录**

在 `AGENTS.md` 和 `WORKLOG.md` 顶部新增 2026-08-31 条目，写明：受限 trial 身份、一次成功资格、失败重试、跨设备恢复、默认 7 天兑换、报告最长 30 天、后台管理、测试结果和“未部署生产”。不得覆盖既有记录。

- [ ] **Step 6: 最小差异与安全审查**

Run:

```bash
git diff --check
rg -n "MATCHMAKING_TRIAL|matchmaking_trial_codes|trial/redeem" backen/src front/src
rg -n "code_hash|完整邀请码|codeSuffix" backen/src/main front/src
```

人工确认：日志没有 raw code；dashboard 不返回 hash；trial 不能通过 `requireUser`；正式用户扣费和 root 权限未变；无关工作区改动未被覆盖。

- [ ] **Step 7: 提交项目记录**

```bash
git add AGENTS.md WORKLOG.md
git commit -m "docs: record matchmaking trial verification"
```

若 AGENTS.md/WORKLOG.md 被仓库忽略，则只验证内容已写入，不强制添加或提交忽略文件。
