# Worklog

按时间倒序，最新在最上。每条对应一个或几个 git commit。

格式：日期 · 一句话总结 · 改了什么 · 为什么 · 踩了什么坑。

---

## 2026-06-15

### chore: 安全移除 OpenClaw 网页对话并准备上线

**目标**：按要求把 OpenClaw 相关入口、接口、配置、脚本和部署依赖全部下线，保留当前站点最小可用功能：文献库、PDF 翻译、PPT 生成和 GitHub 项目展示。

**改动**：
- 删除后端 `OpenClawConfig`、`openclaw/` controller/service、OpenClaw 单测和 `backen/scripts/openclaw_compat.sh`
- 删除前端 `Franchise/index.vue`、`/franchise` / `/openclaw` 路由、OpenClaw API wrapper，并从导航、首页、页脚、关于、工具、样例页移除入口
- 移除 `application.yml` 的 `openclaw.*` 配置、`.env.local.example` 的 `OPENCLAW_*` 模板项、systemd 的 OpenClaw/Node 环境变量和安装脚本里的 OpenClaw Gateway 检查
- 更新 `AGENTS.md`、`MAINTENANCE.md`、README 和部署说明，明确 OpenClaw 已下线，后续不要默认恢复

**验证**：
- `mvn test` 通过：25 tests, 0 failures, 0 errors
- `npm run build` 通过；仅剩既有 Sass deprecation 和 chunk size warning
- `rg "OpenClaw|openclaw|Franchise|franchise|/openclaw|/franchise|OPENCLAW|NODE_COMPILE_CACHE"` 扫描仅剩 `AGENTS.md` / `MAINTENANCE.md` 的下线说明

### fix: 收口服务器部署审查问题

**问题**：部署审查发现发布链路缺少自动回滚和 release manifest，`WORKLOG.md` 不随包同步，Nginx 只对翻译 SSE 关闭 buffering，PPT 外部脚本超时只杀父进程；同时早前“根目录只发布 README”的 `.gitignore` 规则导致 `AGENTS.md / MAINTENANCE.md / WORKLOG.md` 被 staged 删除，直接破坏部署交接资料。

**改动**：
- 取消内部维护文档的 staged 删除，撤回根目录 Markdown 全忽略策略，保留部署审计文档
- 发布包生成 `release-manifest.txt`，记录构建时间、commit、分支、dirty 状态和变更列表；安装时同步 `AGENTS.md / MAINTENANCE.md / WORKLOG.md / release-manifest.txt`
- 一键部署在安装或本机健康检查失败时尝试恢复本次备份，并在退出时清理远端 staging；`REMOTE_RELEASE_KEEP` 至少为 1
- 安装脚本拒绝用 HTTP-only 模板覆盖已有 Certbot/SSL Nginx 配置，并对 Node、OpenClaw Gateway、CJK 字体缺失给出 warning
- Nginx 模板为 `/api/ppt-generate/stream/` 和 `/api/zotero/file/` 增加禁缓冲 location
- 线上保留 Certbot 配置的 `corporate-site` 已手动合并 PPT SSE 与 Zotero 附件流式 location，并通过 `nginx -t` / reload
- PPT 文档解析、Python renderer 和 template-fill 超时后终止整个进程树；BabelDOC Java 默认超时/QPS 与生产文档统一为 21600 秒 / 2 QPS

**验证**：
- 待运行后端单测、前端构建、发布脚本 dry-run 和最小部署审查命令

### chore: 清理本地生成目录的 Git 跟踪规则

**问题**：`.release/npm-cache` 已经误入 Git 索引，且 `.gitignore` 没有覆盖发布包目录、服务器拷贝目录和手工输出目录，后续构建/部署容易继续把缓存和产物带进仓库。

**改动**：
- 根 `.gitignore` 增加 `.release/`、`server-upload/`、`outputs/`
- 从 Git 索引移除已跟踪的 `.release/`、`server-upload/`、`outputs/` 本地产物，保留本机文件
- `AGENTS.md` 补充本地生成目录不得提交的长期维护规则

**验证**：
- `git check-ignore -v` 确认新增目录由根 `.gitignore` 命中
- `git ls-files '.release/**' 'server-upload/**' 'outputs/**'` 确认索引中不再包含这些生成目录

### fix: 附件进度条改为真实字节进度（待用户验证，未部署）

**问题**：首版进度条启用了 Naive UI `processing` 动画，即使浏览器没有收到新字节也会持续滚动；生产 Nginx 默认响应缓冲还可能导致数据成批到达，视觉进度不可信。

**改动**：
- 移除与实际字节无关的进度动画，百分比改为按 `loaded / Content-Length` 保留一位小数计算
- 增加按已接收字节和经过时间计算的实时下载速度
- 附件代理响应增加 `X-Accel-Buffering: no`，要求 Nginx 实时向浏览器转发流

**发布状态**：按用户要求仅完成本地修改和验证，等待用户确认后再部署。

**本地验证**：
- `npm run build`、`mvn -q -Dtest=ZoteroServiceTest test` 和 `git diff --check` 通过
- 本地附件响应确认包含 `X-Accel-Buffering: no`、真实 `Content-Length`
- 本地前后端已运行在 `http://localhost:3000` / `http://localhost:8080`，等待用户手动确认进度表现

### feat: 文献附件预览增加下载进度

**目标**：生产服务器到 Zotero/S3 的附件链路较慢，点击“查看 PDF”后 iframe 会长时间空白，用户无法判断是否仍在下载。

**改动**：
- 文献页改用浏览器 `ReadableStream` 下载 PDF，显示实时百分比、已下载大小和总大小
- 首批数据到达前显示“正在连接附件服务器”，避免进度暂时为零时仍像页面卡死
- 下载完成后创建 Blob URL 打开 iframe 预览；下载中再次点击可取消
- 收起附件或组件卸载时中止请求并释放 Blob URL，避免无效下载和内存泄漏

**验证**：
- `npm run build` 通过；仍有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告
- 本地浏览器点击“查看 PDF”后显示下载状态，完成后以 Blob URL 自动打开 iframe
- 一键部署已完成构建、安装和后端健康检查；服务器内 HTTPS/Nginx 返回 200，线上静态资源包含“正在连接附件服务器 / 正在下载附件 / 取消下载”
- 部署脚本最后的公网检查遇到临时 DNS 解析失败；服务器服务 active，安装结果不受影响

## 2026-06-14

### fix: Zotero PDF 附件代理改为流式转发

**问题**：生产环境文献库打开 PDF 返回 `file proxy error: request timed out`。服务器实测 Zotero/S3 附件链路约 15 KB/s，旧实现用 `BodyHandlers.ofByteArray()` 等完整 PDF 下载完成后才响应，并有 30 秒总超时，因此稳定失败。

**改动**：
- `ZoteroService.fetchItemFile()` 改用 `BodyHandlers.ofInputStream()`，读取前 4 字节识别 ZIP 后直接流式返回
- 普通 PDF/附件不再完整缓冲到 JVM 堆；ZIP 附件继续通过 `ZipInputStream` 解出首个文件并推断 content-type
- `ZoteroController` 改用 `InputStreamResource` 边读边响应，保留 inline、缓存头、上游状态码与普通附件 content-length
- 新增慢速 PDF 首字节返回与 ZIP 流式解包测试

**线上诊断**：
- 后端 `/api/zotero/items` 正常，缓存刷新耗时多为 10-25 秒
- 旧 `/api/zotero/file/{key}` 在服务器精确 30.00 秒返回 502
- 直接从服务器下载约 4.99 MB 测试 PDF：90 秒仅收到 1.33 MB，首字节约 2.5 秒，确认不是 Nginx 或浏览器超时

**验证**：
- `mvn -q -Dtest=ZoteroServiceTest test` 与完整 `mvn -q test` 通过
- 一键部署脚本完成后端测试、前端构建、服务安装及健康检查
- 同一生产附件经后端直连和公网 Nginx 链路均返回 `HTTP 200`，首字节约 2.6-2.9 秒；15 秒内持续传输约 147 KB，不再在 30 秒返回 502
- 生产后端保持 active，验证时 JVM/cgroup 内存约 260 MB

### feat: 切换 Paper Index Studio 视觉并恢复 GitHub 项目入口

**目标**：采用用户选定的第 3 套纸张索引视觉方向，隐藏暂时不用的“项目说明”和“工具模块”，把 GitHub 项目恢复为核心公开模块。

**改动**：
- 首页重做为纸张索引研究工作台，包含当前工作、研究进度、核心工作流和 GitHub 项目四个区块
- 首页通过站内 `/api/github-projects` 展示首个精选项目，失败时使用本地配置回退
- 顶部导航和页脚隐藏 `/about`、`/business`，恢复 `/news` GitHub 项目入口
- 全局主色、背景、边框、阴影、标题字体，以及文献、翻译、PPT 核心工作区切换为暖白纸张、索引红与低饱和绿
- GitHub 项目页同步采用纸张卡片视觉，并把页面主标题调整为 `h1`

**验证**：
- `npm run build` 通过；仍有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告
- `git diff --check -- front/src` 通过

### fix: 修复前端可访问性基础并统一核心工具页首屏结构

**目标**：落实 Product Design 只读审查的第一阶段，先解决键盘/辅助技术访问基础问题，并让文献库、论文翻译、PPT 生成使用同一套工具页首屏结构。

