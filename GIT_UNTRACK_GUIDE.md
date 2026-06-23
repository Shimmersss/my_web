# Git 取消跟踪指南 / Git Untracking Guide

这份文档说明如何把已经被 Git 跟踪的本地文件从仓库中移除，同时保留本地文件不删除。适用于 `.env`、本地说明文档、截图归档、缓存文件和构建产物。

This guide explains how to remove already tracked local files from a Git repository while keeping the files on disk. It applies to `.env` files, local docs, screenshot archives, caches and build outputs.

## 核心概念 / Core Idea

`.gitignore` 只会阻止“未被跟踪的新文件”进入 Git。文件一旦已经被提交过，即使后来写进 `.gitignore`，Git 仍会继续跟踪它。

`.gitignore` only prevents new untracked files from being added. Once a file has been committed, Git keeps tracking it even if the path is later added to `.gitignore`.

要停止跟踪但保留本地文件，使用：

To stop tracking a file but keep it locally, use:

```bash
git rm --cached path/to/file
```

要停止跟踪整个目录但保留本地目录，使用：

To stop tracking a directory but keep it locally, use:

```bash
git rm --cached -r path/to/directory
```

不要用普通 `rm` 或 `git rm`，它们会删除工作区文件。

Do not use plain `rm` or `git rm` unless you also want to delete the working tree files.

## 标准流程 / Standard Workflow

### 1. 确认当前状态 / Check the Current State

```bash
git status --short --branch
```

如果工作区有别的未提交改动，先确认它们是否与你要做的清理有关，避免把无关改动混进同一个提交。

If there are unrelated changes, decide whether they belong in this cleanup before committing.

### 2. 找出已被跟踪的目标文件 / Find Tracked Targets

查看某个文件是否被 Git 跟踪：

Check whether a file is tracked:

```bash
git ls-files path/to/file
```

查看目录下已被跟踪的 env、Markdown、缓存和截图：

Find tracked env, Markdown, cache and screenshot files under a directory:

```bash
git ls-files front backen | rg '(\.env|\.md$|\.markdown$|__pycache__|\.pyc$|\.png$|\.jpg$|\.jpeg$|\.webp$)'
```

中文路径如果在 `git status` 中显示为八进制转义，可以用 `git ls-files --stage -- path/to/directory` 进一步确认：

If a non-ASCII path is escaped in `git status`, inspect it with:

```bash
git ls-files --stage -- front/部分页面截图
```

### 3. 写入 `.gitignore` / Add Ignore Rules

先写忽略规则，再取消跟踪。这样 `git rm --cached` 后文件不会以未跟踪状态反复出现。

Add ignore rules before untracking. This keeps files from reappearing as untracked files after `git rm --cached`.

示例：

Examples:

```gitignore
# local docs and env files
AGENTS.md
WORKLOG.md
MAINTENANCE.md
front/**/*.md
front/**/.env*
backen/**/*.md
backen/**/.env*

# screenshot archives
front/部分页面截图/

# generated caches
__pycache__/
*.py[cod]
*$py.class
```

### 4. 取消跟踪但保留本地文件 / Untrack While Keeping Local Files

单个文件：

Single file:

```bash
git rm --cached front/.env.production
```

多个文件：

Multiple files:

```bash
git rm --cached front/.env.development front/.env.production backen/README.md
```

整个目录：

Whole directory:

```bash
git rm --cached -r -- front/部分页面截图
```

批量移除已跟踪的 Python 缓存：

Bulk remove tracked Python caches:

```bash
git rm --cached $(git ls-files | rg '\.pyc$')
```

如果匹配结果可能为空，先单独运行 `git ls-files | rg ...` 看输出，避免命令参数为空导致误解。

If the match may be empty, run the `git ls-files | rg ...` part first.

### 5. 验证本地文件仍存在 / Verify Local Files Still Exist

```bash
ls -l front/.env.production
ls -l front/部分页面截图
```

`git rm --cached` 只改 Git 索引，不会删除磁盘上的文件。

`git rm --cached` changes only the Git index. It does not delete files from disk.

