# Worklog

按时间倒序，最新在最上。每条对应一个或几个 git commit。

格式：日期 · 一句话总结 · 改了什么 · 为什么 · 踩了什么坑。

---

## 2026-06-02

### perf: BabelDOC 实时阶段进度 + 可选并发加速

**改动**：
- 新增 `backen/scripts/babeldoc_runner.py`，直接调用 BabelDOC Python API，将结构化进度以 JSON 行输出
- 后端边读取子进程输出边发送 SSE `progress`，包含阶段、当前项、总项和整体百分比
- `/status/{taskId}` 同步返回最新进度，断线后仍可恢复
- 前端进度条改为真实百分比，并显示“分析页面版式 / 翻译正文 / 重新排版 / 生成 PDF”等阶段
- 配置态新增稳定模式（4 QPS）和加速模式（8 QPS），默认使用加速模式
- runner 关闭自动术语抽取，并让 worker 数与 QPS 对齐，减少额外模型调用和等待
- runner 收到 BabelDOC `finish` 后主动退出，避免 macOS 上 ONNX/CoreML 原生后台线程让已完成任务迟迟不返回 `done`
- BabelDOC API Key 改为通过子进程环境变量传递，不再暴露在本机进程参数中

---

### feat: BabelDOC 字体族选择 + 纯中文 / 双语 PDF 预览切换

**能力边界**：BabelDOC `0.6.2` 原生支持 `--primary-font-family=serif|sans-serif|script`，但不提供固定字号参数。字号仍由版面引擎根据原文本框自动适配，避免破坏排版。

**改动**：
- 配置态增加译文字体风格：自动匹配、衬线、无衬线、手写 / 斜体
- BabelDOC 一次生成并缓存纯中文 `mono.pdf` 与双语 `dual.pdf`
- `/api/translate/download-pdf/{taskId}` 增加 `mode=translated|bilingual`
- 结果页增加纯中文 / 双语对照切换；切换只读取缓存 Blob，不会重新翻译
- 下载 PDF 按钮下载当前预览版本

---

### perf: PDF 翻译收敛为 BabelDOC 单链路

**问题**：接入 BabelDOC 后仍先用 `LlmService` 串行逐段翻译，再让 BabelDOC 完整翻译一次。同一篇论文被翻译两遍，耗时和模型费用都翻倍，网页预览与最终 PDF 还可能不一致。

**改动**：
- `/api/translate/start/{taskId}` 只保存页面范围，不再用 PDFBox 重建段落
- SSE 直接启动 BabelDOC，事件精简为 `layout / done / error`
- 最终 PDF 缓存在 `TranslationSession`；预览和下载不会重复调用模型
- TXT 下载从 BabelDOC 已生成的中文 PDF 提取，不再额外调用模型
- 前端移除逐段实时预览、双语切换和逐段复制，结果页以 PDF 预览为主
- 修复断线恢复重复启动翻译的问题：恢复时只轮询 `/status/{taskId}`

**保留**：`LlmService`、`TranslationProtector`、`PdfParseService` 的旧段落逻辑暂时留作历史参考，不再进入默认翻译链路。

---

### feat: 原招商加盟页替换为 OpenClaw 原生网页对话

**目标**：把原“招商加盟”页面替换为 OpenClaw Gateway 对话框，让用户在个人主页内直接与本机 OpenClaw 聊天。

**方案演进**：
- 最初尝试把 OpenClaw Control UI 嵌入 iframe
- 实测 Gateway 返回 `X-Frame-Options: DENY` 和 CSP `frame-ancestors 'none'`
- 浏览器会强制阻止嵌入，因此改为站内原生聊天 UI + Spring Boot 后端 CLI 代理

**改动**：
- 前端：
  - `Franchise/index.vue` 删除招商加盟表单、案例和流程，改成 OpenClaw 登录 + 聊天界面
  - 使用 `ADMIN_KEY` 登录，密钥只放 `sessionStorage`
  - 支持读取历史、发送消息、每 2 秒轮询、Enter 发送、Shift+Enter 换行
  - 仿照官方 Control UI 增加左侧对话栏：真实会话列表、搜索、新建和切换
  - 顶部增加 Gateway 模型与思考级别选择器，按当前 session 保存 override
  - 模型列表加入后台定时刷新，顶部刷新按钮同步刷新历史、会话和模型；Gateway 新增白名单模型后无需重新登录
  - 后端模型接口改为 `models.list(view=all)` 与 `config.get.models.providers.*.models` 取交集：只展示本机显式配置模型，不再依赖 `agents.defaults.models` 白名单
  - 输入 `/` 时读取 Gateway 指令列表并展示补全菜单，支持上下键和 Tab
  - 增加当前会话重置按钮；选中会话 key 存入 `sessionStorage`
  - 输入框增加回形针附件按钮，支持最多 5 个、单文件最多 20 MB，发送前可移除
  - 顶部增加会话文件按钮，通过 Gateway artifacts API 列出并下载 agent 生成产物
  - 发送时先本地插入 optimistic 用户消息，解决等待 Gateway 历史轮询后才显示的问题
  - 图片附件在输入区和已发送消息气泡中显示缩略图；刷新页面后根据 Gateway 历史 `MediaPath` 恢复
  - 回复处理中每 420 ms 跟踪历史，同一个 assistant 气泡逐字推进；CLI 代理模式下作为官方 WebSocket `chat.delta` 的兼容方案
  - 后台轮询增加历史签名比较：无变化不触发消息数组重绘；用户查看旧消息时不抢滚动位置，仅显示回到底部提示
  - 对话气泡改为常见聊天布局：用户提问、头像和附件右对齐，OpenClaw 回复保持左对齐
  - 消息气泡增强边框、背景、方向化圆角和轻微阴影，左右两侧对话页框更清晰
  - assistant 消息通过 `marked + DOMPurify` 安全渲染 markdown
  - 导航名称和页面标题从“招商加盟”改成 `OpenClaw`
  - `front/src/api/index.js` 新增 OpenClaw 登录、历史、发送、会话、模型、指令和产物下载 API
