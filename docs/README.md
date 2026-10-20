# 文档索引

## 现行规范

- [项目概览](../README.zh-CN.md)（[English](../README.md)）：功能、截图、快速开始和仓库结构。
- [贡献指南](../CONTRIBUTING.md)、[行为准则](../CODE_OF_CONDUCT.md)、[安全策略](../SECURITY.md)：开源协作入口；Issue/PR 模板在 `.github/`。
- [许可](../LICENSE)（代码 MIT）、[素材许可](../LICENSE-ASSETS.md)（美术与截图 CC BY-NC-SA 4.0）、[第三方组件与引用](../THIRD_PARTY_NOTICES.md)：新增依赖或素材时同步更新。
- [工作流](WORKFLOW.md)：开发、验证、审查、交付和文档维护。
- [部署与恢复](../DEPLOYMENT.md)：生产操作顺序、数据兼容性和脚本能力边界。
- [Android](../android/README.md)：原生容器与APK流程。

根AGENTS、WORKLOG、MAINTENANCE和前后端子目录MD按现有gitignore保留本地，不保证新克隆中存在。公共流程应从以上文件独立可读。

## 规格与实施记录

- [塔罗 v2 完整制作规范与 Terra 交接](tarot-v2/README.md)：当前入口；22牌、88个独立升级主题及完整拆层提示词，深海幻月为范本，含工程/验收/状态规则。规范完成不等于全套素材已生成。

- [22 张塔罗闪卡独立养成与视觉升级](plans/2026-09-15-tarot-card-progression.md)：养成方向已认可；五级视觉、专属母题与 AI 补充素材为讨论稿，尚未实现。
- [塔罗 AI 生图执行手册](plans/2026-09-15-tarot-ai-art-direction.md)与[44 条完整提示词](plans/2026-09-15-tarot-ai-prompts.json)：历史 v1 同构图方案与观察记录，保留追溯；当前执行使用上述 v2 规范。
- [月下占卜：实现合同与兼容边界](plans/2026-10-13-moonlit-divination.md)：当前实施入口；服务器两步抽牌、正逆位及随机主题、免费牌义、AI 解读、事务与旧报告兼容。前后端已于 2026-10-14 同批上线。
- [月下会客厅：关系人格与塔罗报告完整交接](plans/2026-09-10-moonlit-personality-tarot-handoff.md)：历史、非当前规范；已被月下占卜替代，仅供旧报告兼容追溯。
- [人格来源审查](plans/personality-source-review.md)：历史、非当前规范；人格问卷已停用。
- [关系探索素材记录](plans/2026-09-10-moonlit-relationship-assets.md)：22 张原创牌面与来源映射；牌面仍用于月下占卜。

## 已完成设计与历史材料

- [2026-09-07可靠性修复计划](plans/2026-09-07-web-reliability-fixes.md)：该次范围与验证证据；不作为后续每次任务的固定范围。
- [2026-08-31内测设计](superpowers/specs/2026-08-31-matchmaking-trial-codes-design.md)与[实施计划](superpowers/plans/2026-08-31-matchmaking-trial-codes.md)：历史设计。目录名不表示当前必须安装或启用同名插件。
- [2026-08-01维护报告](maintenance-report-2026-08-01.md)：带日期的历史报告，不是实时生产状态。

2026-09-08整理前的11份完整文档保存在本地`.run/documentation-archive/20260908/`，含原路径与SHA256清单。该目录被忽略，不将内部生产信息提交到公共仓库。旧PPT内核、旧端口和旧模板描述仅供追溯，以现行代码和当前规范为准。
