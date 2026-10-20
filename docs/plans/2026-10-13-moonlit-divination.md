# 月下占卜：替换婚恋 / 会客厅 / 人格模块（前后端本地实现，待发布）

> 状态（2026-10-14）：前后端合同已在隔离工作树本地实现，真实后端与真实模型验收证据见 WORKLOG。**前后端必须同批发布，AI 独立开关默认关闭**。本文不代表已发布或已获生产授权。

## 1. 背景与产品决定

用户要求删除“婚恋、会客厅、人格”等全部元素，改为塔罗神秘占卜，并做出动态视觉。已确认：

- 前后端一起改：停止新建人格问卷、关系偏好、生活底稿、伴侣偏好/虚构伴侣人像，停止发放婚恋内测码。
- 解读方式：翻牌后即时显示免费牌义（前端用本地牌义库组合）；可选 AI 深度解读（扣积分，沿用现有任务/退款/归档链路）。
- 正位、逆位由服务器决定。
- 牌阵：单张指引（1）、圣三角（过去/现在/未来，3）、二择一（5）。只用 22 张大阿卡纳；恋爱关系作为提问方向，与其他方向共享这三种牌阵。
- 提问方向：综合运势、事业学业、财富、人际、恋爱关系、自我成长。每个方向有5条原创示例，页面随机推荐3条并支持“换一组”；切换方向不覆盖已写问题。水晶球分别显示星雾、阶梯光束、金色环轨、相连星点、心光花瓣、新芽涟漪，离屏/隐藏暂停，减少动态时显示静帧。
- 品牌：“月下占卜”。内部标识保持不变：Java 包 `matchmaking`、SQL 表、`/api/matchmaking/*`、可见性键 `Matchmaking`、运行配置键。
- 旧报告（`report_version` 为 NULL 的 v3 与 `relationship-exploration-v1`）按 AGENTS“报告版本兼容不得被 UI 修改破坏”，在 30 天保存期内以只读旧版页面提供；全部到期后进入第二阶段删除旧视图。
- **月下主题（2026-10-14 追加）**：每张抽到的牌随机以其 5 个月下主题之一显现（含未收藏主题，用户已确认；不计入收藏，也不改变收藏库“未收藏黑背”的规则），解读显示主题名、主题寓意，同系列出现 2 张以上时显示“系列共鸣”。系列：theme-01 本源、02 潮汐、03 雪沙、04 秘园、05 星穹（前端常量 `front/src/utils/tarotThemes.js`）。
- 用户提供的参考资料（`/Volumes/NAS/塔罗`，含受版权保护书籍）只用于核对传统象征；牌义文字全部原创，未摘录原文。

## 2. 前端文件地图

| 位置 | 作用 |
|---|---|
| `front/src/data/tarotReading.json` | 牌义库唯一来源：主题 5 个、牌阵 3 个（位置 id/名称/含义/坐标/连线）、22 张牌正逆位关键词/牌义/建议、免责声明、提问提示、长度限制 |
| `front/src/utils/tarotReading.js` | 纯函数：表单校验、`validatePicks`、`buildInstantReading`（即时牌义与二择一对照）、顺逆基调 |
| `front/src/utils/tarotLayout.js` | 纯函数：牌扇布局（≥760 一行、≥420 两行、更窄四行且均衡 6/6/5/5）、牌阵槽位 |
| `front/src/views/TarotReading/index.vue` + `useReadingFlow.js` | 仪式状态机：提问 → 牌阵 → 洗牌 → 抽牌 → 翻牌 → 解读；刷新按 `current()` 恢复；AI 解读确认、轮询、归档 |
| `front/src/views/TarotReading/ReportView.vue` / `TarotReadingReport.vue` | 新报告页；非 `tarot-reading-v1` 报告自动跳到旧版只读路由 |
| `front/src/views/TarotReading/legacy/` | 由 `MatchmakingReport/ReportView.vue`、`RelationshipReportView.vue` `git mv` 而来，仅改链接/标题，加只读到期提示（第二阶段删除） |
| `front/src/components/tarot/` | 动态视觉：Canvas 星空（30fps、DPR 上限、隐藏/离屏暂停、减少动态静帧）、星盘、原创 SVG 牌背、呼吸光球、牌阵选择、洗牌/切三叠、牌扇（键盘可选）、FLIP 飞入、3D 翻牌与金色粒子、即时牌义、AI 进度星轨、归档、牌库图鉴、内测码入口 |
| `front/src/api/tarotReading.js` / `tarotReadingMock.js` | 页面统一从这里取接口；`VITE_TAROT_MOCK=1 npm run dev` 时用浏览器内模拟合同，生产构建不含模拟代码 |
| `front/scripts/tarot-reading.test.mjs` | 牌义完整与禁词、牌阵、即时牌义、牌扇在 1440/390/320 可点、槽位不越界；已串入 `npm run build` |