- 后端：
  - 新增 `config/OpenClawConfig.java`
  - 新增 `openclaw/OpenClawController.java`
  - 新增 `openclaw/OpenClawService.java`
  - 提供 `/api/openclaw/login`、`/history`、`/send`、`/sessions`、`/sessions/reset`、`/models`、`/commands`、`/artifacts`、`/artifacts/{artifactId}/download`
  - 后端启动本机 `openclaw gateway call` 子进程，代理 `chat.*`、`sessions.*`、`models.list`、`commands.list`、`artifacts.*`，浏览器不接触 Gateway token
  - 上传附件校验数量、文件名和 base64 体积；下载只接受 Gateway artifactId，不开放任意本机路径
  - 增加 `/api/openclaw/media/inbound/{filename}`，仅允许读取 `~/.openclaw/media/inbound/` 下非软链接普通文件，用于恢复历史图片 Blob
  - CLI stdout 使用异步读取，避免历史 JSON 较大时填满管道导致 `OpenClaw Gateway 响应超时`
- 配置：
  - `.env.local.example` 新增 `OPENCLAW_COMMAND`、`OPENCLAW_SESSION_KEY`、`OPENCLAW_TIMEOUT_SECONDS`
  - `application.yml` 新增 `openclaw.*`

**部署 / 迁移**：
1. 新机器安装 OpenClaw CLI：`npm install -g openclaw`
2. 安装前端依赖：`cd front && npm install && cd ..`
3. 执行 `openclaw setup` 或 `openclaw onboard`
4. Gateway 默认只监听 `127.0.0.1`，因此 Spring Boot 与 OpenClaw 必须部署在同一台主机
5. 启动服务并确认连通：

```bash
openclaw gateway start
openclaw gateway status
```

6. `.env.local` 设置强 `ADMIN_KEY` 和 OpenClaw 参数
7. `./project.sh start`
8. 首次站内发送消息前确认 `openclaw gateway status` 显示 `Capability: admin-capable`
9. 若为 `read-only`，触发发送后运行 `openclaw devices list --json`，批准 CLI scope upgrade
10. 公网部署时在前面放 HTTPS 反向代理，并额外限制 `/api/openclaw/*`；不要把 Gateway 本身直接暴露公网

**踩坑**：
- OpenClaw 官方控制台不能 iframe；这是响应头安全策略，不是前端样式问题
- `chat.history` 可以在只读权限下工作，但 `chat.send` 需要写权限。只验证历史接口会产生“看似正常，实际不能聊天”的假象
- 本机 OpenClaw `2026.5.28` 在 CLI 只读设备给自己升级时出现 request id 滚动和 `unknown requestId`。普通 `openclaw devices approve` 未命中后，使用 OpenClaw 自身 `device-pairing` 内部模块在 Gateway 主机本地批准最新 repair request
- `Process.waitFor()` 之前如果不并行消费 stdout，大段 `chat.history` JSON 会填满子进程管道，形成阻塞直到超时
- `/api/openclaw/*` 不能公开匿名发送，否则访问主页的人都能驱动本机 OpenClaw。当前复用 `ADMIN_KEY` 保护