**改动**：
- 新增公共 `.tool-page` / `.tool-page__header` 样式，统一三个核心工具页的标题基线、顶部间距、说明文案和浅色工作区背景
- 文献库移除大面积首屏留白，首屏可同时看到分组、筛选和完整文献卡片；顶级标题改为稳定 `h1`
- PPT 生成页把标题移到共享页壳，并增加“描述需求 → 可选上传资料 → 生成 PPTX”三步说明
- `App.vue` 增加“跳到主要内容”链接和页面级 `main`；公共头部、导航、页脚补齐 `header/nav/footer` landmarks
- Logo、首页工具卡、页脚入口、文献分组、摘要展开、翻译上传区、邮件联系、返回顶部等交互改用原生按钮/链接
- 主题切换、筛选、文件上传、页码范围、字体、素材比例、提示词和邮件表单补充可访问名称；动态翻译/PPT 进度增加 polite live status
- 翻译页所有状态保留同一个 `h1`，文献与 PPT 功能区移除嵌套 `main`
- 浏览器验证截图保存到 `.review/accessibility-tool-shell-2026-06-14/`

**验证**：
- `npm run build` 通过；仍有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告
- `git diff --check -- front/src` 通过
- Chrome 检查 `/publications`、`/translate`、`/contact`：每页均为 1 个 `h1`、1 个 `main`、1 个 `header/nav/footer`，无无名称按钮，无页面级横向溢出，工具页标题顶部位置均为 89px
- 键盘验证：首次 Tab 聚焦“跳到主要内容”，继续 Tab 聚焦带名称的“返回首页”按钮

## 2026-06-12

### chore: 首页工具卡补独立生图并改右下角邮件联系

**目标**：首页核心工具区还在用模板化圆形图标，滚动下来视觉素材不足；右下角悬浮层也不应再是提示聊天，而应能让访问者填写信息并联系到指定邮箱。

**改动**：
- 为首页 6 个工具卡补齐独立 JPG 图片：文献库、PDF 翻译、PPT 生成、OpenClaw、开源项目、后台队列
- 首页核心工具卡从圆形图标改为 4:3 图片卡，保留标题、说明和详情入口
- 移除单图 hero 的旧轮播淡出动画，避免只有一张背景时周期性变暗或空白
- 右下角悬浮按钮改为邮件联系表单，字段包括姓名/称呼、联系方式、主题和留言内容
- 邮件收件人固定为 `1241420431@qq.com`，点击发送时通过 `mailto:` 打开本机邮件客户端，并提供复制邮箱按钮作为兜底
- `AGENTS.md` 补充首页生图资产和邮件联系入口维护规则

**验证**：
- `npm run build` 通过；仍有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告
- in-app Browser 检查首页：6 张工具卡 JPG 均加载完成，旧 `.service-icon` 圆形图标数量为 0
- in-app Browser 检查邮件弹窗：显示 `1241420431@qq.com`，包含四个输入项、复制、取消和发送邮件按钮
- in-app Browser 设置 375x812 手机视口检查首页和邮件弹窗：`scrollWidth=375`，无页面级横向滚动

## 2026-06-11

### chore: 前端去公司模板化并改成研究工具台

**目标**：当前前端仍残留通用公司官网、客户案例、商务外联、在线客服和随机办公图素材，与站点实际的文献、翻译、PPT、OpenClaw 工作流不一致；先统一改造成个人研究工具台语义。

**改动**：
- 使用 imagegen 生成两张项目相关图片，压缩为 JPG 后保存到 `front/src/assets/images/research-workbench-hero.jpg`、`front/src/assets/images/research-pipeline-panel.jpg`
- 首页首屏、核心工具、工作方式和 PPT 入口改为 Zotero 文献、PDF 翻译、PPT 生成、OpenClaw、GitHub 项目、低资源后台队列等内容
- 关于页改为项目说明、系统结构、核心模块、维护重点和工作原则；模块卡改成图标头像，避免重复加载大图
- 业务页和详情页改为工具模块和工具详情；旧案例页、新闻详情、旧 Contact 组件做轻量改写，直接访问时也不再出现无关模板话术
- 头部、页脚、路由标题、SEO title、i18n 和悬浮提示组件统一改成 `Research Desk / 研究工具台`
- `AGENTS.md` 补充前端叙事和本地生成图片资产维护规则

**验证**：
- `rg` 扫描 `front/src`，确认无 `公司/企业/招商/加盟/客户/商务/合作/服务商/高端企业官网/COMPANY/在线客服/报价/联系我们/解决方案` 残留
- `npm run build` 通过；仍有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告
- in-app Browser 检查 `/`、`/about`、`/business`、`/business/3`、`/cases`、`/cases/1`：桌面无旧关键词，首页本地 hero 背景加载，相关本地图片可加载
- in-app Browser 设置 375x812 手机视口检查上述页面：`scrollWidth=375`，无页面级横向滚动

### fix: PDF 翻译文本层乱码修复与下载命名

**问题**：用户上传的 `cm.pdf` 肉眼看是正常英文论文，但 PDF 可复制文本层已损坏，抽取标题变成 `AnlahmWshnm ne Cdmrhsx...` 这类乱码。BabelDOC 读取文本层翻译，导致输出中文明显偏题并夹杂大量未翻译乱码。另外下载文件名固定为“翻译版/双语对照版/翻译结果”，无法对应原上传文件。

**改动**：
- `PdfParseService.analyzeTextQuality()` 在上传预览阶段抽取前几页文本，按英文元音比例和常见词比例识别疑似字体映射/ToUnicode 乱码
- `TranslationSession` 持久化 `textQualitySuspicious/textQualityWarning`，上传、状态、最近任务接口返回该诊断；前端显示 warning，提示用户重点检查预览
- `babeldoc_runner.py` 新增 `RepairingOpenAITranslator`，在送模型前按段落尝试恢复 `sgd -> the`、`qZchbZk -> radical` 这类 ACS 字体偏移文本；只有修复后英文评分明显提高才替换输入，正常 PDF 不受影响
- 下载命名改为以原上传文件名开头：`原名-翻译版.pdf`、`原名-双语对照版.pdf`、`原名-翻译结果.txt`
- `AGENTS.md` 同步记录文本层质量诊断、自动修复和下载命名规则

**验证**：
- `uv run --with pymupdf` 抽取 `cm.pdf` 与 `翻译版-1.pdf` 文本，确认原文文本层本身就是乱码，译文 PDF 是基于乱码源文本生成
- `uv run --with babeldoc python` 验证修复函数可将样本文本 `Sgd bnlodshshnm...` 恢复为 `The competition...`，正常英文不被改写
- `mvn -q -Dtest=TranslationServiceTest test` 通过

## 2026-06-08

### fix: PPT 任务卡在 8% 的服务端解析超时与部署换包问题

**问题**：服务器 `/contact` 提交 PPT 后长期停在 8%。线上排查发现 8% 对应 `extracting` 阶段，任务 `734db6ab` 卡在 `uv run --with docling --with markitdown python ...ppt_document_parser.py --input ...paper.docx`；Java 端先 `readAllBytes()` 再 `waitFor(timeout)`，导致解析子进程不退出时超时保护不会触发。排查过程中还发现部署安装脚本会在服务运行中覆盖 `backen.jar`，Spring Boot 懒加载嵌套 class 时可能抛 `NoClassDefFoundError`。

**改动**：
- `PptInputExtractor.runDocumentParser()` 改为并发读取子进程输出，同时先 `waitFor(timeout)`；解析超时会杀掉子进程并回退到内置 DOCX/PDF 提取
- 文档解析单次最长限制为 45 秒，避免 2 核 / 4 GB 服务器被 Docling/MarkItDown 长时间占住；超过后尽快回退到 Java 内置 DOCX/PDF 提取，避免前端长期停在 8%
- 新增单测覆盖 parser 超时后能快速 fallback 到内置 DOCX 文本提取
- `deploy/install-linux.sh` 改为替换 `backen.jar` 前先停止 `web-backen.service`，再复制新文件并启动服务，避免运行中 jar 被覆盖造成 `NoClassDefFoundError`
- 服务器上手动清理了卡住的 `ppt_document_parser` 子进程；旧任务 `734db6ab` 已在重启后标记为 `error`
- `AGENTS.md` 补充部署换包前必须先停后端服务的规则

**验证**：
- `mvn -q -f backen/pom.xml -Dtest=PptInputExtractorTest test` 通过
- `./deploy/deploy-server-improved.sh` 完成部署，后端健康接口和公网 `https://shimmer.help/` 检查通过

### fix: PPT 自由生成页数差一页时本地兜底补齐

**问题**：`/contact` 上显式要求 25 页时，mimo 首次生成和页数 repair 后仍可能只返回 24 页，后端因此报错 `mimo 返回的 PPT 页数不符合要求: required=25, actual=24`。

**改动**：
- 自由生成 deck 在 LLM 页数 repair 后仍不匹配时，增加确定性本地兜底：少页就在致谢页前插入 `localSlideCountFallback` 补充页，多页则优先从中间内容页裁剪，保留封面和致谢
- 兜底结果写入 `slideCountAdjustedLocally=true`，方便后续诊断和前端/产物追踪
- 模板上传 fill plan 仍保持页数硬校验，不盲目补页，避免破坏模板槽位与 `source_slide` 约束
- 修复本机无 `setsid` 时 `project.sh start backend` 从一次性 shell 后台启动 Java 不稳定的问题，改用 Python `os.setsid()` 分支写入真实后端 pid
- `AGENTS.md` 同步记录自由生成页数兜底规则