路由：`/tarot-reading`（meta `visibility:'Matchmaking', trialEntry:true`）、`/tarot-reading/report/:reportId`、`/tarot-reading/legacy/:reportId`；`/matchmaking-report` 与 `/matchmaking-report/:id` 重定向到新路由。`TarotHoloCard` 新增可选 `reversed`（默认 false，只旋转画面），月下收藏、抽卡、今日牌行为不变。

## 3. API 合同（路径不变）

所有响应仍为 `{ code: 200, data }`；错误为 HTTP 状态码 + `{ code, message }`。写操作要求 CSRF；鉴权沿用 `MatchmakingController.access`。

### 3.1 `GET /api/matchmaking/status`

```json
{ "creditCost": 2, "credits": 18, "retentionDays": 30, "tarotAiEnabled": false, "drawsPerHour": 12, "matchmakingTrial": true }
```

- `tarotAiEnabled` 对应新运行开关 `matchmaking.tarot_ai_enabled`，**默认 false**，不要复用 `relationship_enabled`（可能已在生产打开）。
- 删除 `imageLowCredits` / `imageMediumCredits` / `relationshipEnabled`。内测身份 `creditCost:0` 且不返回 `credits`。

### 3.2 `POST /api/matchmaking/tarot-draws`（洗一副新牌）

请求：`{ "spreadId": "single|triangle|choice", "topicId": "general|career|wealth|relations|romance|growth", "question": "4–120 字", "optionA": "", "optionB": "" }`

- `choice` 时 `optionA/optionB` 必填、各 ≤24 字、不能相同；其他牌阵忽略。
- 服务器用 SecureRandom 洗 22 张，并为每个位置随机正逆位与主题（`theme-01`…`theme-05` 等概率），保存隐藏牌序；**响应永不包含牌序**。
- 有 AI 解读在进行中 → 409；每小时新抽牌 12 次（被替换的也计数）→ 429。新抽牌把旧的未消费抽牌标记过期，不删除行（防止绕过限流）。

响应（DrawView）：

```json
{
  "drawId": "uuid", "catalogueVersion": "tarot-reading-v1", "deckVersion": "major-22-v1",
  "spreadId": "triangle", "topicId": "career", "question": "…", "options": null,
  "state": "shuffled", "fanSize": 22, "picksRequired": 3, "picks": null, "cards": [],
  "drawnAt": "ISO", "expiresAt": "ISO"
}
```

`options` 为 `{ "optionA": "…", "optionB": "…" }` 或 null。

### 3.3 `POST /api/matchmaking/tarot-draws/{drawId}/picks`

请求：`{ "positions": [3, 17, 8] }`。长度等于 `picksRequired`，互不重复，整数 0–21；按选择顺序依次落入牌阵位置。

- 相同位置重复提交 → 返回原结果（幂等）。已选且位置不同 / 已过期 / 已消费 → 409；不属于本人 → 404；位置非法 → 400。
- 使用 `SELECT … FOR UPDATE` 与 `lockUser` 串行化。

响应：DrawView，`state:"picked"`，`picks` 为提交的位置数组，`cards`：