**验证**：
- `npm run build` 通过（仅已有 Sass deprecation 和 chunk-size 警告）
- `mvn -q -DskipTests package` 通过
- `git diff --check` 通过
- 正确密钥登录 `200`
- 未授权历史请求 `401`
- 空消息发送 `400`
- 历史读取 `200`，成功返回 `main` 会话
- 后端直连发送 `200`，OpenClaw 回复：`代理发送测试成功`
- 浏览器实际 Vite `/api` 代理发送 `200`，OpenClaw 回复：`网页代理测试成功`
- 多会话 API 读取 `2` 个已有 session、`2` 个模型和 `65` 条 `/` 指令
- 通过 Vite `/api` 代理新建隔离会话 `网页控制台联调`，切换到 `deepseek/deepseek-v4-pro` + `thinkingLevel=low`
- 隔离会话发送 `200`，OpenClaw 回复：`控制台联调成功`
- 隔离会话上传 `openclaw-upload-test.txt`，Gateway 保存 inbound media 后 agent 成功回读：`attachment bridge verified`
- `artifacts.list` 代理返回 `200`；当前隔离会话没有生成 artifact，列表为空
- 隔离会话上传 `openclaw-image-test.png`，Gateway 记录 `MediaType=image/png`，agent 回复：`图片附件已收到`
- 历史图片恢复接口：正确密钥返回 `68` 字节 PNG，未授权 `401`，不存在文件 `404`
- 自动模型发现验证：站内接口返回 `8` 个显式配置模型，包含未加入官方白名单的 `deepseek-chat`、`deepseek-reasoner`
- 最终 Gateway：`Connectivity probe: ok`、`Capability: admin-capable`、pending approvals `0`

---

### feat: 翻译完成后先内嵌预览 PDF

**问题**：用户当前最关心翻译 PDF 的版式保持，结果页只有段落级文本预览，无法第一时间判断生成的 PDF 是否保持原页面布局、页码、图片、表格和公式位置。

**改动**：
- 前端 `Translate/index.vue` 结果态新增“翻译 PDF 预览”面板
- 翻译完成后自动调用 `/api/translate/download-pdf/{taskId}` 获取 PDF Blob
- 使用 `URL.createObjectURL()` 生成本地预览 URL，并用 iframe 内嵌显示
- 提供“刷新预览”按钮，下载 PDF 按钮保留
- 重置任务和组件卸载时释放 Object URL，避免 Blob 泄漏
- `front/src/api/index.js` 新增 `getTranslatedPdfBlob()`

**验证**：
- `mvn -q -DskipTests package` 通过
- `npm run build` 通过（仅已有 Sass deprecation 和 chunk-size 警告）

**仍未完全解决**：
- 预览能让用户先检查版式，但真实论文复杂排版是否足够像原文，仍取决于后端 PDF 内容流重建质量。

---

### fix: 长译文不再静默截断

**问题**：旧渲染逻辑里，如果译文换行后超过原文 bounding box，高度不够时会 `break` 停止绘制后续行。这会造成“输出 PDF 看似成功，但译文尾部被删除”，违反“不删除任何内容”。

**改动**：
- `PdfRenderService` 新增 `TextLayout`，统一记录行列表、字号、行高和 overflow 状态
- 翻译块优先尝试缩小字号和压缩行距来塞进原框
- 即使仍然超出原框，也继续完整绘制所有行，不再静默截断
- protected 原文块仍保持单行原样绘制，避免短标签/单位被换行拆坏

**验证**：
- `mvn -q -DskipTests package` 通过
- 临时长译文 PDF 验证：输出 `pages=1`，末尾标记 `ENDMARK` 可从 PDF 文本层抽取到，原英文不残留
- `npm run build` 通过（仅已有 Sass deprecation 和 chunk-size 警告）

**仍未完全解决**：
- 极端长译文完整绘制时可能向下侵入相邻区域；后续更高保真方案需要按邻近块动态让位或做页面内局部重排。

---

### fix: 按水平空隙拆分表格行，保留列结构

**问题**：PDFBox 可能把同一行中的多个表格单元格合成一行文本，例如 `Method    95%    12 ms`。如果整行作为一个翻译块，会破坏表格列结构，也容易把数字/单位跟英文列名一起送去 LLM。

**改动**：
- `PdfParseService.PositionStripper.flushLine()` 不再把整行直接生成一个 `LineBox`
- 根据相邻 `TextPosition` 的水平空隙拆分为多个 segment/cell
- `shouldStartNewBlock()` 识别同一 baseline 上的不同水平 segment，避免后续又合并回一个段落块
- `PdfRenderService` 对 `translatable=false` 的 ASCII 文本使用 Helvetica 单行原样绘制，避免 `%`、`s` 这类字符在中文字体/窄框换行中丢失

**验证**：
- `mvn -q -DskipTests package` 通过
- 临时表格 PDF 解析验证：`Method / Accuracy / Latency / Ours / 95% / 12 ms` 被拆成独立块，数字和单位块为 protected，未合并成整行
- 临时表格 PDF 渲染验证：输出 `pages=1`，`hasChinese=true`，`hasOriginalEnglish=false`，`hasNumber=true`，`hasUnit=true`

**仍未完全解决**：
- 长英文表头目前会作为独立翻译块处理，但还没有真正的表格区域检测/列宽自适应。
- 复杂跨行表头、合并单元格、斜线表头仍需要真实论文样本继续验证。

---