**验证**：
- `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest test` 通过
- `./project.sh restart backend` 后 `/api/ppt-generate/templates` 返回 200
- 服务器反馈确认 `web-backen.service`、`nginx.service` 均为 `active (running)`，`https://shimmer.help/` 返回 200 OK；裸 `http://127.0.0.1/` 返回 403 是未带 `Host: shimmer.help` 命中其它 Nginx 站点，不代表公网异常
- `MAINTENANCE.md` 和 `AGENTS.md` 已补充服务器本机首页检查必须带 Host 的规则

### chore: 折叠前端案例与开源项目入口并移除英文切换

**目标**：案例展示和 GitHub 开源项目两个暂时无用板块先从前端可见入口收起，站点不再提供 EN/中文切换。

**改动**：
- 头部导航移除案例展示、开源项目和语言切换按钮，移动端抽屉同步移除语言切换
- 首页删除客户案例轮播和 GitHub 项目开源区块，同时移除首页对 `/api/github-projects` 的请求
- 页脚快捷导航移除案例展示和开源项目入口，改为保留文献、翻译等当前可用入口
- 前端 i18n 固定中文 fallback，Naive UI locale 固定中文，并删除 `front/src/i18n/en-US.js`
- `AGENTS.md` 补充当前前端入口折叠和固定中文维护规则

**验证**：
- `rg` 检查头部、首页、页脚、入口文件无残留 EN 切换、首页 GitHub 请求、案例/开源入口引用
- `npm run build` 通过；仍有既有 Sass deprecation 和 chunk size 警告

### fix: 答辩 PPT 生成审查修复收口

**问题**：并发 review 子代理指出答辩 PPT 改造仍有几个高风险缺口：默认 50% 提取比例会让 20-30 页答辩候选图不足，vision/fallback 会把普通整页截图默认放过，非答辩请求被 22 页答辩策略误牵引，模板 repair report 会把旧正文 `old_text` 重新暴露给 LLM，`Online result screenshot` 会因为包含 `line` 被误判为装饰图。

**改动**：
- 非答辩、无论文、无显式页数任务不再套用 22 页答辩默认；答辩/论文任务才默认约 22 页，显式页数 repair 后仍不匹配会失败
- 20-30 页答辩任务增加动态候选图下限，默认 50% 提取比例下仍尽量保留 15-25 张候选图；DOCX parser 成功路径复制图片也受 Java 端预算裁剪
- vision 分批失败改为局部 fallback，保留成功批次，manifest 可标记 `partial`；vision 缺项和 fallback 普通 `paper-page-*` 不再默认 `useful=true / importance=3`
- generated visual 单张渲染失败时跳过该页视觉兜底，不拖垮整个任务；只有生成视觉图时 manifest source 改为 `generated`
- template-fill `check-plan` report 移除 `old_text`，只保留长度/容量/槽位信息；新增 `image_edits` 区域校验，拒绝不可填、缺失或带 `rejectReason` 的 region
- strip 模式装饰图识别改为 token 级匹配，修复 `Online` 被 `line` 子串误判；同时清理 layout/master 中明显非装饰的文本、图片、表格和图表，保留 logo/background 等视觉骨架
- 模板 `check-plan` repair 后重新执行页数硬约束，避免容量修复时把 22/25 页计划改成其它页数后静默通过
- 后端模板任务单测新增可解包 PPTX 产物检查，断言旧正文/内容图/图表不再出现且 logo 仍保留；模板 `generated_visual` 也新增断言，确认 PNG、manifest 和 `image_edits` 都能进入模板链路
- `.env.local.example` 补充 `PPT_GENERATION_MAX_EXTRACTED_IMAGES=24` 和 `PPT_GENERATION_MAX_VISION_IMAGES=24`
- `AGENTS.md` 同步记录 partial/generated manifest、保守 fallback、parser 图片预算裁剪、repair 不暴露模板原文等维护规则

**验证**：
- `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest,PptInputExtractorTest test` 通过
- `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/Users/shimmer/Desktop/Web/backen/scripts/ppt-template-fill python3 -m unittest /Users/shimmer/Desktop/Web/backen/scripts/ppt-template-fill/tests/test_strip_source_content.py` 通过
- `python3 -m py_compile backen/scripts/ppt_renderer.py backen/scripts/ppt_document_parser.py backen/scripts/ppt-template-fill/template_fill_pptx.py backen/scripts/ppt-template-fill/template_fill_pptx/*.py backen/scripts/ppt-template-fill/tests/test_strip_source_content.py` 通过

### feat: 前端手机端适配收口

**目标**：让前端在手机宽度下可正常导航和使用核心工具页，避免桌面最小宽度、双列卡片、PDF/README 预览和按钮组造成页面级横向滚动。

**改动**：
- 公共头部新增手机菜单按钮和 Naive UI 抽屉导航；桌面横向菜单在小屏隐藏时仍可进入所有主要路由
- review 后去掉抽屉内重复的 PPT 生成菜单项，保留底部主按钮；同时让业务/案例详情页选中父级导航
- 全局 `.container` 移除 1200px 桌面最小宽度，补充媒体、表单控件和移动端 section/container 基础规则
- `Publications` 手机端改为单列筛选、单列按钮、侧栏全宽折叠，并限制 PDF/Markdown 预览高度和表格横向滚动在组件内部
- `Translate` 手机端收紧配置卡、最近任务、结果按钮组和 PDF 预览高度
- `PptGenerate` 手机端收紧模板/上传卡、进度步骤、最近任务和操作按钮
- review 后修复 PPT 最近任务切换时未关闭旧 SSE/polling 的状态覆盖风险
- `News` 开源项目页手机端收紧卡片头部、指标、README 弹窗和后台管理表单
- `AGENTS.md` 补充移动端正式支持和 375px 无页面级横向滚动要求

**验证**：
- `npm run build` 通过；仅有既有 Sass deprecation 和 chunk size 警告
- in-app Browser 设置 375x812 手机视口，检查 `/`、`/news`、`/publications`、`/translate`、`/contact`、`/franchise`：`scrollWidth=375`，无页面级横向滚动
- 手机头部菜单按钮可打开抽屉，抽屉内包含首页、关于、业务、案例、开源项目、文献、翻译、OpenClaw 和底部 PPT 生成入口
- 本轮浏览器验证覆盖布局、导航和横向滚动；未完整覆盖上传文件、SSE、下载、登录、README 弹窗和 PDF 预览等业务交互链路

### feat: 答辩 PPT 生成改为视觉风格继承与动态配图

**目标**：答辩 PPT 要像人工认真制作的学术汇报：模板只继承视觉风格，正文完全重写；正文页尽量图文结合；20-30 页任务不能只拿少量图片。

**改动**：
- 答辩/论文任务默认按约 22 页规划，提示词要求目录、章节过渡、致谢、学术化表达和 3-6 个要点
- 默认图片提取/视觉候选上限从 8 提到 24，20-30 页答辩任务按 15-25 张候选图做预算
- PDF 页面图采样优先选择包含 `Figure/Fig./图/表/caption`、流程、架构、实验结果、对比、可视化等线索的页面，再用均匀采样补足预算
- 视觉初筛改为每批最多 8 张图片，批量合并 `image-manifest.json`
- fallback manifest 不再把非表格图片全部降成低价值，会按文件名和证据关键词给出保守可用评分
- `visualSpec` / `generated_visual` 支持用 Java2D 生成流程图、结构框图、矩阵/对比图 PNG，论文图片不足时也能补充学术配图
- 上传模板填充默认启用 `--strip-source-content`：清空未替换旧文字，移除模板原正文图片、表格和图表，保留母版、背景、logo、装饰和布局风格
- `--strip-source-content` 会同步删除被移除内容图/旧图表对应的 slide relationships，让旧 media/chart 不再作为可达资源进入输出 PPTX；logo/背景等装饰关系保留
- `compactSlideLibrary()` 不再把模板原文案暴露给 mimo，避免模型复用示例占位内容
- `AGENTS.md` 同步模板只继承视觉风格、动态提图预算、vision 分批和生成视觉兜底规则

**验证**：
- `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest,PptInputExtractorTest test` 通过
- `python3 -m py_compile backen/scripts/ppt_renderer.py backen/scripts/ppt_document_parser.py backen/scripts/ppt-template-fill/template_fill_pptx.py backen/scripts/ppt-template-fill/template_fill_pptx/*.py` 通过
- `mvn -q -f backen/pom.xml test` 通过
- `PYTHONPATH=backen/scripts/ppt-template-fill python3 -m unittest backen/scripts/ppt-template-fill/tests/test_strip_source_content.py` 通过：旧正文图片 `rIdContent`、旧图表 `rIdChart` 被移除且 `ppt/media/content.png`、`ppt/charts/chart1.xml` 不再打包；logo 关系和 `ppt/media/logo.png` 保留

### chore: 部署 uv 检查与 PPT 模板填图文档收口

**问题**：端到端 review 继续指出三个收口点：生产部署脚本去掉 Node/npm 检查后没有补 `uv` 运行态检查，可能健康检查通过但 PPT/BabelDOC 首次运行失败；AGENTS 对“模板也要填论文图片”的说法过于绝对，容易误解为所有 `image_regions` 都可填；此前 smoke 记录写成旧任务目录可复核结果，但实际依赖临时 analyze 输出。

