# 安全策略 / Security Policy

**中文** · [英文](#english)

## 中文

### 支持范围

只有 `main` 分支的最新代码会接收安全修复。

### 报告漏洞

请**不要**通过公开 Issue、讨论区或 PR 报告安全问题。

请使用 GitHub 的[私密漏洞报告](https://github.com/Shimmersss/my_web/security/advisories/new)（仓库 **Security → Report a vulnerability**），并尽量提供：

- 受影响的模块、接口或文件；
- 复现步骤或概念验证（请使用你自己的测试环境）；
- 可能的影响，以及你建议的修复方式（可选）。

我们会尽快确认收到，并在修复发布后致谢报告者（如你愿意）。

### 测试规范

- 只在你自己部署的实例上测试，不要对他人运行的站点做扫描、压测或数据访问。
- 不要访问、修改或删除不属于你的数据，也不要尝试绕过计费或额度。
- 报告中不要附带真实密钥、Cookie 或他人的个人信息。

### 部署安全提示

- 密钥只放在权限为 `0600` 的 `.env.local` 或服务器环境中，不要提交到仓库。
- 生产环境设置 `AUTH_COOKIE_SECURE=true`，并让应用只监听回环地址、由 Nginx 等反向代理对外提供服务。
- PPTX 生成会运行 Codex CLI 与渲染子进程，在完成按任务的容器隔离和出网限制前，请保持仅 root 可用。
- 更多细节见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## English

### Supported versions

Only the latest code on `main` receives security fixes.

### Reporting a vulnerability

Please **do not** report security issues through public issues, discussions or pull requests.

Use GitHub's [private vulnerability reporting](https://github.com/Shimmersss/my_web/security/advisories/new) (**Security → Report a vulnerability** in the repository) and include, where possible:

- the affected module, endpoint or file;
- reproduction steps or a proof of concept (against your own test environment);
- the potential impact and, optionally, a suggested fix.

We will acknowledge your report as soon as we can and, if you wish, credit you once a fix ships.

### Testing rules

- Only test against instances you run yourself. Do not scan, load-test or access data on sites operated by others.
- Do not access, modify or delete data that isn't yours, and do not try to bypass billing or quotas.
- Do not include real keys, cookies or other people's personal data in reports.

### Deployment hardening

- Keep secrets only in a mode-`0600` `.env.local` or the server environment; never commit them.
- Set `AUTH_COOKIE_SECURE=true` in production, bind the app to loopback and expose it only through a reverse proxy such as Nginx.
- PPTX generation runs the Codex CLI and rendering subprocesses; keep it root-only until per-task container isolation and egress restrictions are in place.
- See [DEPLOYMENT.md](DEPLOYMENT.md) for details.