### fix: 保留短表格值、单位和变量标签

**问题**：清理原页文本显示操作后，只重绘翻译块和公式/页码会带来一个新风险：表格里的短数字、单位、坐标轴标签、变量名等可能既不该翻译，也不该删除。临时验证里 `95% 12 ms x_i` 被 PDFBox 合成一行后，一开始会被当成可翻译块，导致原样内容丢失。

**改动**：
- `PdfParseService.shouldTranslateText()` 先判断短保护文本，再决定是否送翻译
- 新增 `looksLikeShortProtectedText()`：
  - 保护短数字/百分比/单位
  - 保护短变量名和带符号标签，例如 `x_i`
  - 保护短表格值、坐标轴标签、单位行
- `shouldPreserveOriginalText()` 复用同一套短文本保护规则，确保清理原文字流后会原样重绘

**验证**：
- `mvn -q -DskipTests package` 通过
- 临时 Java 验证 PDF：英文长段落不残留，`E = m + c`、`95%`、`12 ms`、`x_i`、页码 `1` 均可在输出 PDF 文本层中保留

**仍未完全解决**：
- 表格内部的长英文列名/句子仍会被当作翻译块，可能需要更细的表格区域识别。
- 多行复杂公式和特殊数学字体仍依赖启发式判断。

---

### fix: 清理原页文本显示操作，减少复制到底层英文

**问题**：上一轮已经把输出改为“原页矢量背景 + 中文文本叠加”，但复制/文本抽取时仍可能读到底层被遮罩的英文，因为导入的原页 Form XObject 里还保留英文内容流。

**尝试**：
- 先验证 `/Artifact ... EMC` 标记原页背景，结果 PDFBox `PDFTextStripper` 仍会抽到底层英文，不能解决问题。
- 改用 PDFBox 内容流解析/重写：`PDFStreamParser` 解析导入页 Form XObject，删除文本显示操作（`Tj/TJ/'/"`），再用 `ContentStreamWriter` 写回。

**改动**：
- `PdfRenderService.stripTextShowingOperators()` 删除导入页 Form XObject 的文本显示操作
- 保留图片、路径、表格线等矢量/图像背景，不回退到整页截图
- `PdfParseService.Paragraph` 新增 `translatable` 标记
- 页码、公式样文本标记为 `translatable=false`，不送 LLM 翻译
- `TranslationService` 对 `translatable=false` 块直接保留原文，并通过 SSE 标记 `preserved=true`
- `PdfRenderService` 对 `translatable=false` 块在清理原文字流后原样重绘，避免页码/公式样文本跟着消失

**验证**：
- `mvn -q -DskipTests package` 通过
- 临时 Java 验证程序生成 3 页 PDF，只渲染第 2 页，输出 `pages=1`
- 输出 PDF 文本抽取结果：`hasChinese=true`，`hasOriginalEnglish=false`
- 同一验证里公式样文本 `E = m + c` 和页码 `2` 可被原样重绘并抽取到

**仍未完全解决**：
- 复杂公式如果不是被当前启发式识别成公式样文本，而是混在普通英文段落或拆成特殊字形，仍可能被清掉或被翻译块覆盖。
- 当前是更接近“文本替换”的 PDFBox 自研路线，还不是 PDFMathTranslate/PDF2ZH 那种完整版面重建。复杂双栏、表格内部文字、多字体数学符号仍需要继续验证和优化。

---

### fix: PDF 翻译按行坐标重建文本块 + 修正绘制坐标

**问题**：翻译 PDF 虽然已经改成选页输出和矢量背景，但版型仍容易错位。旧解析逻辑先用 `PDFTextStripper` 拿纯文本段落，再用“段落换行数”去匹配行坐标；遇到双栏、标题、图注、图表附近文本时，很容易把遮罩和译文画到错误区域。另外 PDFBox 提取的 y 坐标接近左上角阅读坐标，渲染时直接写入 PDF 内容流会发生上下坐标系错配。

**改动**：
- `PdfParseService.PositionStripper` 改为同时记录每行文本和 bounding box
- 不再用纯文本段落反推坐标，改为按同栏、行距、缩进、标题/图注边界重建 layout block
- 保留页码/公式样文本跳过逻辑，避免遮罩公式和页码
- `PdfRenderService` 绘制遮罩/译文前把阅读坐标转换为 PDF 左下角坐标：`pageHeight - y - height`
- 中文自动换行和字号适配改用 PDFBox 字体真实宽度，减少中英文缩写、数字混排时的溢出

**验证**：
- `mvn -q -DskipTests package` 通过
- 临时 Java 验证程序生成 3 页 PDF，只渲染第 2 页，输出 `pages=1`
- 输出 PDF 可通过 PDFBox `PDFTextStripper` 提取到中文译文，证明译文仍是可复制文本