**改动**：
- `deploy/install-linux.sh` 在复制文件和重启服务前增加 `command -v uv` 早失败检查，明确说明 PPT/PDF 的 python-pptx、Pillow、BabelDOC、Docling、MarkItDown 默认都依赖 `uv run`
- `AGENTS.md` 改成“上传模板时应尽量使用论文图片/表格素材”，但只允许填经过 analyzer 和 Java 双重过滤的可填内容区域 `region_id`
- `AGENTS.md` 明确背景、logo、装饰、小图以及 cover/toc/chapter/ending 页图片区域不可填；模板没有可填区域时只替换文字并记录/说明原因
- `AGENTS.md` 补充大页数模板任务分批规划会更慢，SSE/progress 应显示当前批次和总批次

**待主代理或 Java/Python worker 补充验证**：
- 短词装饰匹配、`small_content_image` 与 cover 主视觉保护、Java 二次过滤的最终实现结果和单测命令
- 真实 overlay 测试或短真实链路验证；如引用 smoke，请记录可复核命令和 `/tmp/...` 输出路径，不要只写旧任务目录里的 `slide-library.json` 已更新

### fix: 收紧 PPT 模板图片区域填充风险

**问题**：review 指出模板图片填充还有风险：`template_fill_pptx analyze` 会把所有 `p:pic` 当成可填图片区域，可能把封面背景、目录装饰、校徽/logo、小图标当成论文图片占位；后端兜底补图也可能把论文图塞进这些装饰区域。

**改动**：
- `analyzer.py` 给每个 `image_regions` 增加 `fillable`、`role`、`rejectReason`
- 背景、logo/icon/decor 名称、小图、低面积占比图片会标记为不可填
- cover/toc/chapter/ending 页的图片区域统一标记 `page_type_not_fillable`
- Java 后端二次过滤 image region：必须 `fillable=true`、非装饰/背景/Logo、尺寸足够大，且页面类型允许填图
- `compactSlideLibrary()` 只把可填图片区域暴露给 mimo，减少模型误选
- 视觉 `layoutHint` 白名单补齐 `image-top/evidence-strip`，避免自由生成布局提示被窄白名单吞掉
- 单测改成模型误选 logo 区域时，后端会清理并改用真正内容图框
- `AGENTS.md` 补充模板图片区域保守筛选规则

**验证**：
- `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest test` 通过
- `python3 -m py_compile backen/scripts/ppt-template-fill/template_fill_pptx.py backen/scripts/ppt-template-fill/template_fill_pptx/*.py` 通过
- 真实模板 analyze smoke 曾用临时输出观察到 24 页、28 个图片区域中仅 1 个 `fillable=true`，封面/目录/结尾页大图均标记 `page_type_not_fillable`；该记录缺少可复核 `/tmp/...` 输出路径，后续真实链路验证需要补具体命令和产物路径

### fix: PPT 上传模板任务确认走原生填充并分批规划

**问题**：用户下载的 `AI生成PPT-d0a868f4.pptx` 上传了模板但几乎没有模板样式。排查任务目录发现有 `template.pptx / style.json / output.pptx`，但没有 `slide-library.json / fill-plan.json / fill-check-report.json`，说明当时运行的是旧 jar/free renderer 路径，根本没进入新 template-fill 链路。用新 jar 复现后又发现 mimo 一次性输出 25 页 fill plan 时容易长时间卡住或返回坏 JSON。

**改动**：
- `project.sh` 后端新鲜度检查从只看 `backen/src` 扩展到同时看 `backen/scripts` 下 Java/XML/YAML/Python/JSON，避免 template-fill 或 renderer 脚本更新后跳过重打包继续跑旧 jar
- 后端上传模板分支增加明确日志：有模板时打印“PPT 生成使用上传模板原生填充链路”，无模板时打印自由 renderer 链路
- 模板 fill plan 解析失败时自动落盘原始坏响应，并用紧凑 JSON prompt 重试，覆盖 `Unexpected character`、冒号/逗号缺失、截断等常见 mimo JSON 问题
- 模板规划输入做瘦身：论文文本压缩到关键片段，slide library 只给主要文本槽和大图片区域，减少 prompt 和输出体积
- 目标页数超过 8 页时，fill plan 改为约 6 页一批请求 mimo，再合并成最终 `fill-plan.json`，让 25 页任务不再赌一次超长 JSON
- `AGENTS.md` 补充上传模板分批规划、坏 JSON 重试和脚本更新必须重打包的维护规则

**验证**：
- 使用同一模板 `.run/ppt-generation-tasks/d0a868f4/template.pptx` 和论文 `.run/ppt-generation-tasks/d0a868f4/paper.docx` 重新生成 `6d45ef9a`
- 后端日志确认进入上传模板原生填充链路
- 任务目录生成 `slide-library.json`、`fill-plan.json`、`fill-check-report.json`、`output.pptx`
- `fill-check-report.json` 汇总 `error=0`
- 输出 `.run/ppt-generation-tasks/6d45ef9a/output.pptx` 为 25 页，PPTX 包含 4 个 slide master、32 个 slide layout 和 18 个 media

### feat: PPT 模板填充支持论文图片，自由版式增加节奏

**问题**：上传模板路径已经能原生替换文字，但还没有把论文图片/表格填进模板；无模板自由生成的图文页也过于固定，容易连续出现同一种右图/满图排布。

**改动**：
- `template_fill_pptx analyze` 新增 `image_regions`，把模板 PPTX 原有图片区域及 geometry 写入 `slide-library.json`
- mimo fill plan schema 增加 `image_edits`，可引用 `imageManifest` 中的真实 `image_id` 和模板里的 `region_id`
- 后端新增模板图片分配与校验：只允许 `useful=true && importance>=3` 的素材进入模板；模型漏分配时，按结果/方法/数据页优先做受控补图
- template-fill 生成 PPTX 后，后端复用 `PPT_GENERATION_RENDERER_COMMAND` 的 `python-pptx + pillow` 环境，把图片等比 contain 到模板图片区域
- 自由生成 prompt 增加版式节奏约束，支持 `image-top / evidence-strip / metric-hero / comparison / matrix / timeline`
- `ppt_renderer.py` 新增上述布局，并把图片放置改为等比 contain，避免拉伸变形
- `AGENTS.md` 补充“上传模板也要填论文图片”和“自由版式要有节奏”的维护规则

**验证**：
- `python3 -m py_compile backen/scripts/ppt_renderer.py backen/scripts/ppt-template-fill/template_fill_pptx.py backen/scripts/ppt-template-fill/template_fill_pptx/*.py backen/scripts/ppt-template-fill/svg_to_pptx/pptx_notes.py` 通过
- `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest test` 通过
- 真实模板 analyze smoke 识别 24 页、28 个 `image_regions`
- 真实模板 overlay smoke 生成 `/tmp/web-template-overlay-final.pptx`，PPTX media 数量增加到 9
- 自由 renderer layout smoke 生成 `/tmp/web-ppt-renderer-layout-smoke.pptx`

### feat: PPT 上传模板改走原生 template-fill 链路

**目标**：上传 PPTX 模板后真正复用模板页、槽位和 OOXML 元素，不再只把模板当弱配色/背景参考，也不恢复前端大纲确认流程。

**改动**：
- 后端上传模板分支改为 `analyze -> mimo fill plan -> check-plan -> apply`：先生成 `slide-library.json`，再让 mimo 输出 `template_fill_pptx_plan.v1`，检查槽位/容量后原生填充模板 PPTX
- 无上传模板时继续走 `backen/scripts/ppt_renderer.py` 的 `python-pptx` 自由生成 fallback
- 新增 `backen/scripts/ppt-template-fill/`，最小化引入 PPT Master 的 `template_fill_pptx` 脚本能力
- `PptGenerationConfig` / `application.yml` / `.env.local.example` 增加 `PPT_GENERATION_TEMPLATE_FILL_COMMAND` 和 `PPT_GENERATION_TEMPLATE_FILL_SCRIPT`
- 新增单测覆盖上传模板必须走原生 template-fill，且不能误走普通 renderer
- `AGENTS.md` 同步上传模板原生填充、无模板自由生成和部署依赖规则

**验证**：
- `python3 -m py_compile backen/scripts/ppt-template-fill/template_fill_pptx.py backen/scripts/ppt-template-fill/template_fill_pptx/*.py backen/scripts/ppt-template-fill/svg_to_pptx/pptx_notes.py` 通过
- `mvn -q -f backen/pom.xml -Dtest=PptGenerationServiceTest test` 通过
- `mvn -q -f backen/pom.xml test` 通过
- `PYTHONPATH=backen/scripts/ppt-template-fill uv run --with python-pptx python backen/scripts/ppt-template-fill/template_fill_pptx.py apply ... --transition keep` 通过，生成 `/private/tmp/web-template-fill-smoke_20260608_032855.pptx`

### research: PPT 上传模板路径评估 ppt-master / presenton

**目标**：用户反馈上传模板生成效果差、几乎没用上模板，且 PPT 规划提示词一般；需要找 GitHub 上更成熟的开源方案，验证是否能改善“模板复用”和“规划质量”。