### 6. 验证 Git 不再跟踪 / Verify Git No Longer Tracks Them

```bash
git ls-files front/.env.production
git ls-files --stage -- front/部分页面截图
```

如果命令无输出，说明目标文件已经不再被跟踪。

No output means the target is no longer tracked.

### 7. 验证忽略规则生效 / Verify Ignore Rules

```bash
git check-ignore -v front/.env.production
git check-ignore -v front/部分页面截图/首页.png
git check-ignore -v backen/scripts/__pycache__/babeldoc_runner.cpython-313.pyc
```

输出会显示是哪一条 `.gitignore` 规则命中了该路径。

The output shows which `.gitignore` rule matched the path.

### 8. 提交并推送 / Commit and Push

```bash
git status --short
git add .gitignore
git commit -m "chore: stop tracking local files"
git push
```

如果还有 `D path/to/file`，这是正常的：它表示“从仓库删除该路径的跟踪记录”。只要本地文件还在，就是正确结果。

If you see `D path/to/file`, that is expected. It means the path is removed from the repository index. The local file remains on disk.

## 本仓库当前策略 / Current Policy in This Repository

当前公开分支保留根目录 `README.md` 和 `GIT_UNTRACK_GUIDE.md`。以下文件或目录应保留在本地，不上传远端：

This public branch keeps root `README.md` and `GIT_UNTRACK_GUIDE.md`. The following files or directories should stay local and should not be uploaded:

```text
AGENTS.md
WORKLOG.md
MAINTENANCE.md
front/**/*.md
front/**/.env*
backen/**/*.md
backen/**/.env*
front/部分页面截图/
.run/
.release/
server-upload/
outputs/
.env.local
.deploy.local
__pycache__/
*.pyc
```

## 常见错误 / Common Mistakes

### 只改 `.gitignore`，没有 `git rm --cached`

症状：文件仍然出现在提交里。

Symptom: the file still appears in commits.

修复：

Fix:

```bash
git rm --cached path/to/file
git commit -m "chore: stop tracking local file"
```

### 用了 `git rm` 导致本地文件被删

症状：文件从本地磁盘消失。

Symptom: the file is removed from disk.

如果删除还没提交，可以从 HEAD 恢复：

If the deletion has not been committed, restore it from HEAD:

```bash
git restore path/to/file
```

然后重新执行：

Then run:

```bash
git rm --cached path/to/file
```

### 忽略规则写得太宽

例如 `*.md` 会把根 `README.md` 和公开指南也忽略掉。优先使用明确路径：

For example, `*.md` would also ignore root `README.md` and public guides. Prefer scoped rules:

```gitignore
front/**/*.md
backen/**/*.md
AGENTS.md
WORKLOG.md
```

### 需要强制添加一个被忽略文件

一般不要这么做。确实需要时：

Avoid this in normal work. If it is truly needed:

```bash
git add -f path/to/ignored-file
```

本仓库中不要对密钥、`.env.local`、`front/.env*`、`AGENTS.md`、`WORKLOG.md`、`front/部分页面截图/` 使用 `git add -f`。

In this repository, do not use `git add -f` for secrets, `.env.local`, `front/.env*`, `AGENTS.md`, `WORKLOG.md`, or `front/部分页面截图/`.

## 最小审查清单 / Minimal Review Checklist

提交前运行：

Run before committing:

```bash
git status --short
git ls-files front backen | rg '(__pycache__|\.pyc$|\.pyo$|\.DS_Store$|\.log$|\.tmp$|\.bak$|\.env|\.md$|\.markdown$)'
git check-ignore -v front/部分页面截图/首页.png
```

期望结果：

Expected result:

- `git status` 只包含你打算提交的 `.gitignore`、公开文档和索引删除记录。
- `git ls-files ... | rg ...` 对本仓库应无输出。
- `git check-ignore -v ...` 能显示明确的忽略规则。

完成后推送并确认远端分支：

After pushing, verify remote branches:

```bash
git push
git ls-remote --heads origin main no_database
```