**仍未完全解决**：
- 当前仍是“原页矢量背景 + 遮罩 + 中文文本叠加”。为了保留图片、表格、公式和页面结构，暂未全局删除原页文本显示操作。
- 因为底层原页 Form XObject 仍包含英文内容流，复制/抽取文本时可能同时读到被遮罩的英文。要完全达到“仅英文替换为中文、复制内容也只有中文”，还需要做坐标级 PDF 内容流编辑，或更深入借鉴 PDFMathTranslate/PDF2ZH 的版面重建/文本替换方案。

---

### fix: 翻译 PDF 输出改为选中页 + 非扫描型可复制译文

**问题**：翻译 PDF 旧实现有两个明显问题：
1. 用户只选择部分页面翻译，但下载 PDF 仍导出原文全部页。
2. `PdfRenderService` 把每页先渲染为 200 DPI 图片当背景，再盖白框写译文，输出接近扫描型 PDF，版型粗糙，文件也偏大。

**参考方向**：优先调研开源方案，PDFMathTranslate / PDF2ZH 是更接近目标的路线：做版面分析、文本块级替换、保留公式/图表/表格，而不是整页截图。当前项目先落地可控的 PDFBox 改造，后续继续往内容流级替换推进。

**改动**：
- `TranslationSession` 记录 `startPage/endPage`
- `TranslationService.startTranslation()` 校验并保存有效页面范围
- `TranslateController.status()` 返回 `startPage/endPage`
- `PdfRenderService.renderTranslatedPdf()` 改为只循环用户选择的页面范围
- `PdfRenderService` 不再用 `PDFRenderer.renderImageWithDPI()` 生成整页图片背景
- 改用 PDFBox `LayerUtility.importPageAsForm()` 把原页面作为矢量 Form XObject 画入新 PDF
- 在矢量原页上叠加白色遮罩和中文译文，中文译文是 PDF 文本对象，可被复制/搜索
- 根据原段落 bounding box 动态缩小译文字号，减少溢出

**验证**：
- `mvn -q -DskipTests package` 通过
- `npm run build` 通过
- 临时 Java 验证程序生成 3 页 PDF，只导出第 2 页，输出 PDF 页数为 1
- 用 PDFBox `PDFTextStripper` 能从输出 PDF 提取到中文译文，证明叠加译文不是扫描图片

**仍未完全解决**：
- 目前是“原页矢量背景 + 白框遮罩 + 可复制中文叠加”，还不是彻底删除英文内容流再替换中文。
- PDFTextStripper 仍可能提取到底层被遮罩的英文（视觉上被遮住，但内容流还在）。要完全满足“仅语言由英文变中文”，后续需要更深入的内容流编辑或引入 PDFMathTranslate/PDF2ZH 类似版面重建方案。
- 公式/表格/图注精细识别仍依赖当前段落提取质量，复杂双栏论文可能还会有错位。

---

### fix: 翻译保护层 + 公式过滤启发式

**问题**：LLM 可能把论文中的缩写、变量、引用编号、单位、公式片段误翻；解析阶段也可能把数学公式当成段落送去翻译，导致公式区域被白框遮罩。

**改动**：
- 新增 `TranslationProtector`
  - 翻译前保护 CNN/LSTM/Transformer/YOLO/PointPillars/VoxelNet/KITTI/Waymo/NuScenes/LiDAR/SLAM/mAP/IoU 等缩写
  - 保护引用编号 `[12]`、公式编号 `(3)`、变量样式 `x_i` / `x^2`、百分比和常见单位
  - 翻译后恢复占位符
  - 规范 Figure/Fig. → “图”，Table → “表”
- `LlmService.SYSTEM_PROMPT` 强化：要求保留公式、变量、引用、占位符和指定英文缩写，只输出译文
- `TranslationService` 接入保护层：每段翻译前 protect，翻译后 restore
- `PdfParseService.looksLikeFormula()` 粗略过滤数学符号密集、变量/公式样文本，减少公式被翻译/遮罩

**验证**：
- 临时 Java 验证：`CNN`、`x_i`、`KITTI`、`[12]`、`95%` 可被保护并恢复；`Figure 2` 可规范成“图 2”
- `mvn -q -DskipTests package` 通过
- `npm run build` 通过

**仍未完全解决**：
- 公式过滤还是启发式，复杂公式、多行公式、表格内数学文本仍可能误判。
- 真正高保真方案仍需继续向 PDFMathTranslate/PDF2ZH 的版面分析和内容流级替换靠近。

---

### feat: PDF 论文翻译功能（LLM + SSE + 翻译 PDF 渲染 + 页面范围选择）

**问题**：用户需要在网站中嵌入 PDF 论文翻译功能，上传英文 PDF 后自动翻译为中文，并支持将译文填回原 PDF 输出。