**结论**：
- `hugohe3/ppt-master` 更适合作为当前项目的模板路径底座：它的 `template_fill_pptx` 能把 PPTX 分析成 slide library（页类型、文本槽、geometry、font metrics、表格和图表），再用 fill plan clone 原 slide 并替换 OOXML 文本
- `presenton/presenton` 更适合作为完整自托管 AI PPT 产品/API 参考；它支持 custom LLM 和 API，但自定义模板偏 HTML/Tailwind layout，不是直接原生复用上传 PPTX 母版

**验证**：
- 用本地 `2fc988b2/template.pptx` 跑 `ppt-master` analyze，成功识别 24 页模板和文本槽
- `check-plan` 成功拦截错误 slot id 和超容量文本
- 修正后生成 `.review/evals/ppt-master-template-fill/exports/manual-smoke_20260608_031554.pptx`
- 解压 XML 确认替换文本 `化合物活性预测研究`、`计算机科学 李泽轩` 已写入 PPTX

**下一步建议**：
- 上传 PPTX 模板时优先走 `template_fill_pptx` 式原生填充链路：analyze → mimo fill plan → check-plan → apply
- 无模板时保留当前 `python-pptx` renderer 作为 free-design fallback
- 把当前 deck JSON prompt 替换成 layout-first strategist prompt，让 mimo 选择模板页、slot 替换、图片证据和讲稿，而不是直接规划自由版式

### fix: PPT 图片初筛记录模型来源并阻断低质图绕过

**问题**：图片初筛实际默认走 `mimo-v2.5`，但 `image-manifest.json` 没有明确记录本次是视觉模型成功还是 fallback 兜底；同时内部 PPT 结构如果直接填了低价值 `imageId`，渲染前只会给未分配图片补位，不会硬清理这个坏引用。

**改动**：
- `image-manifest.json` 顶层新增 `source`、`model`、`fallbackReason`，可直接判断 `source=vision` 是否代表 mimo 视觉初筛生效
- 视觉调用成功时写入当前 `PPT_GENERATION_VISION_MODEL`，默认 `mimo-v2.5`；无图片写 `source=none`，调用失败写 `source=fallback`
- `ensureImageAssignments()` 渲染前清理不在 `useful=true && importance>=3` 候选集里的 `imageId`，避免低质图、无效 ID 或 fallback 低分素材绕过初筛进入 PPTX
- `AGENTS.md` 补充 PPT 图片初筛来源字段和渲染前硬过滤维护规则

**验证**：
- `mvn -q -f backen/pom.xml test` 通过

### fix: 清理旧版 PPT 大纲待确认状态

**问题**：PPT 生成已经改成一段式直出，但历史任务 `2fc988b2` 仍落盘为旧版 `outline_ready/progressStage=outline_ready`，后端恢复任务时只处理 `queued/generating/rendering`，导致前端最近任务仍可能卡在“大纲待确认”。

**改动**：
- `PptGenerationService.loadRecentSessions()` 改为历史恢复时只把 `completed/error` 当作终态，其余所有非终态都标记为 `error`
- `AGENTS.md` 补充旧版 `outline_ready/outlining/revising` 状态不得继续进入前端流程

**验证**：
- `mvn -q -f backen/pom.xml test` 通过

## 2026-06-07

### feat: PPT 生成放弃 PptxGenJS，改为 python-pptx 直出

**目标**：完全去掉 PptxGenJS 和“先大纲、再确认、再渲染”的两段式流程，用户提交后后台直接生成 PPTX。

**改动**：
- 新增 `backen/scripts/ppt_renderer.py`，使用 `python-pptx` 直接写出 `.pptx`
- 删除 `backen/scripts/pptx-generator/` 旧 Node/PptxGenJS runner 和 npm 依赖链
- `PptGenerationService` 改为单 worker 一段式：抽取资料 → mimo 生成内部 PPT 结构 → 图片分配 → Python renderer 直出 PPTX → `done`
- 删除后端 `/deck/{taskId}`、`/revise/{taskId}`、`/render/{taskId}` 接口；SSE 不再发送 `outline-ready`
- 前端 `PptGenerate/index.vue` 删除大纲编辑器、对话修改、预览和二次渲染，只保留提交、进度、完成下载和最近任务
- 配置改为 `PPT_GENERATION_RENDERER_COMMAND` / `PPT_GENERATION_RENDERER_SCRIPT`，启动和部署脚本不再安装 Node runner 依赖
- `AGENTS.md` 同步为当前一段式直出架构

**验证**：
- `python3 -m py_compile backen/scripts/ppt_renderer.py backen/scripts/ppt_document_parser.py` 通过
- `uv run --with python-pptx --with pillow python backen/scripts/ppt_renderer.py ...` smoke 生成 3 页 PPTX 通过
- `mvn -q -f backen/pom.xml test` 通过
- `cd front && npm run build` 通过（仍有既有 Sass deprecation / chunk size 警告）

### chore: 清理 PPT 论文解析脚本无用代码

**改动**：
- 删除 `ppt_document_parser.py` 中未使用的 `work_dir` 参数、`tempfile` import 和未被 Java 消费的 `output` 空字段
- 删除 parser 结果里未被消费的图片清单、base64 图片数据和对应 helper，只保留 DOCX 原图复制
- `AGENTS.md` 补充解析脚本输出字段必须有消费方的维护约束

**验证**：
- `python3 -m py_compile backen/scripts/ppt_document_parser.py` 通过
- `mvn -q -f backen/pom.xml test` 通过

### feat: PPT 输入层切换为 Docling / MarkItDown 解析底座

**目标**：把 PPT 生成的文档理解层从自研零散抽取，切到更强的外部解析底座，优先用 Docling，失败回退 MarkItDown，再回到旧 PDFBox / ZIP 逻辑，保留现有 PptxGenJS 渲染链和 mimo 决策层。

**改动**：
- 新增 `backen/scripts/ppt_document_parser.py`，作为论文解析中间层，输出结构化 Markdown/JSON 和图片清单
- `PptInputExtractor` 现在优先调用新的解析脚本，再回退旧 PDFBox / DOCX 抽取逻辑
- `PptGenerationConfig` / `application.yml` / `.env.local.example` 增加 `PPT_GENERATION_PAPER_PARSER_COMMAND` 和 `PPT_GENERATION_PAPER_PARSER_SCRIPT`
- `AGENTS.md` 补充新的输入层职责边界，明确解析和渲染分离

**说明**：
- 这里没有把成品生成器换掉，仍然保留现有 PptxGenJS 渲染链；这次主要是把“论文怎么读懂”换成更强的底座
- 图片审查仍留在后端，优先启发式过滤，再让 mimo 复核，不把高成本视觉判断全部压给 LLM

**验证**：
- 本轮先完成接线和文档更新，后续需要跑 `mvn test` 和前端构建确认无回归
### fix: PPT 模板扫描被文字样本上限截断，强保真模板误判

**问题**：复查任务 `173c7f74` 的模板产物后发现，`style.json` 里的 `templateAnalysis` 只有前 2 页，`templateFramework` 也只记录了 2 页骨架。根因不是模板本身太弱，而是 `PptInputExtractor.scanTemplate()` 先用 `samples.size() < 12` 截断了整段 slide 扫描，导致后续 22 页完全没进入模板分析；结果 `template-fill` 仍能跑，但实质上是在拿一个“只看了前两页”的模板做强保真判断。

**改动**：
- `PptInputExtractor.scanTemplate()` 改为遍历全部 slide，文字样本只限制保存数量，不再限制是否继续分析后续页
- `style.json` 新增 `templateFit` / `templateRoute` / `templateFitReason`，模板分析完成后自动判断强保真、弱模板或回退框架
- `PptGenerationService` 读取 `templateRoute` 后决定最终渲染模式，避免弱模板继续伪装成 `template-fill`
- 前端 `PptGenerate/index.vue` 增加模板健康度提示，直接显示“强保真可用”或“已回退框架复用”
- 新增 `PptInputExtractorTest.scansAllTemplateSlidesAndInfersFitBeyondTheFirstTwoPages()`，专门防止模板扫描再次被前几页截断

**验证**：
- `cd backen/scripts/pptx-generator && npm run smoke:template-fill` 通过
- `cd front && npm run build` 通过
- `mvn -q -f backen/pom.xml test` 本轮因测试编译问题中断，已修复缺失 `JsonNode` import，待重新跑完整回归
### feat: PPT 模板理解层和母版图片直填

**目标**：用户上传 PPTX 模板后，不只是“像模板”，而是要真正读懂模板骨架，并能从论文里自动抽图、抽表格，按模板槽位把证据填回去。

**改动**：
- `PptInputExtractor` 扫描模板时新增 `templateAnalysis`，记录文本槽、图片槽、页角色和文字样本，和 `templateFramework` 一起写入 `style.json`
- `PptGenerationService` 在大纲、修订、读取和渲染结果里补齐 `templateAnalysis`、`templateFramework`、`evidenceCandidates` 和 `slotAssignments`
- 前端 `PptGenerate/index.vue` 增加模板理解、证据命中、槽位分配三个预览面板，方便直接看模板是否被解析对了
- `generate_deck.mjs` 的 `template-fill` 逻辑升级为按模板分析和槽位分配优先替换图片关系，而不是只替换文本；对缺失图片、异常图片和关系顺序差异增加了容错
- 重新补回 `backen/scripts/pptx-generator/template_fill_smoke.mjs`，用最小可重复样本验证 base 生成、template-fill、图片关系替换、克隆页关系清理和媒体字节一致性
- `AGENTS.md` 增加模板理解层、证据层和母版图片替换的长期规则