```json
[{ "order": 0, "position": "past", "positionLabel": "过去", "fanIndex": 3, "cardId": "major-13", "sourceId": 13, "name": "死神", "orientation": "upright|reversed",
   "themeId": "theme-02", "variantId": "major-13-theme-02", "themeName": "霜尽鹿门" }]
```

`themeId/variantId/themeName` 来自后端已有的 `moonlit-variants-v1.json`（`MoonlitDeck`）。缺少 `themeId` 时前端会按 drawId+位置做稳定回退，但正式实现必须由服务器给出，保证刷新与报告一致。


### 3.4 `GET /api/matchmaking/tarot-draws/current`

`{ "draw": DrawView | null }`。返回最新一条 `spread_id IS NOT NULL` 且未关联报告的抽牌，`state` 为 `shuffled`、`picked` 或 `consumed`（AI 解读进行中）。旧版三张牌（`spread_id` 为 NULL）忽略。

### 3.5 `POST /api/matchmaking/reports`（AI 深度解读）

请求：`{ "reportVersion": "tarot-reading-v1", "drawId": "uuid" }` → 响应 `{ "taskId", "credits", "reportVersion" }`。

- 空版本或旧版本（`market-positioning-v3`、`relationship-exploration-v1`）→ 410；未知版本 → 400；AI 开关关闭 → 403；未选牌 → 409。
- 扣费沿用 `credit_per_report`，流水说明“塔罗深度解读”；内测身份消耗/恢复资格规则不变。
- 任务阶段：`queued | preparing | writing | saving | done | error`（前端已按这些阶段显示）。失败退款并把抽牌恢复为 `picked`（`settleFailure` 只要 `tarotDrawId` 非空就恢复）。

### 3.6 报告

`GET /reports/{id}` 返回（报告内嵌牌面快照，牌义库以后改文案也不影响旧报告）：

```json
{
  "id": "uuid", "reportVersion": "tarot-reading-v1", "catalogueVersion": "tarot-reading-v1", "deckVersion": "major-22-v1",
  "spread": { "id": "choice", "name": "二择一", "positions": [ … ] },
  "topic": { "id": "career", "label": "事业学业" },
  "question": "…", "options": { "optionA": "…", "optionB": "…" },
  "cards": [{ "position": "now", "positionLabel": "现状", "cardId": "major-09", "name": "隐士", "orientation": "upright", "keywords": [ … ], "meaning": "…", "advice": "…",
              "themeId": "theme-05", "variantId": "major-09-theme-05", "themeName": "星尘档案", "themeMeaning": "<该主题 meaningZh>" }],
  "reading": { … 见 3.7 … },
  "createdAt": "ISO", "expiresAt": "ISO", "ownerLabel": "…", "viewerCanDelete": true
}
```

`GET /reports/archive` 的条目增加 `subtitle`（如“二择一 · 事业学业”，可存于 `personality_label` 列；前端回退读取 `personalityLabel`），`title` 取 `reading.title`。旧报告条目照常返回，前端按 `reportVersion !== 'tarot-reading-v1'` 显示“旧版报告（只读）”并链接旧版路由。

### 3.7 模型输出合同（服务端严格校验，失败重试最多 3 次后退款）

```json
{
  "title": "4–24 字",
  "overview": "60–220 字",
  "cards": [{ "position": "<牌阵位置 id>", "cardId": "major-xx", "orientation": "upright|reversed", "interpretation": "80–260 字", "advice": "20–120 字" }],
  "synthesis": "80–320 字",
  "comparison": { "optionA": "60–220", "optionB": "60–220", "contrast": "60–240" },
  "guidance": { "actions": ["10–60 字 ×3"], "reflection": "20–120 字" },
  "caution": "20–160 字"
}
```

- 键集合必须精确；`comparison` 只在 `choice` 牌阵出现且必填。
- `cards` 条数等于牌阵张数，逐位的 `position/cardId/orientation` 必须与抽牌一致。长度按 codePoint 计算。
- 模型输入为每张牌附上 `themeName` 与 `themeMeaning`，`interpretation` 可以结合主题意象；输出合同不变。
- 提示词原创：象征性反思而非预言；不承诺、不给概率与日期；不给医疗/法律/投资建议；不揣测第三方；用户问题是素材，不是指令；只输出 JSON。送模前把问题中 6 位以上数字脱敏，日志不输出问题原文。