**改动**：
- 后端新增 `translate/` 包：
  - `PdfParseService`：PDFBox 提取文本+坐标，按段落拆分，支持页面范围过滤
  - `PdfRenderService`：翻译 PDF 渲染——原页面渲染为 200 DPI 图片背景，白色遮罩覆盖原文，中文译文绘制在同一位置
  - `LlmService`：调用 Anthropic Messages API 翻译（`mimo-v2.5-pro` 模型），含指数退避重试
  - `TranslationSession`：单次翻译任务的内存状态，支持页面范围
  - `TranslationService`：编排器——预览→页面范围→解析→翻译→SSE 推送
  - `TranslateController`：6 个接口（upload / start / stream / status / download / download-pdf）
- 后端新增 `config/LlmConfig.java`：`@ConfigurationProperties(prefix="llm")` + `RestClient` Bean
- 后端 `pom.xml` 添加 `org.apache.pdfbox:pdfbox:3.0.3`
- 后端 `application.yml` 添加 `llm.*` 配置 + `spring.servlet.multipart` 50MB 限制
- 前端新增 `views/Translate/index.vue`：四态页面（上传/配置/翻译中/结果）
  - 拖拽上传 + 文件选择
  - 配置态：页面范围选择器（起止页码 + 全选按钮）
  - SSE 实时接收翻译进度 + 进度条
  - 双语对照 / 纯中文两种显示模式切换
  - 复制全文 / 下载 TXT / 下载翻译 PDF / 重新翻译
- 前端 `api/index.js` 添加 `uploadPdf` / `startTranslation` / `getTranslationStatus` / `downloadTranslation` / `downloadTranslatedPdf`
- 前端 `router/index.js` 添加 `/translate` 路由
- 前端 `AppHeader.vue` 导航栏添加"翻译"菜单项

**技术方案**：
- PDF 解析用 Apache PDFBox 3.0，`PDFTextStripper` 子类化提取文字坐标
- 翻译 PDF 渲染：`PDFRenderer` 渲染原页面为图片→`PDImageXObject` 作为背景→白色遮罩+中文文字叠加
- LLM 使用 Anthropic Messages API（代理地址 `token-plan-cn.xiaomimimo.com`，模型 `mimo-v2.5-pro`）
- 实时推送用 SSE（`SseEmitter` + `EventSource`），比 WebSocket 简单
- 长段落按句号边界拆分后分批翻译，拼接结果
- 翻译结果存内存（ConcurrentHashMap），不持久化

**踩坑**：
- `application.yml` 不能有两个 `spring:` 顶级 key，YAML 不允许重复 key
- PDFBox 3.0 的 `PDDocument.load()` 改为 `Loader.loadPDF()`
- PDFBox 3.0 颜色值需要 0-1 范围，不是 0-255
- TTC 字体文件（STHeiti、Songti）在 PDFBox 3.0 中无法直接用 `PDType0Font.load()` 加载，改用 `/Library/Fonts/Arial Unicode.ttf`
- Anthropic 代理不认标准 Claude 模型名，需要用 `mimo-v2.5-pro`

**配置**：`.env.local` 中设置 `LLM_API_URL`、`LLM_API_KEY`、`LLM_MODEL`。

---

## 2026-06-01

### feat: GitHub 开源项目后台 + 后端代理 README / 仓库数据

**问题**：原“新闻动态”改成 GitHub 开源项目后，前端最初直接请求 `api.github.com` / `raw.githubusercontent.com`。这和 Zotero 板块“前端只打 `/api`、上游请求交给后端”的架构不一致，也容易遇到浏览器侧限流、CORS、错误提示暴露等问题。

**改动**：
- 前端 `News/index.vue` 改成 GitHub 开源项目页：
  - 卡片展示 repo、说明、stars、forks、语言、topics、更新时间
  - README 按钮 / 标题点击打开 README 弹窗
  - 管理后台支持 `ADMIN_KEY` 登录、添加项目、编辑 GitHub 地址/分类/说明、首页展示、上移/下移排序、删除
- 后端新增 `github/`：
  - `GithubProjectStore`：读写 `.run/github-projects.json`，只保存 `repo/highlight/category/featured`
  - `GithubProjectService`：后端请求 GitHub API 补全 `description/stars/forks/language/topics/default_branch/homepage/pushed_at`
  - `GithubProjectController`：提供 `/api/github-projects`、`/api/github-projects/{owner}/{repo}/readme`、登录和保存接口
- README 读取改为后端代理：尝试 `default_branch/main/master` 和 `README.md/readme.md/README.MD`，前端只拿 markdown 文本后用 `marked + DOMPurify` 渲染
- 首页开源项目预览读取同一份后端配置，按后台保存顺序取前 3 个 `featured=true`
- `project.sh` 后端启动改成 package 后 `java -jar`，并用 `setsid` 脱离 shell，会比 `mvn spring-boot:run` 的 PID 管理稳定
- `ZoteroConfig` / `AdminConfig` 补显式 getter/setter，避免 Maven 打包时 Lombok getter 不可见

