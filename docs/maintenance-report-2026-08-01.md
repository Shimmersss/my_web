# 2026-08-01 Agent 演示内核维护报告

## 维护目标

将 PPT 模块从“模型生成 deck JSON，再由固定 renderer 套版”的旧内核迁移为 provider-neutral Agent + repository Skill 驱动，并把 PPTX/HTML 的研究、生成、真实渲染、视觉审查、自然语言修订和来源追踪纳入同一条可失败、不可静默降级的质量链路。

## 本次范围

- 保留 Spring Boot 的登录、权限、额度、幂等、有界队列、任务恢复、SSE、下载和历史清理。
- 新增 `research-presentation`、`create-template-pptx`、`create-html-presentation` 三个仓库级 Skill。
- 新增独立 Node Agent worker，兼容 OpenAI Chat Completions、Claude Messages 和后台 `auto`/Mimo 配置。
- PPTX 使用固定版 `pptx-automizer` 复制明确映射的源页并原位修改；HTML 使用 12 套离线 reveal.js 主题资产独立编排。
- 研究聚合 OpenAlex、Crossref、arXiv、Semantic Scholar，并在配置 Tavily 时补充网页检索；来源按 DOI/标题/作者去重并映射到页面、参考文献和 speaker notes。
- 删除旧 PptxGenJS 固定 renderer、固定 reveal renderer、Python template-fill 及运行时兼容回退。

## 审查中修复的问题

- 创建任务先写入 `creating` 标记，额度流水在扣费后立即落盘；重启时会核对资料完整性和数据库 SPEND 流水，安全恢复或退款，不再把半成品直接排队。
- 部署锁覆盖自定义任务目录，停服前后各扫描一次；活动任务以退出码 42 推迟部署并恢复旧服务。
- 模板缓存与自定义任务目录解耦，生产固定到持久化 `_template-cache`；失败回滚单独恢复旧模板缓存，活动任务阻塞时清理临时备份。
- 研究请求只允许五个官方 HTTPS 主机，拒绝凭据、IP 字面量、私网/保留地址和跨域带密钥跳转；每跳重新验证，并将实际 TLS 连接绑定到已验证公网 IP；响应体流式限制为 5 MB。
- PPTX manifest 遵循 `presentation.xml` 的真实放映顺序，并把逻辑页映射回物理 `slideN.xml`；重复 shape 名使用 `nameIdx` 精确选择。
- 全页、越界、品牌和装饰图片不会成为可替换图片槽；文字和图片编辑都必须命中当前源页的合法槽位与容量。
- 视觉审查及返修最多四页一批；返修只合并被授权页面，避免 2 核/4 GB 机器上的全 deck Base64 内存峰值。
- LibreOffice 每次使用独立 profile；部署拒绝 Dev/alpha/beta/RC，并以空白基线和两组等长纯中文实际 PNG 验证可区分字形，防止“中文消失但预检通过”。
- HTML 预览令牌不进入 URL；修订切换任务时立即释放旧 HTML/PNG Blob。

## 验证记录

- Node Agent 单元测试：23/23。
- Maven 定向回归：PPT 生命周期、翻译生命周期、BabelDOC 分片与参考文献延续全部通过。
- 前端生产构建：通过。
- 六套 PPTX 模板均完成 5 页真实 LibreOffice → PDF → PNG smoke；包、frame map、占位符/引用确定性检查和截图非空/非重复检查通过。
- 本机随附的是 `LibreOfficeDev 26.8.0.0.alpha0`，新字体门禁按设计拒绝；完整的稳定版 LibreOffice + Chromium smoke 在生产安装阶段执行，未通过则自动回滚。
- 当前桌面沙箱禁止 localhost bind、Chrome 子进程和 npm registry DNS，因此全量 Maven 中依赖临时端口/Chrome 的旧测试及本机 HTML smoke 无法在该沙箱执行；协议单测已改为内存 fetch，不再依赖本地监听。

## 生产发布

本轮自动部署尚未完成。2026-08-01 只读 SSH 检查在本机建立连接前被 Codex managed sandbox 拒绝：`ssh: connect to host 115.28.129.221 port 22: Operation not permitted`；尝试改用 Computer Use 时，运行时又明确禁止控制 `com.apple.Terminal`。因此没有执行上传、停服、安装或生产变更，也没有修改 DNS/Sites。

最近一次已确认的线上版本仍是 2026-07-31 17:43 的 Agent 初版：release `/home/admin/.web-homepage-releases/web-homepage-20260731-174300.tar.gz`，backup `/home/admin/.web-homepage-releases/web-homepage-backup-20260731-174300.tar.gz`。恢复 SSH 通道后，应从仓库根目录运行 `./deploy/deploy-server-improved.sh`；安装器会先检查活跃任务，随后在服务器执行 stable LibreOffice/CJK 门禁。部署后还必须完成 6 套 PPTX + 12 套 HTML 视觉 smoke、真实 PPTX/HTML Agent 任务、`systemctl`/health/domain/proxy/memory 检查，再把新 release/backup 写回本报告。

## 已知事件

2026-07-31 的早期试部署回滚曾中断翻译任务 `994f676b`。当时旧回滚流程替换了 `.run`，任务无法恢复，且未发现相应额度流水；该任务需要用户重新提交。本次部署已改为始终保留任务 `.run`，并在发布锁前后检查 `creating/queued/running` 状态，避免再次发生。

## 回滚原则

不保留旧 PPT 生成内核运行时开关。应用级回滚只使用服务器 release/backup 归档，且保留当前任务目录；模板缓存使用独立备份恢复。未修改 DNS、OpenAI Sites 绑定或现有 Nginx 域名流量。
