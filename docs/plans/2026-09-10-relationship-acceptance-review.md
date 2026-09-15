# 关系探索 v1 验收复核

日期：2026-09-10。结论：暂不通过发布验收，退回修正。范围为当前工作区实现的代码、合同与本地测试复核；未修改业务实现、未部署、未调用收费模型。

## 后续返修状态

本清单保留最初验收证据，不作为当前源码描述。用户随后授权直接修复：R1–R5已作本地代码修正并补测试；R6候选题库已换成本站新编16题并启用独立版本，但牌义逐牌历史核对仍待完成。分享卡下载提示已改为请求下载而非已落盘。尚未通过下文完整发布验收项目，未部署。

## 已确认问题（按优先级）

### R1 · P1：旧版伴侣画像选项丢失

`MatchmakingService.createTask` 第484行从规范化后的 profile 读取 includePartnerImage，但旧版 `validate`（692–733行）不保留该字段。因此旧版请求即使传 true，也不加图片费用、不调用图片生成。恢复旧版布尔选项解析，并对默认版本/显式旧版本、布尔 true/字符串 true 添加任务级回归测试。

### R2 · P1：新版收费画像没有展示入口

新版问卷保留 includePartnerImage，服务端会加价并将生成图片保存为 report.partnerImage（Service 第353行）。ReportView 第8行将新版交给 RelationshipReportView；后者完整模板没有读取 partnerImage，旧版图片模板位于互斥分支。结果是付费生成后用户在新版报告里看不到图片。补充新版图片及虚构说明、失败状态，并以含图片的报告夹具验证展示。

### R3 · P1：人格回答没有进入模型，证据校验仍允许引用

Service 第258–268行仅发送人格聚合结果、关系偏好等；relationshipAgentProfile 第370行不包含 personalityAnswers。RelationshipReportAgent 第221行附近 evidence() 只检查静态 p01–p28/r01–r05 白名单，不核对本次实际输入。即使 questionnaire 路径，模型也看不到每个 pXX 的回答；selfReported/skip 同样能通过 pXX 引用。应发送经过规范化且带题意的实际回答，按本次输入构造有效证据集合；跳过/自报必须拒绝问卷证据。新增三路径正反例测试。

### R4 · P2：运行中可新抽牌，失败恢复后存在两个可用牌阵

drawTarot 无活动任务拦截；TarotService.draw 只复用 consumed_task_id IS NULL 的行。顺序 A抽牌→A提交运行→另页抽B→A失败恢复，会留下A、B两个未消费牌阵，current 按 created_at DESC 返回B，无法保证失败重试仍使用A。无需并发竞争就能触发。应明确运行中禁止新抽或返回当前任务牌阵，恢复与抽牌使用一致的用户级互斥；增加该交错生命周期测试。

### R5 · P2：人格轴直接渲染内部对象，关系阶段显示枚举

RelationshipReportView 第28行直接插值 axes 每个 value，而后端返回 name/left/right/leftCount/rightCount/dominant/strength 对象，Vue 会展示序列化对象，而非友好的倾向标签。第9/31行直接展示 relationshipStage、explorationIntent，如 gettingCloser、communicateBetter。改为明确字段与 catalogue 中文标签，测试28题路径和三种人格路径报告。

### R6 · P2：来源验收条件尚未满足

personality-source-review 明确题文原始权利未确认，但仍采用候选28题；与交接规范19.3“来源无法确认不作为正式商用题库上线”的条件不一致。不声称官方 MBTI 并不等于完成该来源门禁。应补足来源确认，或按规范采用标注本站原创的替代题库。牌义逐牌历史核对也被文档明确列为未完成，不能宣称已完成规范19.2接入审阅。本条是交付合同检查，不作法律结论。

## 本轮实际验证

- `cd backen && mvn -q -Dtest='Matchmaking*Test,Relationship*Test,TarotDeckTest' test`：退出0；相关测试全部通过。通过不表示覆盖上述缺陷。
- `cd front && npm run build`：退出0；含导航、内测权限静态检查、4个请求/轮询测试、Android桥接静态检查、Vite、PWA与PDF预览检查。保留既有Sass/chunk-size警告。
- 新增 RelationshipTaskTest 只有1个跳过人格的成功流程，且注入假报告代理；不能证明真实模型输出、画像交付或失败恢复交错行为。

## 尚不能签字的验收项目

- 本轮未复跑隔离 MySQL 事务/迁移；已有工作日志的通过记录不等于本轮独立复验。
- 未完成浏览器桌面/390/320逐章实测、真实模型生成与Android实际下载验收。
- 分享卡当前用 Blob URL 加 a.download 并立即宣称“已下载”；仅有静态Android检查，不是文件成功落盘证据。需要Web与GeckoView实测，按结果补桥接或正确的状态提示。
- 返修后优先验证R1–R4，再进行完整产品与发布验收。维持生产开关关闭；本报告不授权部署。