**规则**：
- GitHub 和 Zotero 一样，**前端不要直连外部 API**。以后任何 GitHub 数据、README、raw 文件都走后端 `/api/github-projects*`。
- 前端可以负责展示、过滤、Markdown 渲染和后台表单，但外部请求、地址归一化、限流降级都放在后端。

**踩坑**：
- GitHub REST API 的 repo 路径不能把 `owner/repo` 作为单个 `{repo}` URI 变量传给 `/repos/{repo}`，要拆成 `/repos/{owner}/{repo}`。
- GitHub 公共 API 偶尔 403/限流，页面不要全局报黄条；后端拿不到实时字段时保留本地配置基础展示即可。
- README 走 raw 地址比 GitHub README API 更适合做后端代理，但仍要后端尝试多个分支和文件名。

---

### feat: 孤立附件 + markdown 渲染（commit `3a5f281`）

**问题**：用户在 Zotero 里直接拖进 collection 一个 `README.md`，前端不显示。

**两层根因**：
1. **过滤逻辑误伤**：之前假设所有 `attachment` 都是母条目的子项（论文+PDF 模式），处理流程会过滤掉 attachment 类型。但用户直接拖进 collection 的 md / 单文件 pdf，是 `parentItem=null` 的孤立附件，两边都漏。同时还有 2 条单文件 PDF（RDKit.pdf、1-s2.0-S1385894725123899-main.pdf）也被误吞。
2. **Zotero ZIP 打包**：md / 网页快照这类附件，Zotero **服务端会用 ZIP 打包存**（contentType 写的是 `text/plain` 但实际字节是 ZIP，PK 头）。直接代理给浏览器是乱码。

**改动**：
- 后端 `ZoteroCache.processItems`：保留 `parentItem=null` 的 attachment 作为独立条目
- 后端 `ZoteroCache.simplify`：孤立附件把自己挂进自己的 `attachments` 数组（前端"查看 PDF"按钮零改动复用）
- 后端 `ZoteroService.fetchItemFile`：检测 ZIP 头（`PK\003\004`）自动解第一个非目录条目，按文件名后缀推断 content-type（md → text/markdown，html → text/html，pdf → pdf 等）
- 前端 `Publications/index.vue`：
  - 按钮文案随类型变："查看 PDF" / "查看 Markdown" / "查看文件"
  - 装 `marked` + `dompurify`，md 类型 fetch 文本 → marked 渲染 → DOMPurify sanitize → v-html
  - 加 `.md-render` 样式（标题、代码块、表格、引用、链接）
  - `typeMap` 加 `attachment: '附件'` 映射
- 总条目数 47 → 50

**踩坑**：
- ZIP 头检测必须看前 4 字节 `0x50 0x4B 0x03 0x04`，光看 contentType 会被骗（Zotero 那边写的不是 application/zip）
- npm 缓存权限报错 `EACCES: ~/.npm/_cacache/...`，用 `npm install --cache /tmp/npm-cache` 绕过
- DOMPurify 不能省，用户 md 内容如果含 `<script>` 直接 v-html 是 XSS

---

### perf: 缓存层 + 启动预热（commit `b726af8`）

**问题**：直连 Zotero API 16-30 秒，前端每次刷新都白屏十几秒，体验崩。

**测了一下，瓶颈不在我们后端**：
| 链路 | 耗时 |
|---|---|
| 直连 Zotero `/items?limit=200` | 16-30 s |
| 我们后端 `/api/zotero/items`（之前）| 1.3-13 s |
| 后端→前端（同机器）| 2 ms |

慢的是上游，不是我们的处理。Zotero 不支持 streaming，再怎么"流式"也得先等它吐完 JSON。

**方案**：内存缓存 + 启动异步预热 + 每 5min 后台静默刷新。
- 新增 `ZoteroCache.java`（@Component），`AtomicReference<List<Map>>` 存预处理后的数据
- `@PostConstruct` 启动一个 `zotero-cache-warm` 线程拉数据，不阻塞 Spring 启动
- `@Scheduled(fixedDelay = 5min)` 后台刷新
- `BackenApplication` 加 `@EnableScheduling`
- Controller 的 `/items` 和 `/collections` 直接读 cache.getItems() / getCollections()
- 多了 `?refresh=true` 触发后台拉新，当前请求仍秒返
- 响应里加了 `warmedUp` 字段，前端冷启动期间会自动 2s 后重试

**结果**：
- 启动后 3.4s 缓存就绪（用户感知不到，因为前端会重试）
- 之后所有请求 ~2ms（提速 600-15000 倍）
- 5min 后台刷新，用户永远命中热缓存

**为什么不用 Redis**：47 条数据几十 KB，内存里 List 就够。引入 Redis 是过度工程。

**没做的**：服务端 ETag。当前缓存已经够快了，加 ETag 收益微乎其微，先不做。

---

### feat: 分页懒加载 + PDF 按需打开 + 引用导出（commit `77e18dc`）

