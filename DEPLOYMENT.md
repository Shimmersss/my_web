# 部署与恢复

适用于当前 Spring Boot + Vue 项目。开发步骤见 [项目工作流](docs/WORKFLOW.md)，配置项查 [.env.local.example](.env.local.example)、[application.yml](backen/src/main/resources/application.yml)，实际发布参数查 [部署脚本](deploy/deploy-server-improved.sh)。本文件不存放源站地址、密钥或实时生产状态。

## 发布前必须知道

- 生产基线为单应用实例、2核/4GB；婚恋 SQL 任务不是分布式队列。
- 代码包与数据是两类恢复对象：代码备份不包含 `.run`，也不是数据库备份。
- `matchmaking_tasks` 是婚恋任务恢复依据；`credit_refund_claims` 与扣费流水保证退款幂等。待补偿状态不能随清理或回滚丢失。
- 旧版只读磁盘婚恋任务的程序不能直接接管新版 SQL 任务。禁止把代码自动回滚等同于恢复完成。
- 常规发布保留现有 Nginx、systemd drop-ins、代理、环境文件及 Android 更新清单。纯Web修改无需重编APK。

## 环境与配置

构建需要 Java 17、Maven、Node.js 20+及npm；PPT/HTML运行还需要 uv、稳定版LibreOffice、Poppler、Chromium、fontconfig和CJK字体。按当前锁文件安装依赖，不自行更换模型、CLI或文档渲染内核来绕开环境问题。

PDF 翻译对缺少文字层的页面先用 Tesseract 识别，再交给 BabelDOC。原文件保留；这类页面以最高 300 DPI 背景保留外观，并添加带可渲染字体的隐藏文字层，避免路径文字/图形被重绘丢弃或引用编号显示黑块。页内小于半页的嵌入图片（出版社标志、期刊封面、插图）里的文字不进入识别结果，译文白底只覆盖识别出的文字行，不再按整个版面块涂白，以免抹掉页眉标志和图形。隐藏文字的字号和单词宽度按原图笔画实测（Tesseract 4 与 5 的单词框高度、宽度不同），字号偏小会导致译文字小、留白大。运行机另需 `tesseract` 与 `eng` 识别数据（Ubuntu：`apt-get install tesseract-ocr tesseract-ocr-eng`；macOS：`brew install tesseract`）。可用 `tesseract --list-langs` 检查。OCR 按所选页逐页执行，限制为单线程，最长边最多 6000 像素；有原生文字的页不做 OCR。

默认 Python 命令锁定 BabelDOC 0.6.2 与 bitstring 4.4.0（与现行生产环境一致）。bitstring 5 不再导出 BabelDOC 使用的 `BitStream`，新环境不能直接解析最新依赖。已有受控虚拟环境可继续复用；若手动覆盖 `BABELDOC_COMMAND`，需验证 runner 能导入并完成真实任务。

```bash
java -version
mvn -version
node -v
npm -v
uv --version
npm --prefix backen ci
npm --prefix front ci
```

本地使用根 `.env.local`，生产环境文件位置由 `CONFIG_DIR` 决定，默认 `/etc/web-homepage/web.env`。设置0600权限，秘密不得出现在命令输出、日志或提交中。首次初始化root需要配置密码；修改环境变量不等于修改已存在账户的密码。

生产必须事先准备 MySQL 数据库和应用用户；`schema.sql` 创建/补充表，但不会创建数据库账户。H2仅用于显式本地开发/测试，不是生产数据替代品。

Codex PPT/HTML的已保存服务Key优先；无Key时，服务账号有经过管理的CLI登录态才可回退。登录态、代理、临时HOME过滤与独立Images凭据要分别核验，不能因为认证失败自行覆盖现有配置。应用通过JVM调用子进程时，JVM代理参数不自动替代子进程的HTTP(S)_PROXY。

## 当前脚本能力与缺口

| 项目 | `deploy-server-improved.sh` 当前行为 | 发布者须补齐 |
| --- | --- | --- |
| 测试/构建 | 默认Java、Node测试及前端完整构建 | 业务变化对应的浏览器、MySQL、真实产物测试 |
| 代码备份 | 打包现有安装树，排除`.run` | 数据库、任务文件、配置一致备份与校验 |
| 活动任务 | 部署锁与翻译/PPT磁盘状态扫描 | 生图、婚恋SQL状态、待补偿项及旧JSON迁移状态 |
| 失败恢复 | 安装/内网验证失败时尝试代码回滚 | 核对目标版本的数据格式；不兼容时禁用代码自动回滚并准备恢复步骤 |
| 上线检查 | 服务、内网health、本机入口、公网响应 | 数据库迁移、登录态接口、JS/CSS一致性和页面行为 |
| 末尾统计 | 普通SSH用户执行`du` | root私有备份目录用sudo核验；不能为统计而放宽权限 |