**验证**：
- `cd backen/scripts/pptx-generator && npm run smoke:template-fill` 通过
- `node --check backen/scripts/pptx-generator/generate_deck.mjs` 通过
- `mvn -q -f backen/pom.xml test` 通过
- `cd front && npm run build` 通过

## 2026-06-07

### fix: DOCX 论文原图被表格配额挤占，成品无论文素材

**问题**：任务 `c599ab24` 的 DOCX 内有 16 张 `word/media` 图片，但抽取目录只有 8 张 `paper-table-*`。原实现让 DOCX 表格截图、嵌入 Excel 和原始图片共用同一个递增计数；`word/document.xml` 先被处理并把 8 张配额占满，后续 `word/media` 全部被跳过。视觉摘要又把这些表格全部标记为 `useful=false`，最终 deck 没有任何 `imageId`，成品只剩模板装饰图。

**改动**：
- `PptInputExtractor.extractDocxTextAndImages()` 改为两遍读取 DOCX ZIP：
  - 第一遍抽正文、收集表格候选并统计真实媒体数量
  - 第二遍优先抽取约 60% 配额的 `word/media` 图片，再用剩余额度渲染表格
- 真实论文图片与表格仍共享总预算，不增加 2 核 / 4 GB 生产机的素材上限
- `normalizeImageManifest()` 对已成功渲染的 `paper-table-*` / `paper-excel-*` 设置最低可用等级，防止视觉摘要把所有表格清零
- 大纲生成后和每次渲染前都会检查图片分配；对尚未使用的高价值素材，受控分配到方法、数据、实验、结果等语义相关页
- 素材缓存版本升级到 3，已有任务下一次渲染会强制重新抽取，避免继续复用缺图缓存
- 新增 `PptInputExtractorTest`，构造包含 10 张图和 10 个表格的 DOCX，验证 8 张总预算内同时保留论文原图与表格

**任务修复**：
- 用新逻辑重新渲染 `c599ab24`
- 抽取结果从“8 张表格、0 张论文图”变为“5 张论文图、3 张表格”
- 自动向 6 个相关页面分配论文素材
- 已覆盖 `/Users/shimmer/Downloads/AI生成PPT-c599ab24.pptx`

**验证**：
- `cd backen && mvn test` 通过
- `cd front && npm run build` 通过
- `cd backen/scripts/pptx-generator && npm run smoke && npm run smoke:template-fill` 通过

### fix: PPT 任务隔离、队列状态机和母版扩页关系

**问题**：代码审查发现 PPT 公开入口存在四类风险：只凭 `taskId` 即可读取或篡改其他任务；对话修改绕过单 worker 并发调用 LLM；队列满或重复渲染会污染 session/deck 状态；母版填充扩页原样复制 `.rels`，导致多页引用同一个 notesSlide。

**改动**：
- 每个 PPT 任务创建 256-bit 随机 `accessToken` 并落盘；创建接口仅向创建者返回一次，前端存入当前标签页 `sessionStorage`
- 状态、大纲、保存、修改、渲染接口校验 `X-Ppt-Task-Token`；SSE 和下载因浏览器 API 限制使用 `accessToken` 查询参数
- `/recent` 改为按 `X-Ppt-Task-Tokens` 过滤，只返回当前浏览器持有令牌的任务，不再公开全站任务
- 对话修改进入与大纲/PPTX 相同的有界单 worker；同一任务处理中拒绝保存、修改和重复渲染
- 渲染入队前保存旧状态和旧 deck；队列拒绝或写盘失败时完整回滚，避免任务永久停在 `queued`
- `template-fill` 扩页改用未占用的 slide 编号，并移除克隆页的 notes/comments 单页唯一关系，避免备注串页和 PowerPoint 修复提示
- 新增 `PptGenerationServiceTest`，覆盖任务令牌隔离、最近任务过滤、队列满回滚、重复操作与 revise 队列拒绝
- 新增 `npm run smoke:template-fill`，验证母版扩展到 7 页后克隆页不保留 notes/comments 关系

**兼容性**：
- 后端首次加载没有令牌的旧 PPT 任务时会生成新令牌；由于旧浏览器从未持有这些令牌，旧任务不会继续出现在公开最近任务中，这是安全迁移的预期行为。

**验证**：
- `cd backen && mvn test` 通过
- `cd front && npm run build` 通过；仅有既有 Sass deprecation 和 chunk size 警告
- `cd backen/scripts/pptx-generator && npm run smoke && npm run smoke:template-fill` 通过
- `unzip -t /tmp/web-pptx-template-fill-smoke.pptx` 通过

### fix: PPT 重新生成复用素材、母版填充缩字和表格抽图

**问题**：同一个大纲反复点“重新生成 PPTX”时仍重新读取论文/模板，进度体验像又在生成大纲；母版填充把长文本和 notes 直接塞进模板文本框，容易字号过大、元素错位；DOCX 表格和嵌入 Excel 表格没有作为图片素材进入 PPT 链路。

**改动**：
- 新增 `asset-cache.json` 素材指纹，按论文文件、模板文件、提取比例和模板模式判断素材是否可复用；未变化时重新生成 PPTX 只重跑 runner，不再重新抽取
- 进度阶段新增 `reusing_assets` / `refreshing_assets`，前端显示“复用素材”或“刷新素材”，避免误显示为“读取论文”
- DOCX `<w:tbl>` 表格解析成行列并渲染为 `paper-table-*.png`
- DOCX 内嵌 `word/embeddings/*.xlsx` 读取第一个 worksheet/sharedStrings，渲染为 `paper-excel-*.png`
- 表格图片 fallback manifest 标记为 `kind=table/useful=true/importance=4/layoutHint=full-image`
- `template-fill` 不再把 notes 写到模板形状里；当新文本明显长于原占位文本时，runner 会压缩对应 run 字号
- runner 读取模板素材数量从 4 个同步到 24 个，匹配后端抽取上限

**验证**：
- `cd backen && mvn -q test` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告
- `node --check backen/scripts/pptx-generator/generate_deck.mjs` 通过

### fix: PPT 大纲 JSON 被截断时自动重试

**问题**：用户生成 25 页答辩 PPT 时，mimo 返回的 deck JSON 在 `slides` 数组中途被截断，后端 Jackson 报 `Unexpected end-of-input: expected close marker for Array`。根因是大纲生成固定使用 `PPT_GENERATION_LLM_MAX_TOKENS=8192`，长页数 + notes/bullets 容易把 JSON 尾部截掉。

**改动**：
- PPT 大纲默认输出上限从 8192 提高到 16384，并按目标页数动态估算 token，最高 32768
- `buildDeckJson()` 解析失败时识别 `Unexpected end-of-input` / `expected close marker` / 未闭合括号等截断特征
- 截断后自动用紧凑 JSON prompt 重试，限制每页 bullet 和 notes 长度，优先返回完整可解析 JSON
- 页数纠偏 prompt 也复用动态 token 预算
- `.env.local.example`、`AGENTS.md` 同步记录新的默认值和截断重试策略

**验证**：
- `cd backen && mvn -q test` 通过

### fix: 生成 PPTX 时保留模板模式与提取比例

**问题**：用户指出“大纲确认后点击生成 PPT”可能覆盖之前选择。排查后确认 `/api/ppt-generate/render/{taskId}` 旧接口只接收 deck JSON，不会重新携带 `templateMode/extractionPercent`；旧任务或缺字段任务在渲染阶段可能退回默认 `framework`，导致上传模板后仍走普通重绘。

**改动**：
- `render/{taskId}` 请求体兼容旧 deck，也支持新结构 `{ deck, templateMode, extractionPercent }`
- `PptGenerationService.renderTask()` 在渲染前写回 `templateMode/extractionPercent` 到 session metadata，并继续校验 `template-fill` 必须有上传模板
- 前端 `renderGeneratedPpt()` 发送 deck 的同时显式带上当前 `templateMode/extractionPercent`
- 打开最近任务时，如果旧任务有 `templateFileName` 但没有 `templateMode`，前端默认显示为 `template-fill`

**验证**：
- `cd backen && mvn -q test` 通过
- `node --check backen/scripts/pptx-generator/generate_deck.mjs` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告

### fix: 上传模板后默认进入母版填充

**问题**：用户反馈任务 `ad548411` 选了模板模式但成品不像上传模板，且 `task.json` 缺少 `templateMode/extractionPercent`、`style.json` 缺少 `frameworkMode/templateFramework`，说明该任务仍走旧的 PptxGenJS 重绘链路，没有进入母版填充分支。

**改动**：
- 前端 `handleTemplateSelect()` 改为选择 `.pptx` 后自动切到 `template-fill`，用户仍可手动切回 `framework`
- 手动用当前 runner 将 `.run/ppt-generation-tasks/ad548411/deck.json` 填充到 `.run/ppt-generation-tasks/ad548411/template.pptx`，重新生成 `.run/ppt-generation-tasks/ad548411/output.pptx`
- 同步覆盖 `/Users/shimmer/Downloads/AI生成PPT-ad548411.pptx`
- 补写 `ad548411/task.json` 的 `templateMode=template-fill` 和 `extractionPercent=50`
- `AGENTS.md` 补充：上传模板默认母版填充，避免“模板模式”默认走普通重绘

