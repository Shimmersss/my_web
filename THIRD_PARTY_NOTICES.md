# 第三方组件与引用 / Third-Party Notices

本项目建立在众多开源项目、公开数据与在线服务之上，在此致谢并列出其许可。版本以仓库中的 `pom.xml`、`package-lock.json`、`android/app/build.gradle` 与部署文档为准；升级依赖时请同步更新本文件。

This project is built on many open-source projects, public datasets and online services. They are credited below together with their licenses. Versions follow `pom.xml`, the `package-lock.json` files, `android/app/build.gradle` and the deployment docs; update this file whenever a dependency changes.

> 本文件是归属说明，不替代各组件自身的许可证全文。分发构建产物时，请随附相应组件的 LICENSE/NOTICE。
>
> This file is an attribution summary and does not replace each component's full license text. Ship the relevant LICENSE/NOTICE files when you redistribute builds.

## 1. 随仓库分发的代码与素材 / Code and assets shipped in this repository

| 组件 Component | 位置 Location | 版本 Version | 许可 License | 说明 Notes |
| --- | --- | --- | --- | --- |
| [open-ppt-skill / open-kimi-ppt-skill](https://github.com/jinwyp/open-ppt-skill) by Binaryify Zhuang | `vendor/open-kimi-ppt-skill/` | 1.3.0 (`07eeaad`) | MIT | PPTD 设计系统、编辑器与 PPTX 导出。详见该目录 `LICENSE`、`VENDOR.md`。<br>PPTD design systems, editor and PPTX export. See `LICENSE` and `VENDOR.md` there. |
| ↳ neo-ppt 编辑器资源 / editor assets | `vendor/open-kimi-ppt-skill/editor/` | — | 见下方提示 / see caveat below | 上游说明其为逆向并修补的 Kimi/neo-ppt 资源，**不随 MIT 自动授权**。<br>Upstream describes these as reverse-engineered, patched Kimi/neo-ppt assets — **not covered by MIT**. |
| ↳ [KaTeX](https://katex.org/) fonts | `vendor/open-kimi-ppt-skill/editor/neo-ppt/assets/` | — | MIT | 编辑器内的公式字体 / math fonts used by the editor |
| [Noto Serif SC](https://fonts.google.com/noto/specimen/Noto+Serif+SC) 子集 | `front/src/assets/fonts/` | — | SIL OFL 1.1 | 许可全文见 `NotoSerifSC-LICENSE.txt` / full text in `NotoSerifSC-LICENSE.txt` |
| [three.js](https://threejs.org/)（含 GLTFLoader） | `front/public/tarot/flashcards/**/app.bundle.js` | r180 | MIT | 全息闪卡 WebGL 查看器 / holographic flashcard WebGL viewer |
| [BJTU-Slides-Template](https://github.com/Allenpandas/BJTU-Slides-Template) by Allenpandas | `front/public/ppt-template-previews/github-bjtu-*`；模板目录 / catalog `.agents/skills/create-template-pptx/assets/template-catalog.json` | `b68f691` | Apache-2.0 | 预览图由原模板渲染；PPTX 原件在准备阶段按固定提交与 SHA-256 下载。<br>Previews are renders of the original templates; PPTX sources are fetched at a pinned commit with SHA-256 checks. |
| [tiktoken](https://github.com/openai/tiktoken) `o200k_base` 词表 / vocabulary | `backen/scripts/tiktoken-cache/` | — | MIT | 随应用发布并做哈希校验，避免任务启动时联网下载。<br>Bundled and hash-checked so jobs never download it at runtime. |

> ⚠️ **neo-ppt 编辑器资源提示**：`vendor/open-kimi-ppt-skill/editor/` 中的编辑器包按上游说明来自对 Kimi/neo-ppt 的逆向与修补。本仓库仅在所有者另行确认授权的部署中使用，并把 PPTX 生成限制为 root 权限。Fork 或再分发时请自行评估其权利状态，必要时删除该目录并改用 HTML（reveal.js）链路。
>
> ⚠️ **About the neo-ppt editor assets**: per upstream, the editor bundle under `vendor/open-kimi-ppt-skill/editor/` contains reverse-engineered and patched Kimi/neo-ppt assets. This repository only uses it in deployments the owner has separately authorized and keeps PPTX generation root-only. If you fork or redistribute, assess its rights status yourself; you can remove the directory and use the HTML (reveal.js) pipeline instead.

## 2. 前端依赖 / Frontend dependencies (`front/`)

| 组件 Component | 版本 Version | 许可 License |
| --- | --- | --- |
| [Vue](https://vuejs.org/) | 3.5.30 | MIT |
| [Vue Router](https://router.vuejs.org/) | 4.6.4 | MIT |
| [Pinia](https://pinia.vuejs.org/) | 2.3.1 | MIT |
| [vue-i18n](https://vue-i18n.intlify.dev/) | 9.14.4 | MIT |
| [Naive UI](https://www.naiveui.com/) | 2.44.1 | MIT |
| [@vicons/ionicons5](https://github.com/07akioni/xicons)（[Ionicons](https://ionic.io/ionicons)） | 0.12.0 | MIT |
| [PDF.js](https://mozilla.github.io/pdf.js/) (`pdfjs-dist`) | 4.10.38 | Apache-2.0 |
| [marked](https://marked.js.org/) | 18.0.4 | MIT |
| [DOMPurify](https://github.com/cure53/DOMPurify) | 3.4.7 | MPL-2.0 OR Apache-2.0 |
| [Vite](https://vitejs.dev/) · `@vitejs/plugin-vue` | 5.4.21 · 5.2.4 | MIT |
| [Sass](https://sass-lang.com/) | 1.98.0 | MIT |
| [unplugin-auto-import](https://github.com/unplugin/unplugin-auto-import) · [unplugin-vue-components](https://github.com/unplugin/unplugin-vue-components) | 0.17.8 · 0.26.0 | MIT |

## 3. 后端依赖 / Backend dependencies (`backen/`)

### Java (Maven)

| 组件 Component | 版本 Version | 许可 License |
| --- | --- | --- |
| [Spring Boot](https://spring.io/projects/spring-boot)（web、jdbc、test）· Spring Security Crypto | 3.2.5 | Apache-2.0 |
| [MyBatis-Plus](https://baomidou.com/) | 3.5.6 | Apache-2.0 |
| [Apache PDFBox](https://pdfbox.apache.org/) | 3.0.3 | Apache-2.0 |
| [MySQL Connector/J](https://dev.mysql.com/doc/connector-j/en/) | 8.3.0（Spring Boot 管理 / managed） | GPL-2.0 with Universal FOSS Exception |
| [H2 Database](https://h2database.com/) | 2.2.224（同上 / managed） | MPL-2.0 OR EPL-1.0 |
| [Project Lombok](https://projectlombok.org/) | 1.18.32（编译期 / compile-time） | MIT |

### Node.js 演示文稿工作进程 / presentation worker

| 组件 Component | 版本 Version | 许可 License |
| --- | --- | --- |
| [OpenAI Codex CLI](https://github.com/openai/codex) (`@openai/codex`) | 0.147.0 | Apache-2.0 |
| [reveal.js](https://revealjs.com/) | 6.0.1 | MIT |
| [Playwright](https://playwright.dev/) (`playwright-core`) | 1.62.1 | Apache-2.0 |
| [pptx-automizer](https://github.com/singerla/pptx-automizer) | 0.8.2 | MIT |
| [JSZip](https://stuk.github.io/jszip/) | 3.10.1 | MIT OR GPL-3.0-or-later（本项目按 MIT 使用 / used under MIT） |
| [image-size](https://github.com/image-size/image-size) | 1.2.1 | MIT |
| [undici](https://undici.nodejs.org/) | 6.28.0 | MIT |
| [yaml](https://eemeli.org/yaml/) | 2.8.1 | ISC |

### Python 文档处理 / document processing

以下组件**不随仓库分发**，由部署环境安装，并以独立子进程调用。

These are **not distributed with the repository**; the deployment installs them and the backend calls them as separate processes.

| 组件 Component | 版本 Version | 许可 License | 用途 Purpose |
| --- | --- | --- | --- |
| [BabelDOC](https://github.com/funstory-ai/BabelDOC) | 0.6.2 | AGPL-3.0 | 保留版式的 PDF 翻译 / layout-preserving PDF translation |
| [PyMuPDF](https://pymupdf.readthedocs.io/) | BabelDOC 依赖 / via BabelDOC | AGPL-3.0（或商业 / or commercial） | PDF 解析与渲染 / PDF parsing and rendering |
| [bitstring](https://github.com/scott-griffiths/bitstring) | 4.4.0 | MIT | BabelDOC 兼容锁定 / pinned for BabelDOC compatibility |
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) + `eng` 数据 / data | 4.1.1+ | Apache-2.0 | 无文字层 PDF 的 OCR / OCR for textless PDFs |
| [Docling](https://github.com/docling-project/docling) | 可选 / optional | MIT | 资料转 PPT 的文档解析 / source parsing for PPT |
| [MarkItDown](https://github.com/microsoft/markitdown) | 可选 / optional | MIT | 同上的轻量回退 / lightweight fallback |
| [NumPy](https://numpy.org/) | — | BSD-3-Clause | 版式与 OCR 计算 / layout and OCR maths |

> BabelDOC 与 PyMuPDF 采用 AGPL-3.0。本项目只通过命令行调用它们，不链接其代码；但如果你修改它们并以网络服务形式提供，需要遵守 AGPL 的源码提供义务。
>
> BabelDOC and PyMuPDF are AGPL-3.0. This project only invokes them as command-line processes and does not link their code; if you modify them and offer them over a network, the AGPL source-availability obligations apply to you.

### 系统工具 / System tools

[LibreOffice](https://www.libreoffice.org/)（MPL-2.0）、[Poppler](https://poppler.freedesktop.org/) `pdftoppm`（GPL-2.0/3.0）、[Chromium](https://www.chromium.org/) / Google Chrome（BSD-3-Clause 及其他）、[fontconfig](https://www.freedesktop.org/wiki/Software/fontconfig/) 与 [Noto CJK](https://github.com/notofonts/noto-cjk)（OFL-1.1）用于演示文稿渲染与质检；[uv](https://github.com/astral-sh/uv)（MIT/Apache-2.0）管理 Python 环境；[Blender](https://www.blender.org/)（GPL-3.0）仅用于离线制作闪卡 GLB，不参与运行时。它们都作为外部程序安装。

LibreOffice (MPL-2.0), Poppler `pdftoppm` (GPL-2.0/3.0), Chromium / Google Chrome (BSD-3-Clause and others), fontconfig and Noto CJK (OFL-1.1) render and check presentations; uv (MIT/Apache-2.0) manages the Python environment; Blender (GPL-3.0) is only used offline to author flashcard GLB files and is not part of the runtime. All of them are installed as external programs.

## 4. Android

| 组件 Component | 版本 Version | 许可 License |
| --- | --- | --- |
| [GeckoView](https://mozilla.github.io/geckoview/)（arm64-v8a） | 149.0.20260403140140 | MPL-2.0 |
| [AndroidX Core](https://developer.android.com/jetpack/androidx/releases/core) | 1.16.0 | Apache-2.0 |

## 5. 数据与参考资料 / Data and references

| 来源 Source | 版本 Version | 许可 License | 用途 Usage |
| --- | --- | --- | --- |
| [Tarotoo Tarot Card Meanings Dataset](https://github.com/Tarotoo-com/tarotoo-tarot-dataset) | `8cee2b1` | MIT | 22 张大阿卡纳的字段结构起点；站内中文牌义为本项目自行编写并版本化维护。<br>Structural starting point for the 22 Major Arcana; the Chinese meanings are written and versioned by this project. |
| A. E. Waite, [*The Pictorial Key to the Tarot*](https://en.wikisource.org/wiki/The_Pictorial_Key_to_the_Tarot/Part_3)（1910） | — | 公有领域 / Public domain | 仅作象征参考，未直接摘录 / symbolism reference only, not quoted |
| [luhuadong/mbti-test](https://github.com/luhuadong/mbti-test) | `daaf70b` | MIT | 历史人格问卷的题序与计分方向（已停用，仅旧报告兼容）。<br>Question order and scoring for the retired personality questionnaire (legacy reports only). |

## 6. 外部服务 / External services

后端在配置了相应密钥时调用以下服务；使用时请遵守各自的服务条款。浏览器从不直接访问它们。

When the matching keys are configured, the backend calls the services below; follow each provider's terms. The browser never calls them directly.

- [Zotero Web API v3](https://www.zotero.org/support/dev/web_api/v3/start) — 文献库同步 / library sync
- [GitHub REST API](https://docs.github.com/rest) — 项目展示与周榜/月榜 / project showcase and weekly/monthly rankings
- OpenAI 兼容的 Chat / Vision API — 翻译、解读、项目摘要 / translation, readings, summaries
- [OpenAI Images API](https://platform.openai.com/docs/guides/images)（GPT Image 2）— 生图与演示配图 / image generation and slide visuals
- [OpenAI Codex](https://github.com/openai/codex) — 演示文稿作者层 / presentation authoring
- [Tavily](https://tavily.com/)、[Semantic Scholar](https://www.semanticscholar.org/product/api) — 联网研究 / web and scholarly research
- [Wikimedia Commons](https://commons.wikimedia.org/)、[Openverse](https://openverse.org/) — 带复用授权信息的图片检索；生成结果会保留来源与授权状态。<br>Image search with reuse metadata; outputs keep source and license status.

## 7. 致谢 / Acknowledgements

感谢以上所有项目的作者与维护者。若你发现遗漏或许可描述有误，欢迎提交 Issue 或 PR。

Thanks to the authors and maintainers of every project above. If you spot an omission or an incorrect license description, please open an issue or a pull request.
