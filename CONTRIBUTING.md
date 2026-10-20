# 参与贡献 / Contributing

[中文](#中文) · [English](#english)

## 中文

感谢你愿意改进闪闪小站！提交 Issue 或 PR 之前，请先阅读本文件和[行为准则](CODE_OF_CONDUCT.md)。

### 提交 Issue

- **Bug**：说明复现步骤、期望与实际结果、浏览器/系统版本；涉及任务时附上任务类型与状态，**不要**贴出密钥、Cookie、会话令牌或个人文件。
- **功能建议**：先描述要解决的问题，再给出方案；大的功能改动请先开 Issue 讨论。
- **安全问题**：不要公开提交，请按 [SECURITY.md](SECURITY.md) 私下报告。

### 开发流程

1. Fork 并基于 `main` 新建分支，例如 `feat/translate-glossary`、`fix/ppt-retry`。
2. 按 [README 的快速开始](README.md#快速开始)启动本地环境；不需要 MySQL 时可用 `PROJECT_DB_MODE=h2`。
3. 改动保持聚焦：不顺手格式化或重写无关模块。
4. 按[验证矩阵](docs/WORKFLOW.md#验证矩阵)运行检查，并在 PR 中写明执行过的命令与结果。
5. 有用户可见的变化时附截图，至少检查桌面与 390px 宽度。

常用检查：

```bash
npm --prefix front run build          # 前端构建与全部前端门禁
mvn -f backen/pom.xml test            # 后端 Java 测试
npm --prefix backen test              # PPT/HTML 演示文稿工作进程测试
python3 backen/scripts/test_babeldoc_runner.py   # 翻译脚本测试（需 BabelDOC 环境）
```

### 必须保持的约定

- 后端目录名是 `backen/`，Java 包名是 `com.web.backen`，**不要**改成 `backend`。
- 浏览器只访问本站 `/api/*`；外部 API、密钥、文件代理和子进程调用都放在后端。隐藏导航不能代替接口鉴权。
- 登录态使用 HttpOnly Cookie + CSRF，不要把会话令牌写入 `localStorage` 或 URL。
- 扣费、退款、邀请码占用必须有事务与并发保证；失败补偿要可重试。
- 资源基线是 2 核 / 4 GB：增加进程、队列或并发前，需要在混合负载下验证。
- 密钥只放在本地 `.env.local`（已被 Git 忽略），日志里不能出现密码、令牌或问卷原文。
- 用户可见文字以中文为主；Markdown 需经 DOMPurify 清理；切换页面时关闭 SSE、轮询并释放 Blob URL。

### 提交信息

建议使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：`feat:`、`fix:`、`docs:`、`refactor:`、`test:`、`chore:`，可加范围，例如 `fix(pdf): ...`。

### 许可

提交贡献即表示你同意：代码按 [MIT](LICENSE) 许可发布；美术、图标与截图按 [CC BY-NC-SA 4.0](LICENSE-ASSETS.md) 发布。引入新的第三方依赖或素材时，请在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中补充来源与许可，并确认与上述许可兼容。

## English

Thanks for helping improve Shimmer Desk! Please read this guide and the [Code of Conduct](CODE_OF_CONDUCT.md) before opening an issue or a pull request.

### Issues

- **Bugs**: include reproduction steps, expected vs. actual behaviour and your browser/OS. For task-related bugs, include the task type and state. **Never** paste keys, cookies, session tokens or personal files.
- **Feature requests**: describe the problem first, then the proposal. Open an issue to discuss larger changes before writing code.
- **Security issues**: do not file them publicly — follow [SECURITY.md](SECURITY.md).

### Workflow

1. Fork the repo and branch off `main`, e.g. `feat/translate-glossary` or `fix/ppt-retry`.
2. Start the local stack as described in [Quick Start](README.en.md#quick-start). Use `PROJECT_DB_MODE=h2` if you don't need MySQL.
3. Keep changes focused; don't reformat or rewrite unrelated modules.
4. Run the checks from the [validation matrix](docs/WORKFLOW.md#验证矩阵) and list the commands and results in your PR.
5. For user-visible changes, attach screenshots and check at least desktop and 390 px widths.

Common checks:

```bash
npm --prefix front run build          # frontend build and all frontend gates
mvn -f backen/pom.xml test            # backend Java tests
npm --prefix backen test              # PPT/HTML presentation worker tests
python3 backen/scripts/test_babeldoc_runner.py   # translation runner tests (needs a BabelDOC env)
```

### Ground rules

- The backend directory is `backen/` and the Java package is `com.web.backen`. **Do not** rename them to `backend`.
- The browser only talks to `/api/*`. External APIs, secrets, file proxies and subprocesses live in the backend. Hiding a menu item is not access control.
- Sessions use HttpOnly cookies plus CSRF. Never put session tokens in `localStorage` or URLs.
- Charging, refunds and invite-code use must be transactional and concurrency-safe; compensation must be retryable.
- The resource baseline is 2 vCPU / 4 GB. Validate under mixed load before adding processes, queues or concurrency.
- Secrets live only in the local, git-ignored `.env.local`. Logs must never contain passwords, tokens or questionnaire text.
- The UI is primarily Chinese. Markdown is sanitized with DOMPurify. Close SSE streams and polling and revoke Blob URLs on navigation.

### Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) are preferred: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, optionally scoped, e.g. `fix(pdf): ...`.

### Licensing

By contributing you agree that code is released under [MIT](LICENSE) and artwork, icons and screenshots under [CC BY-NC-SA 4.0](LICENSE-ASSETS.md). When you add a third-party dependency or asset, record its source and license in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and make sure it is compatible.