**验证**：
- `node backen/scripts/pptx-generator/generate_deck.mjs --deck .run/ppt-generation-tasks/ad548411/deck.json --style .run/ppt-generation-tasks/ad548411/style.json --images .run/ppt-generation-tasks/ad548411/images.json --manifest .run/ppt-generation-tasks/ad548411/image-manifest.json --mode template-fill --template .run/ppt-generation-tasks/ad548411/template.pptx --out .run/ppt-generation-tasks/ad548411/output.pptx` 通过
- `unzip -t .run/ppt-generation-tasks/ad548411/output.pptx` 通过
- 对比结构：模板 24 页、14 个媒体、16 个 layout、2 个 master；输出 25 页、14 个媒体、16 个 layout、2 个 master，说明输出保留了原模板结构并只额外复制了一页
- `cd front && npm run build` 通过；仅有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告

### feat: PPT 模板母版填充与提取比例控制

**目标**：用户上传 PPTX 模板时，支持完全在模板 slide 上填充替换；同时让用户自己控制 PDF/论文/模板素材提取比例，减少“模板元素提太少”和“私自缩放模板元素”的问题。

**改动**：
- 前端 `PptGenerate/index.vue` 新增“模板复用方式”：`framework` 框架复用 / `template-fill` 母版填充；母版填充必须先上传 PPTX 模板
- 前端新增 `extractionPercent` 滑块，范围 10-100，提交到 `/api/ppt-generate/tasks`
- `PptGenerationSession` 和 Controller summary 新增 `templateMode`、`extractionPercent`
- `PptInputExtractor` 根据提取比例控制 PDF 页面图数量、DOCX 图片数量、PPTX framework 页数和模板媒体素材数量；PDF 页面图改为整篇均匀采样，不再只渲染前 N 页；PPTX 模板媒体素材上限提高到 24 个
- PDF 表格通过页面图进入图片 manifest；DOCX 表格文本会追加为 `[表格 n]` 上下文，避免只抽正文丢表格信息
- Node runner 新增 `template-fill` 模式：直接读取上传模板 PPTX zip，用 deck JSON 替换 slide XML 中的 `<a:t>` 文本，保留原 shape/media/位置/尺寸；页数不足时复制最后一页模板 slide，页数过多时只让 presentation 引用所需页
- runner 显式依赖 `jszip`，`package-lock.json` 已更新
- `AGENTS.md` 补充母版填充、提取比例和资源边界说明

**边界**：
- `template-fill` 当前以文本替换为主，不做图片占位符智能替换；这样能保留模板元素，不再把模板元素抽出后缩小/放大重排
- PDF 表格“转图片”当前通过页面级 96 DPI PNG 实现，能保留表格视觉，但还不是单独裁剪表格区域

**验证**：
- `cd backen && mvn -q test` 通过
- `cd backen/scripts/pptx-generator && npm run smoke` 通过
- `node --check backen/scripts/pptx-generator/generate_deck.mjs` 通过
- `node generate_deck.mjs --deck fixtures/sample-deck.json --style fixtures/sample-style.json --images fixtures/sample-images.json --manifest fixtures/sample-images.json --mode template-fill --template /tmp/web-pptx-generator-smoke.pptx --out /tmp/web-pptx-template-fill-smoke.pptx` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告

### feat: 上传 PPTX 模板时复用模板 framework

**目标**：用户提供 PPTX 模板时，不应该只提取配色或素材，而是尽量复用模板的页面骨架、装饰节奏和版式框架。

**改动**：
- `PptInputExtractor.scanTemplate()` 增加 framework 扫描：最多读取 16 页 slide XML，记录页角色、文本块数、图片数、表格数和少量文字样本
- 上传模板媒体素材复制上限从 4 个提高到 8 个，仍保持低内存、落盘处理
- `PptGenerationService` prompt 告诉 mimo：`templateStyle.frameworkMode=true` 时要按 `templateFramework` 映射 cover/contents/section/content/image/conclusion/thanks 节奏
- `generate_deck.mjs` 新增模板框架装饰层：封面/章节/致谢可复用宽幅模板素材作等比 cover 背景，内容页复用边栏、角标、分栏和模板素材节奏
- 前端上传模板文案改为“复用模板框架和素材，配色仍跟随所选通用模板”
- `AGENTS.md` 补充 framework mode 边界：复用框架感但不承诺高保真母版复制，生成文本和图形仍保持可编辑

**验证**：
- `cd backen && mvn -q test` 通过
- `cd backen/scripts/pptx-generator && npm run smoke` 通过
- `node --check backen/scripts/pptx-generator/generate_deck.mjs` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告

### fix: PPT 生成锁定配色、收紧配图并按页数压缩

**问题**：用户反馈 PPT 生成仍有三类质量问题：成品配色没有稳定跟随一开始选择的通用模板；图片和正文主题匹配不严，出现文不对图和乱放图；提示词规定页数后，系统倾向于截断前 N 页而不是压缩整份内容。

**改动**：
- `applyBuiltInTemplateStyle()` 改为始终使用用户选择的通用模板 palette，上传 PPTX 只借用素材和文字样本，不再覆盖成品色板
- deck JSON prompt 新增 `targetSlideCount` 和压缩策略：固定页数时必须返回精确页数，并在完整叙事上合并背景、方法、实验、结果、结论，而不是只保留前半部分
- 后端从提示词解析 `12 页`、`10-15页`、`十二页` 等页数表达，传给 mimo 作为结构化约束
- 如果 mimo 首次返回页数仍不匹配，后端会带着原始输入和当前 deck JSON 追加一次纠偏调用，要求精确合并/展开到目标页数
- 视觉摘要失败时的 fallback manifest 不再把论文图片默认标记为可用，避免识图失败后仍随机配图
- Node runner 去掉 `deck.slides.slice(0, 24)` 硬截断，并取消 `imageId` 模糊匹配；只有精确 ID 且 manifest 判定有用的图片才会放入页面
- 前端上传模板文案改为“借用素材和文字样本，配色仍跟随所选通用模板”
- `AGENTS.md` 同步记录：PPT 通用模板锁定配色、固定页数要压缩不要截断

**验证**：
- `cd backen && mvn -q test` 通过
- `cd backen/scripts/pptx-generator && npm run smoke` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API / `darken()` deprecation 和 chunk size 警告

### feat: PPT 生成支持 PDF 页面图作为素材

**目标**：PDF 论文输入不只提供纯文本，也能把页面中的图表、流程图和实验结果截图带入 PPT 生成链路。

**改动**：
- `PptInputExtractor` 在抽取 PDF 文本时同步用 PDFBox `PDFRenderer` 将前几页渲染为 96 DPI PNG
- 渲染页数复用 `PPT_GENERATION_MAX_EXTRACTED_IMAGES` 上限，产物仍落到任务目录 `images/`，继续走现有 image manifest、视觉摘要和 PptxGenJS 图片引用流程
- `PptGenerationService.runRenderTask()` 在每次重新生成 PPTX 前刷新论文图片素材、模板素材和 `image-manifest.json`，避免旧 taskId 只复用旧素材导致用户看不到变化
- 对比 `AI生成PPT-128e19f2(2).pptx` 与人工参考稿 `李泽轩-毕业论文答辩-图文精简版.pptx` 后，确认质量差距不只是图片缺失：模板配色提取把 OOXML 坐标/尺寸误识别为颜色，导致蓝白模板跑偏成棕绿；大纲 section 输出 `1.1/2.3` 这类论文编号；runner 普通内容页自动轮播塞图，破坏答辩稿图文节奏
- 修复 PPTX 模板色彩扫描，只读取真正的 `srgbClr val="xxxxxx"` / `color="xxxxxx"`，避免误把 6 位数字当色值
- 收紧 deck JSON prompt：默认 16-20 页答辩节奏，section 使用 `BACKGROUND/ROUTE/DATA/RESULT/CONCLUSION` 等语义页眉，图片只在作为证据对象时使用
- 调整 Node runner：普通内容页没有 `imageId/imageHint` 或图示版式时不再硬塞图片；数字 section 会降级为语义页眉，避免页面顶部出现 `1.1/2.3`
- 视觉摘要 manifest 增加 `useful`、`importance`、`layoutHint`，让识图阶段筛掉空白、重复、装饰、不可读或低价值图片
- deck JSON prompt 要求只有当页面标题/核心句与 manifest 的 `title/summary/bestUse` 直接对应时才引用 `imageId`，避免文不对图
- Node runner 改为严格使用明确 `imageId`，不再自动轮播图片；放图使用等比 contain 居中计算，不拉伸、不裁切，并按 `layoutHint`、图片宽高比和版式自动选择左图、右图或大图页
- 后端新增 LLM JSON 容错解析：生成大纲、对话修改和视觉摘要先正常解析 JSON；若 mimo 把 JSON 字符串边界误写成中文弯引号 `“...”`，会修复边界引号后重试，避免 `Unexpected character ('“')` 直接中断任务
- `AGENTS.md` 补充 PDF 页图提取的资源边界，提醒后续不要随意提高 DPI 或页数上限

**验证**：
- `cd backen && mvn -q test` 通过
- `cd backen/scripts/pptx-generator && npm run smoke` 通过
- `node --check backen/scripts/pptx-generator/generate_deck.mjs` 通过
- 用 `qlmanage` 对比封面缩略图，修复后蓝白配色已回到参考稿方向；临时验证文件为 `/Users/shimmer/Downloads/AI生成PPT-128e19f2-fixed-blue.pptx`