因此，本脚本不是完整的SQL迁移发布器。2026-09-08发布曾使用一次性包装补齐专项备份与回滚控制；该包装在本地`.run`，不属于新克隆可用的标准工具。后续发布必须审阅并落实表中缺口，不能假定已经永久修复脚本。

## 独立全息闪卡的静态增量发布

独立全集入口已改为跳转月下收藏库 https://shimmer.help/moonlit-collection。资源目录仍为 https://shimmer.help/tarot/flashcards/，收藏/抽卡/今日牌仅加载 covers/ 内已渲染卡面，不嵌入完整3D界面。运行资源保存在 `front/public/tarot/flashcards/`，常规前端构建会继续包含它们。现有已验证工程可用 `python3 deploy/build-flashcards.py` 重建；本批压缩入口为 `tar -czf output/tarot-progression/v2/deploy-20261009/flashcards-v20261009.tar.gz -C front/public/tarot flashcards`，发布执行 `bash deploy/deploy-flashcards-static.sh`。

该脚本只发布新的独立静态目录，执行磁盘、服务、配置指纹、归档/逐文件哈希检查，并通过同文件系统 rename 原子上线；它拒绝覆盖已存在的同名入口，不重启应用或写数据库、不自动清理。包中仅有公开运行资源，连接参数仍来自私有 `.deploy.local`。原应用、SQL、任务/退款凭据不进入本次安装与恢复范围；如果发布范围扩展到业务代码，必须执行下方完整应用发布流程。

该首次静态发布脚本现仅用于重建资源，已上线资源由业务依赖，不得再把移动整目录当作独立业务回滚。历史首次批次的撤回记录为仅移动闪卡目录到私有 release 目录并保留原文件；不恢复旧数据库或余额。公网验收覆盖110网页、配置及资源，全集桌面/390/320布局和三种viewer真实交互；证据见 `output/tarot-progression/v2/deploy-20261009/`。新的修订须另选release/version路径，先明确旧目录备份与切换方案，勿直接复用本批的首次安装脚本覆盖线上文件。

## 发布步骤

### 1. 锁定本次发布内容

确认用户已经授权生产发布。检查当前工作区和分支，明确哪些未提交修改将进入包；不要假定main就是生产版本。生产基线取远端release manifest和带时间的维护记录。

```bash
git status --short
git branch --show-current
git diff --check
```

涉及SQL格式/任务恢复/计费的变更，先写明迁移、备份和恢复步骤，并在隔离MySQL实例验证。额外真实模型调用若会消耗额度，应明确测试范围并清理测试产物。

### 2. 先构建，保持生产服务运行

复制并配置 [.deploy.local.example](.deploy.local.example) 为本机 `.deploy.local`，权限0600；使用已有SSH别名或受控连接方式，不提交真实源站信息。不要覆盖已经存在的本地配置。`DEPLOY_HOST`、`DEPLOY_USER`、`DOMAIN` 没有默认值，必须写在本地配置中（`PUBLIC_URL` 默认为 `https://$DOMAIN/`）；`REMOTE_DIR`、`REMOTE_UPLOAD_DIR` 等服务器路径的默认值只是 `/opt/web-homepage*` 占位，请按实际服务器填写。脚本按作者的 Ubuntu + systemd + Nginx 环境编写，自建部署时只作参考。

```bash
# 只构建，不上传、不停服、不安装。
BUILD_ONLY=1 RUN_TESTS=1 bash deploy/deploy-server-improved.sh
```

确认前后端门禁通过，保留release ID、基线commit/dirty状态和完整构建日志。`front`构建包含模板预览、导航、内测、轮询、Android、PWA/DAL和PDF检查；不要只执行`vite build`替代。

### 3. 准备恢复依据，再进入维护窗口

- 核对剩余磁盘、服务状态与必要权限；预估备份和安装时长，将耗时构建/上传提前完成。
- 阻止新生成请求并排空翻译、PPT、生图、婚恋任务；检查SQL与实际配置的磁盘目录。部署锁目前不等于四模块都已阻止接单。
- 停服后再次确认任务状态。若存在运行项或待补偿项，先完成/恢复它们，或采用明确保留恢复凭据的迁移方案；不清空任务文件来通过检查。
- 备份代码、MySQL一致快照、`.run`和配置。数据库涵盖用户、会话、积分流水、退款凭据、邀请码、内测资格、婚恋任务/报告/问卷及其他业务表；不要只导出报告表。
- 检查退出码、文件非空、SQL导出完成标记及SHA256；保留可恢复路径。私密备份目录0700、文件0600，不下载到公开产物目录。
- 失败恢复按“服务已停/安装已开始/新应用已写入数据”等阶段区分；避免备份异常使旧服务长期停止。

