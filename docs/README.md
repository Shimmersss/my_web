# 文档索引

## 现行规范

- [项目概览](../README.md)：功能、启动和仓库结构。
- [工作流](WORKFLOW.md)：开发、验证、审查、交付和文档维护。
- [部署与恢复](../DEPLOYMENT.md)：生产操作顺序、数据兼容性和脚本能力边界。
- [Android](../android/README.md)：原生容器与APK流程。

根AGENTS、WORKLOG、MAINTENANCE和前后端子目录MD按现有gitignore保留本地，不保证新克隆中存在。公共流程应从以上文件独立可读。

## 规格与实施记录

- [月下会客厅：关系人格与塔罗报告完整交接](plans/2026-09-10-moonlit-personality-tarot-handoff.md)：面向 Luna Max 的产品、问卷、牌阵、接口、事务、视觉、验收与部署方案；本地实现已完成，生产开关仍关闭。
- [人格来源审查](plans/personality-source-review.md)：28 题来源、编码、边界和未确认事项。
- [关系探索素材记录](plans/2026-09-10-moonlit-relationship-assets.md)：22 张原创牌面与来源映射。

## 已完成设计与历史材料

- [2026-09-07可靠性修复计划](plans/2026-09-07-web-reliability-fixes.md)：该次范围与验证证据；不作为后续每次任务的固定范围。
- [2026-08-31内测设计](superpowers/specs/2026-08-31-matchmaking-trial-codes-design.md)与[实施计划](superpowers/plans/2026-08-31-matchmaking-trial-codes.md)：历史设计。目录名不表示当前必须安装或启用同名插件。
- [2026-08-01维护报告](maintenance-report-2026-08-01.md)：带日期的历史报告，不是实时生产状态。

2026-09-08整理前的11份完整文档保存在本地`.run/documentation-archive/20260908/`，含原路径与SHA256清单。该目录被忽略，不将内部生产信息提交到公共仓库。旧PPT内核、旧端口和旧模板描述仅供追溯，以现行代码和当前规范为准。