### feat: `/contact` 替换为 PPT 生成工具页

**目标**：保留 `/contact` 公网 URL，但把旧“联系我们”页面改成公开可用的“PPT 生成”工具；后端参考 `/Users/shimmer/Desktop/论文/pptx-toolkit` 的 PptxGenJS 方案，并纳入本项目发布包。

**改动**：
- 前端新增 `front/src/views/PptGenerate/index.vue`，`/contact` 路由改为 PPT 生成；导航按钮、首页 CTA、首页底部入口、页脚工具入口、业务/案例详情按钮都改为 PPT 生成语义
- 前端支持必填提示词、可选 `.pptx` 模板、可选 `.pdf/.docx` 论文；展示提交、排队/生成中、结果下载、最近任务恢复
- 后端新增 `ppt/` 模块：`PptGenerationController`、`PptGenerationService`、`PptGenerationSession`、`PptInputExtractor`
- 新增接口：`POST /api/ppt-generate/tasks`、`GET /stream/{taskId}`、`GET /status/{taskId}`、`GET /recent`、`GET /download/{taskId}`
- `LlmService` 新增通用 `complete(systemPrompt, userPrompt, maxTokens)`，PPT 生成复用现有 `LLM_*` mimo 配置，不影响论文翻译专用 prompt
- 新增 `backen/scripts/pptx-generator/generate_deck.mjs`，用 PptxGenJS 把 deck JSON、模板风格和论文图片生成可编辑 PPTX
- 新增 `ppt-generation.*` 配置、`.env.local.example` 示例、`project.sh` 本地 runner 依赖安装、部署安装脚本 runner `npm ci`、发布包剔除 runner `node_modules`

**资源边界**：
- 公开入口不加 `ADMIN_KEY`；默认提示词 8000 字，模板/论文各 30 MB，单 worker，队列容量 3，最近任务 5 条
- 任务文件落盘到 `.run/ppt-generation-tasks/`，JVM 只保存轻量元数据
- 后端重启后未完成 PPT 任务标记为 error，需要重新提交；完成结果按最近任务清理策略保留
- 模板 v1 只做风格提取和素材借用，不承诺高保真母版复刻

**验证**：
- `cd backen && mvn -q test` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API 和 chunk size 警告
- `backen/scripts/pptx-generator/generate_deck.mjs` smoke 通过，生成 4 页、约 88 KB 的 `.pptx`

### feat: PPT 生成增加模板、大纲确认、预览和修改闭环

**目标**：PPT 不再“一键直接出成品”，而是先生成大纲供用户调整确认；生成后可继续预览、对话修改和人工编辑，再重新生成 PPTX。

**改动**：
- 新增 `/api/ppt-generate/templates`，提供学术蓝、极简黑白、数据绿、暖色答辩 4 个通用模板；用户上传自定义 PPTX 时仍优先提取上传模板风格
- `/api/ppt-generate/tasks` 改为先生成 deck JSON 大纲，SSE 新增 `outline-ready`
- 新增 `/deck/{taskId}` 读取/保存大纲，`/revise/{taskId}` 对话修改大纲，`/render/{taskId}` 根据确认后的大纲排队生成 PPTX
- 前端 `PptGenerate/index.vue` 改为两阶段工作流：模板选择 + 上传/提示词 → 生成大纲 → 人工编辑/前端预览/对话修改 → 生成或重新生成 PPTX
- 前端预览为 16:9 幻灯片卡片，人工编辑范围是 deck JSON 的页类型、标题、核心句、要点和讲稿备注；不是形状级 PowerPoint 编辑器

**验证**：
- `cd backen && mvn -q test` 通过
- `cd front && npm run build` 通过；仅有既有 Sass legacy API 和 chunk size 警告

### fix: PPT 重复渲染时模板素材已存在导致 70% 失败

**问题**：任务 `254f552e`（论文文件 `李泽轩-毕业论文初稿.docx`，模板 `06.pptx`）在“生成 PPTX”阶段卡到 70% 后失败，错误信息只有 `.run/ppt-generation-tasks/254f552e/template-assets/template-asset-1.png`。日志确认根因是同一任务重新渲染时，`PptInputExtractor.scanTemplate()` 再次 `Files.copy()` 到已存在的 `template-asset-1.png`，触发 `FileAlreadyExistsException`。

**改动**：
- `PptInputExtractor` 在重新抽取 DOCX 图片和 PPTX 模板素材前清理旧抽取目录，并用 `StandardCopyOption.REPLACE_EXISTING` 兜底
- `generate_deck.mjs` 启动时预读图片为 data URI；不可读取或格式异常的图片只记录 warning 并跳过，避免单张素材导致整份 PPTX 写出失败
- 补齐 `backen/scripts/pptx-generator/fixtures/*`，让 `npm run smoke` 不再引用缺失文件

**验证**：
- `cd backen && mvn -q test` 通过
- `cd backen/scripts/pptx-generator && npm run smoke` 通过
- 重新调用 `POST /api/ppt-generate/render/254f552e`，任务从 `rendering 70%` 恢复到 `completed 100%`
- `GET /api/ppt-generate/download/254f552e` 返回 200，生成 `.run/ppt-generation-tasks/254f552e/output.pptx`（约 1.6 MB）

## 2026-06-06

### ops: PDF 翻译服务器保护与部署包清理

**问题**：生产机 2 核 / 4 GB 且无 swap，`666.pdf` 全 18 页 BabelDOC 翻译期间服务器出现重启；后端只限制 JVM 堆，BabelDOC/Python 子进程没有 cgroup 内存上限，远端 release 备份目录也已增长到约 3.6 GB。

**改动**：
- systemd 模板增加 `MemoryAccounting=yes`、`MemoryHigh=2200M`、`MemoryMax=2800M`、`TasksMax=256`
- 生产保留 4 QPS 加速模式入口，新增资源监控保护；内存压力较高时自动终止当前 BabelDOC 并按 2 QPS 稳定模式重试一次
- 长任务超时设置为 6 小时，稳定降级目标为 `TRANSLATION_STABLE_QPS=2`
- 线上 1 页翻译实测发现把 `MemoryHigh=2200M` 当终止点过于保守，稳定/加速都会被误杀；资源保护阈值改为可配置，默认 cgroup `2600 MiB`、系统可用内存 `400 MiB`、swap 已用 `1200 MiB`
- 一键部署脚本增加远端 release tarball 保留策略，默认发布包和备份包各保留最近 3 个
- 文档明确 Zotero 文献缓存只在 JVM 内存，磁盘增长重点是翻译任务目录和部署 release 备份目录

---

## 2026-06-03

### chore: 增加本地一键部署收尾脚本

**改动**：
- 新增 `deploy/deploy-server-improved.sh`，串联测试、发布包构建、上传、线上目录备份、安装和健康检查
- `deploy/deploy-server.sh` 保留为兼容入口，转发到增强版脚本
- 默认适配当前 `admin@115.28.129.221:/home/admin/web-homepage` 生产环境
- 新增 `.deploy.local.example`，支持本地覆盖部署参数且不提交密钥
- 部署过程保留服务器现有 `/etc/web-homepage/web.env`

---

### perf: OpenClaw CLI 缓存与低资源轮询优化

**问题**：每次 `openclaw gateway call` 都会启动一次 Node CLI，实测约消耗 1 秒 CPU 时间。页面初始化和 2 秒历史轮询会在 2 核服务器上持续争抢 CPU，200 Mbps 带宽并不是主要瓶颈。

**改动**：
- 后端限制最多同时运行 2 个 OpenClaw CLI
- 高频只读调用增加分层短缓存，并合并相同的并发缓存未命中
- 写操作后主动失效会话和历史缓存
- Spring 启动后后台预热模型、指令和会话数据
- 前端空闲历史轮询调整为 5 秒，会话约 15 秒、模型约 60 秒刷新

**实测**：`sessions` / `history` 热缓存响应约 2 ms，10 个并发历史请求只触发一次 CLI。

---

### perf: PDF 翻译后台队列 + 最近任务落盘

**问题**：旧实现把输入 PDF、纯中文 PDF、双语 PDF 都长期保存在 JVM `byte[]` 中，并使用无界线程池并行启动 BabelDOC。2 核 / 4 GB 服务器上多个任务容易触发 OOM。

**改动**：
- 改为单 worker、有界等待队列，同一时间只运行一个 BabelDOC 子进程
- PDF 输入和结果落盘到 `.run/translation-tasks/`，下载接口按文件流返回
- 新增最近翻译任务接口和前端记录列表，完成结果在后端重启后仍可恢复
- 翻译速度调整为 2 QPS / 4 QPS，默认最多允许 4 QPS

---

### chore: 回流服务器上线后的维护修复

**改动**：
- 长篇 PDF 翻译的 BabelDOC、SSE 和 Nginx 超时统一提升到 6 小时
- OpenClaw 页面增加 `/openclaw` 路由别名
- 新增 `openclaw_compat.sh`，兼容新版 `openclaw gateway call` CLI 格式并启用启动缓存
- 同步生产环境变量模板、systemd/Nginx 部署模板和线上维护交接文档

**线上结论**：
- OpenClaw Gateway device 已具备写权限，站内会话接口可正常使用
- 当前 OpenClaw 接口仍有数秒启动成本，后续性能优化方向是后端长连接或进程内 Gateway client

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