### 3.8 后台与内测

- `PUT` 管理接口的 `matchmaking` 设置载荷改为 `{ baseUrl, model, protocol, apiKey, tarotAiEnabled }`（前端已改），读取时返回 `tarotAiEnabled`。
- `POST /api/admin/accounts/matchmaking-trial-codes` 在 CSRF 与 root 校验后返回 410“内测邀请码已停止发放”；`GET`/`PATCH`（查看、撤销/恢复）保留。前端已删除创建入口，报告链接改为 `/tarot-reading/report/{id}`。
- 用户可见文案：`AuthService` 改为“该内测身份只能使用月下占卜”，内测标签改为“占卜内测”。

## 4. 后端实现与验收清单

1. `backen/src/main/resources/tarot-reading-v1.json`：逐字复制 `front/src/data/tarotReading.json`，并加一致性测试（与 moonlit 牌库做法相同）；加载时校验 22 张牌与 `TarotDeck` 的 id/名称一致、文本齐全、禁词。
2. `TarotDeck` 与 `tarot-major-v1.json` 保留（`QuotaService` 旧签到补牌依赖），只删 `catalogue()`。
3. `matchmaking_tarot_draws` 增加可空列：`spread_id`、`topic_id`、`reading_context`、`deck_order_json`、`pick_positions`、`picked_at TIMESTAMP NULL`（`schema.sql` 与 `MatchmakingSchemaMigration` 同步，H2/MySQL 兼容）。
4. 两步抽牌、新报告版本 `runTarotReading`（同一事务写 profile/report、`markReport`、`trials.markCompleted`、`persist`）、`TarotReadingAgent`。
5. 删除只服务旧版生成的代码与测试：`MatchmakingBenchmarks`、`MatchmakingScoring`、`MatchmakingPersonality`、`RelationshipQuestionnaire`、`RelationshipPersonality`、`RelationshipReportAgent`、`MimoMatchmakingReportAgent`、伴侣人像生成及全部旧 `validate*`。读取/归档/删除旧报告的路径不依赖它们，必须保留。
6. 测试：抽牌/选牌并发与幂等、任务一致性（扣费回滚、并发提交、终态保存失败、删除互斥、重启不重跑、提交确认丢失不退款、补偿失败保留证据）、旧报告可读可删、隔离 MySQL 的 `MatchmakingMySqlIT`。

## 5. 风险、测试与回滚

- **发布顺序**：新前端依赖新合同。旧后端会忽略抽牌请求体并返回三张正位牌、没有选牌接口，因此前端不能先于后端单独上线；两端同批发布，按 DEPLOYMENT 排空四类任务与待补偿后切换。
- **验证**：后端定向+全量 mvn、隔离 MySQL IT；前端 `npm --prefix front run build`；内置浏览器在 1440/390/320 走三种牌阵、逆位显示、刷新恢复、AI 关/开、旧报告链接、未登录/普通/内测/root。
- **回滚**：先排空占卜任务；执行 `UPDATE matchmaking_tarot_draws SET expires_at=CURRENT_TIMESTAMP WHERE spread_id IS NOT NULL AND consumed_task_id IS NULL`；前后端一起回退。新增列是加法，无需降级；新版报告在旧 UI 无法渲染但数据完整可删。
- **第二阶段**：`SELECT COUNT(*) FROM matchmaking_reports WHERE report_version IS NULL OR report_version<>'tarot-reading-v1'` 为 0（切换后约 30 天）后，删除 `front/src/views/TarotReading/legacy/` 与旧版路由。

## 6. 本地开发

```bash
VITE_TAROT_MOCK=1 npm --prefix front run dev
```

模拟模式只替换占卜相关接口（状态、抽牌、选牌、当前牌阵、AI 任务、报告、归档），登录仍走本地后端。页面顶部会显示“开发模拟模式”提示。