**问题**：B 分支首版一次性渲染 75 条 + 每张卡片若干字段，DOM 重；如果未来文献多了会更慢。同时用户希望 PDF 不要全量预加载，点了再看。

**改动**：
- 后端 `/items` 重做：返回母条目（过滤掉 `itemType=attachment|note`），把 PDF 等附件按 `parentItem` 反向挂到母条目的 `attachments` 数组。47 条母条目里 16 条带 PDF
- 后端新增：
  - `/api/zotero/file/{key}` PDF 流代理。Zotero 返回 302 重定向到 S3，**Spring RestClient 默认不跟随重定向**，改用 JDK `HttpClient.followRedirects(NORMAL)` 才拿到 PDF 字节
  - `/api/zotero/items/{key}/export?format=bibtex|ris|bibliography&style=apa` 引用导出
    - bibtex/ris：透传 `?format=bibtex|ris`
    - bibliography：要用 `?include=bib&style=apa`，从返回 JSON 取 `.bib` 字段（一开始用 `?format=bibliography` 报 400）
- 前端 `Publications/index.vue`：
  - 渲染分页：`pageCount * 20`，`IntersectionObserver` 滚动到底自动 +1
  - 切 collection / 搜索时重置 pageCount=1
  - 卡片右下两个按钮：「查看 PDF」（有附件才显示）、「导出」下拉
  - PDF iframe 懒加载，再点收起卸载（省内存）
  - APA 导出走剪贴板（HTML 转纯文本）

**踩坑**：
- Zotero PDF 走 S3 重定向，必须自跟随
- `?format=bibliography` 是无效参数，要用 `include=bib`
- naive-ui 的 `useMessage` 必须在 `<n-message-provider>` 内调用，App.vue 已经包了

---

### feat(B): 自建 collection 树 + 卡片列表（commit `b1686dd`、`656cbd9`）

**问题**：和 A 分支并行做对比方案。A 用官方 `zotero-publications` 组件，但样式跟 Naive UI 风格不统一，且组件内部 `groupByCollections` 实际上 throws "not implemented"（README 是空头支票）。

**改动**：
- 后端 `simplify()` 加 `collections` 字段（item 所属的 collection key 数组）
- 前端完全自建：左侧 collection 树（支持嵌套、过滤、计数），右侧卡片列表
- collection 树用扁平化 + depth 字段做缩进（不是真的递归组件，简单粗暴够用）
- 卡片底部显示该 item 所属 collection 名字

**为什么选这条路**：风格可控、能展示 collection 标签、之后好加功能（PDF 内嵌、导出、搜索都比改官方组件容易）。

---

### feat(A): 嵌入官方 zotero-publications + 自建 collection 侧栏（commit `c938948`）

**改动**：把 `zotero-publications` npm 包嵌进来，左侧用我们自己的 collection 列表过滤。

**结论**：保留作为备选分支（`zotero-a-official`），但官方组件样式和我们站点不搭，且导出/详情外的功能扩展性差。

---

### init: Vue3 前端 + Spring Boot 后端 + Zotero 接入（commit `bff6041`）

**做了**：
- 创建 `backen/`（Spring Boot 3 + Java 17）+ `front/`（Vue 3 + Vite + Naive UI）
- `ZoteroConfig` + `ZoteroService` + `ZoteroController` 三件套，调通 Zotero API
- `project.sh` 一键启停（PID 文件、日志、健康检查、`.env.local` 加载）
- 前端 `Publications` 页面初版，路由接入

**踩坑**：
- 包名拼写 `backen`（不是 `backend`），建项目时手滑了，已经牵动太多文件，决定**不改**
- Naive UI 自动导入插件 `unplugin-vue-components` 配好后 NXxx 组件可以直接用，不用手动 import

---

## 状态快照（2026-06-02 03:00）

- `main` ↔ `zotero-b-custom` 同步在 `b726af8`
- `zotero-a-official` 备选，停在 `c938948`
- 后端：50 条母条目 + 4 个 collection，缓存命中 ~2ms
- 前端：分页 20 条 / 批，PDF 内嵌 iframe，三种导出
- 新增：PDF 论文翻译功能（上传→页面范围选择→LLM 翻译→下载翻译 PDF）
- LLM 使用 Anthropic Messages API（`mimo-v2.5-pro` 模型）
- 接口在 `http://localhost:8080/api/*`，前端在 `http://localhost:3000`

下一步可能要做（未排期）：
- [ ] 文献按年份/期刊聚合视图
- [ ] 全文搜索（前端 fuse.js？）
- [ ] 标签云
- [ ] 把首页其他模块的 mock 数据接真实后端
- [ ] 上线部署（Caddy 反代 / Docker compose）
- [ ] 翻译 PDF 字体打包（Noto Sans SC）替代系统字体依赖
- [ ] 翻译结果持久化（H2 数据库）