### 4. 安装与迁移

在审阅后的发布流程中调用安装路径，保留 `FORCE_NGINX_CONFIG=0`、`RUN_TESTS=1`。基础脚本入口是 `bash deploy/deploy-server-improved.sh`；它会重新构建并执行其现有回滚逻辑，运行前必须按能力表补齐相应控制，不能用不存在的参数声称禁用了回滚。

安装时保留生产配置、`.run`、systemd drop-ins与Nginx。等待启动完成并检查迁移错误。婚恋旧JSON只有成功导入SQL后才删除；不可读快照会阻止启动，应保留原文件定位原因，不用删除凭据“修复”启动。

### 5. 上线验收

- 服务与Nginx active，Nginx语法正常；内网与公网`/api/health`通过。该接口只说明应用响应，不证明数据库/worker健康。
- 迁移后的表/字段存在；核验业务记录、任务状态和补偿状态。任务0条可能是正常清理结果，不等于迁移失败。
- 通过短期测试会话访问相关登录态API，确认权限与错误码；结束时退出，不把Cookie写到日志。
- 公网首页、相关路由、JS/CSS可达且与本次构建一致；至少检查受影响桌面/手机页面、溢出和控制台。
- 既有代理、源站保护和OpenClaw下线状态不回退。Android文件链路或原生壳有变化才按 [Android说明](android/README.md) 另行发版。
- 区分安装失败与清理/统计失败：后者先核对服务和产物，避免盲目回退。发布后才更新“已上线验证”状态。

## 恢复决策

### 系统 Java 更新后的进程启动故障

系统自动更新 OpenJDK 后，已运行的 JVM 可能继续使用旧版本，而磁盘上的 `jspawnhelper` 已被替换。若日志同时出现 `Incorrect Java version`、不同版本的 `jspawnhelper version` 与 `Failed to exec spawn helper`，翻译或其他子进程任务会立即失败；`/api/health` 正常不能排除此故障。

先核对包升级记录、服务启动时间及 `/proc/<MainPID>/exe`（旧可执行文件可能标记 `deleted`），再检查四类任务和 SQL 婚恋活动/待补偿状态。排空后受控重启 `web-backen`，验证真实翻译、PDF 产物及登录态下载；保留任务和退款凭据。每次 JDK 更新后均需安排这项检查与安全重启，不在运行任务期间无条件自动重启，也不放宽资源门禁。

生产 Java 按用户选择改为维护窗口手动升级：`/etc/apt/apt.conf.d/99-web-java-manual-upgrades` 在 `Unattended-Upgrade::Package-Blacklist` 中添加 `^openjdk-`，其余系统安全自动更新继续保留。这不是 `apt-mark hold`，普通手动 `apt upgrade` 仍可能升级 Java，执行前须检查拟升级包并排空任务。Java 安全补丁由维护者定期检查，不能永久忽略。策略调整后以 `apt-config dump` 和 `unattended-upgrade --dry-run --debug` 核验，不实际升级包来验证。恢复自动升级时仅撤销该独立配置文件，并安排升级后的安全重启。

| 场景 | 处理原则 |
| --- | --- |
| 构建/上传失败，尚未停服 | 保持原服务，不做数据恢复 |
| 已停服，未安装/未迁移 | 核对现场后恢复原服务，保留失败日志 |
| 新版启动失败且未写入不兼容状态 | 验证数据兼容后恢复代码，保留`.run`和数据库 |
| SQL任务格式变化或新版已接单 | 保留新数据，使用兼容修复版或经审阅的数据转换；不能仅换旧jar |
| 仅统计/清理非零退出 | 复核服务、manifest、迁移、产物；补完失败步骤，不自动认定上线失败 |

不要直接恢复旧账户余额或删除任务/退款表来恢复旧程序。备份存在不代表可以覆盖上线后的真实写入；恢复前必须明确数据时间点和新写入处理方式。

## 发布记录

在本地MAINTENANCE更新一条带时间的生产快照；WORKLOG记录本次结果、命令证据、release、备份及恢复限制。AGENTS仅更新长期约束，不追加整段发布日志。通用流程变化更新本文件。

文档不会自动修复部署脚本。后续自动化优先项：四模块接单锁/排空、数据库与运行文件备份、SQL兼容回滚策略、区分安装与收尾退出码；各项完成并验证后再修改能力表。
