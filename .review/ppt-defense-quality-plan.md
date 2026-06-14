# 答辩 PPT 生成质量改造计划

## Summary

- 将 PPT 生成切换为“答辩优先”策略：内容学术化、结构完整、每页 3-6 个要点、正文页尽量图文结合。
- 上传模板时只继承视觉风格，不复用模板原正文、原图片、原图表或示例占位内容。
- 将论文图片数量从当前偏少的 8 张上限提升为动态预算：20-30 页答辩 PPT 默认目标 15-25 张候选视觉素材。
- 保持现有单 worker、有界队列、96 DPI 渲染和 2 核 / 4 GB 资源约束。

## Key Changes

- 在 `PptGenerationService.java` 增加 `defenseMode` 策略：用户提示包含“答辩/毕业/论文汇报”或上传论文时启用，默认目标约 22 页；若用户明确指定页数则严格尊重。
- 改写 deck 与 template-fill prompts：明确要求目录页、章节过渡页、致谢页；正文页优先图文混排；禁止大段原文复制、营销风、炫酷科技风和模板示例内容复用。
- 扩展内部 JSON：普通 deck slide 增加可选 `visualSpec`；模板 fill plan slide 增加可选 `generated_visual`，用于论文图片不足时生成流程图、结构框图、对比信息图或数据图。
- 在 `PptInputExtractor.java` 调整图片预算：默认 `PPT_GENERATION_MAX_EXTRACTED_IMAGES` / `PPT_GENERATION_MAX_VISION_IMAGES` 提升到 24，并按目标页数动态取样；20-30 页任务目标保留 15-25 张候选图。
- 图片优先级改为：caption/图号图片、流程图、架构图、网络结构、实验结果、对比图、可视化结果优先；模糊截图、纯文字图片、空白图降权或剔除。
- 视觉识别改为分批处理，每批最多 8 张，避免一次性把 20+ 图片压给 vision model；最终合并成一个 `image-manifest.json`。
- fallback manifest 不再把非表格图片全部判为低价值；按文件名、页码、caption、尺寸和上下文关键词给出保守可用评分。
- 为 `visualSpec/generated_visual` 增加确定性 PNG 渲染兜底，生成学术风流程图、结构框图、矩阵、时间线、对比图；这些生成图进入 manifest，和论文图片一样参与配图。
- 自由生成 renderer 根据图片尺寸、图片数量和页面类型自动选 `image-left/right`、`image-top`、`full-image`、`comparison`、`matrix`、`timeline` 等布局，避免连续页面同版式。

## Template Handling

- 在 `applier.py` 增加 `--strip-source-content` 模式，并由后端上传模板链路默认启用。
- 清理规则：克隆模板页后，清空所有未被替换的旧文字；删除或清空模板原正文图片、内容图表、示例表格和占位内容。
- 保留规则：母版、背景、主题、布局、字体样式、配色、页眉页脚结构、校徽/logo、装饰线条、页面框架保留。
- 模板图片区域中，原内容图先移除，再把论文图或生成视觉图等比 contain 到该区域，避免旧图透出。
- `compactSlideLibrary()` 不再把模板 `text_summary` 和原 slot 文本作为可借用内容暴露给 LLM，只保留槽位角色、几何、容量、页型和可填图片区信息。
- 表格/图表：除非 fill plan 明确给出基于论文内容的新 `table_edits/chart_edits`，否则不保留模板原图表数据。

## Test Plan

- 后端单测：上传模板任务生成后，解压 PPTX 验证模板原正文、示例图片、原图表内容不再出现；校徽/背景/装饰关系仍存在。
- 后端单测：20-30 页答辩请求下，图片预算至少进入 15 张候选；vision 分批调用；manifest 合并后保持 caption/图号素材优先。
- 后端单测：当论文图片不足时，生成 `generated_visual` PNG，并能进入普通 renderer 和模板图片区域。
- Prompt/JSON 单测：LLM 返回低质图片 ID、无效图片 ID、模板装饰区域 ID 时，后端继续硬过滤。
- Python 检查：`python3 -m py_compile` 覆盖 renderer、parser、template-fill 脚本。
- Java 检查：运行 `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest,PptInputExtractorTest test`；风险较高时补跑 `mvn -q -f backen/pom.xml test`。
- 文档验收：更新 `AGENTS.md` 和 `WORKLOG.md`，记录模板只继承视觉风格、动态提图预算、生成视觉兜底和资源约束。

## Assumptions

- 不改前端交互；用户仍通过现有 `/contact` PPT 生成页上传提示词、论文和可选模板。
- 不引入额外并发；仍保持单 worker、有界队列，图片渲染逐张处理并及时释放内存。
- 自动生成的补充图采用确定性学术图形渲染，不调用外部图片生成模型。
- 用户明确指定页数时优先满足指定页数；未指定且为答辩场景时默认生成约 22 页。
