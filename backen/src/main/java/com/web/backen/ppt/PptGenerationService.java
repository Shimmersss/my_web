package com.web.backen.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.translate.LlmService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
public class PptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationService.class);
    private static final String AUTO_PROMPT = "请根据上传的资料自动提炼重点，判断合适的受众和叙事方式，生成一份结构清晰、视觉专业、可编辑的演示文稿。保留关键事实、数据和结论，必要时补充目录、图表解读、行动建议或下一步。";
    private static final Pattern JSON_IMAGE_OBJECT = Pattern.compile("\\{\\s*\"index\"\\s*:\\s*\\d+[\\s\\S]*?\\n\\s*\\}");
    private static final Pattern ARABIC_SLIDE_COUNT = Pattern.compile("(?:生成|做|制作|输出|整理|压缩|控制|限制)?\\s*(\\d{1,2})(?:\\s*[-~到至]\\s*(\\d{1,2}))?\\s*(?:页|張|张|slides?|PPT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_SLIDE_COUNT = Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:页|張|张)");
    private static final Set<String> IMAGE_LAYOUTS = Set.of("full-image", "image-left", "image-right", "image-top", "evidence-strip");
    private static final Set<String> SAFE_SLIDE_TYPES = Set.of(
            "cover", "contents", "section", "content", "image", "conclusion", "thanks", "chapter");
    private static final Pattern HEX_COLOR = Pattern.compile("#?[0-9A-Fa-f]{6}");
    private static final Set<String> DECORATIVE_SHAPE_TOKENS = Set.of("bg", "line", "shape");
    private static final Set<String> GENERIC_IMAGE_TERMS = Set.of(
            "paper", "image", "figure", "table", "chart", "other", "素材", "论文", "图片", "图", "表", "使用", "用途");
    private static final List<String> SEMANTIC_KEYWORDS = List.of(
            "pipeline", "workflow", "timeline", "baseline", "online", "rgb", "heatmap", "subgraph", "shapenet",
            "method", "route", "model", "framework", "architecture", "result", "conclusion", "data", "experiment",
            "evaluation", "comparison", "performance", "feature", "algorithm", "system", "training", "dataset",
            "方法", "路线", "模型", "结构", "架构", "流程", "结果", "结论", "数据", "实验", "评估", "对比", "性能",
            "特征", "算法", "系统", "训练", "数据集", "热力图", "管线", "基线", "时间线", "在线", "表格");
    private static final String SYSTEM_PROMPT = """
            You are an expert presentation designer. Create a concise, editable presentation deck for any topic: business, product, project, teaching, training, marketing, research, or personal sharing.
            Return ONLY valid JSON. Do not wrap it in Markdown.
            JSON shape:
            {
              "title": "deck title",
              "subtitle": "optional subtitle",
              "audience": "target audience",
              "theme": "short style description",
              "slides": [
                {
                  "type": "cover|contents|section|content|image|conclusion|thanks",
                  "section": "optional section label",
                  "title": "slide title",
                  "headline": "one strong sentence",
                  "bullets": ["3 to 5 short bullets"],
                  "metrics": [{"value":"0.8321","label":"Random Forest PRC"}],
                  "notes": "speaker notes",
                  "imageHint": "optional image usage hint",
                  "imageId": "optional image id from imageManifest",
                  "layout": "auto|full-image|image-left|image-right|image-top|evidence-strip|metrics|metric-hero|comparison|matrix|timeline|conclusion",
                  "visualSpec": {"type":"workflow|architecture|comparison|matrix|timeline|chart","title":"optional generated visual title","items":["short labels"]}
                }
              ]
            }
            Rules:
            - Use Simplified Chinese unless the user's prompt explicitly requests another language.
            - If targetSlideCount is provided, return exactly that many slides including cover and thanks. Compress and merge content to fit the requested count; never satisfy a page limit by only keeping the first chapters.
            - If targetSlideCount is empty, infer a natural length from the request and available material.
            - For defenseMode=true, use a formal thesis defense rhythm and, when targetSlideCount is empty, normally create about 22 slides. For all other tasks, infer a natural story arc from the request and source material.
            - When the requested page count is small, preserve the complete story arc by merging adjacent details into denser synthesis slides: context, audience need, approach, evidence, implications, and conclusion or next steps.
            - Prefer short, audience-ready phrasing over paragraphs. Each content slide should have 3 to 6 bullets, each bullet 1 to 2 short lines.
            - Do not copy long source paragraphs. Extract, summarize, and structure the material for a live presentation.
            - Match the tone to the request: persuasive for a pitch, practical for training, clear for teaching, rigorous for research, and executive-friendly for a business report. Avoid generic filler and unsupported hype.
            - Preserve important facts, numbers, names, definitions, product terms, metrics, dates, and decisions.
            - Do not invent specific facts or metrics if the source material does not contain them. Clearly label recommendations or proposed next steps.
            - Use section as a semantic uppercase label such as BACKGROUND, ROUTE, DATA, FEATURES, MODELS, RESULT, SCALE, CONCLUSION, OUTLOOK. Do not use numeric labels like 1.1 or 2.3.
            - Use the builtInTemplate palette as the single visual color source. Do not infer or invent another palette from uploaded PPTX text samples or source images.
            - If templateStyle.frameworkMode is true, follow templateStyle.templateFramework as the preferred slide rhythm and layout skeleton. Map cover/contents/section/content/image/conclusion/thanks slides onto the closest framework roles while replacing all original wording with the new deck content.
            - If imageManifest is provided, choose relevant imageId values deliberately. Use extracted charts, screenshots, photos, diagrams, tables, or document pages when they directly support the slide. Request visualSpec for a clear editable diagram when no extracted image fits.
            - Never pair a slide with an image unless the slide title/headline directly matches that image's title, summary, or bestUse. If no image matches but a visual would improve the slide, leave imageId empty and provide visualSpec.
            - Do not assign image layouts when both imageId and visualSpec are empty. Use content, metrics, comparison, conclusion, or auto layout instead.
            - Ignore imageManifest items where useful is false or importance is below 3.
            - Use varied slide composition. Across adjacent content slides, alternate between text synthesis, metric-hero, comparison, matrix/timeline, image-top, image-left/right, evidence-strip, and full-image. Avoid using the same image placement more than twice in a row.
            - Use image-top for a wide chart/workflow with short interpretation bullets below; evidence-strip for multiple result/evidence slides that need an image plus compact takeaways; metric-hero when one number or metric is the slide's anchor; comparison/matrix/timeline for text-heavy synthesis.
            - Use image/full-image layouts for important charts, tables, product screenshots, workflows, architecture figures, and visual evidence. Use text, metrics, comparison, matrix, timeline, metric-hero, or conclusion layouts for synthesis slides.
            - Use layout image-left, image-right, image-top, evidence-strip, or full-image based on the chosen image's layoutHint and slide role. Prefer full-image for dense charts/tables/workflows/architecture, image-top/evidence-strip for wide evidence, and side image only when text needs equal weight.
            - Use metrics when the source material contains strong numeric evidence; put the numeric values in metrics.
            - Use section slides as chapter dividers and keep them visually sparse.
            - If revisionInstruction or existingDeck is provided, treat the previous deck as the editing baseline: preserve correct content and slide order where possible, then apply every requested change precisely.
            - When defenseMode=true, prefer the rhythm: cover, contents, section, background, significance, route, section, data, framework, model, section, experiment design, 4-6 result/comparison/ablation slides, section, conclusion, outlook, thanks. For other topics, choose a topic-appropriate arc instead of forcing a research structure.
            """;
    private static final String VISION_PROMPT = """
            You are helping build a professional PPT for an arbitrary topic. Inspect the attached source-material images in order.
            Return compact JSON only:
            {
              "images": [
                {
                  "index": 1,
                  "kind": "workflow|chart|table|architecture|formula|other",
                  "title": "短标题",
                  "summary": "15字内说明",
                  "bestUse": "15字内用途",
                  "importance": 1,
                  "useful": true,
                  "layoutHint": "full-image|image-left|image-right|image-top|evidence-strip|none"
                }
              ]
            }
            Rules:
            - Use the provided order number as index.
            - Keep every field short to avoid truncation.
            - Be concrete: mention visible chart/table/workflow/product/screenshot/result content.
            - Prefer charts, tables, workflows, architecture, product screenshots, photos, timelines, comparisons, visualizations, and clear document pages with a presentation purpose.
            - Mark useful=false for logos, decorative assets, blurred screenshots, blank/mostly text pages, repeated pages, tiny formulas, unreadable crops, or images that do not provide useful slide evidence.
            - importance is 1 to 5, where 5 means highly useful for the deck.
            - layoutHint: full-image for dense charts/tables/workflows/architecture; image-top or evidence-strip for wide evidence/result images with short takeaways; image-left or image-right for simple figures that can sit beside text; none for useless images.
            """;
    private static final String SLIDE_COUNT_REPAIR_PROMPT = SYSTEM_PROMPT + """

            You are correcting a deck that missed the requested slide count.
            Return a revised deck with exactly requiredSlideCount slides, including cover and thanks.
            Preserve the full story arc by merging or expanding content across the whole deck. Do not keep only the first slides.
            """;
    private static final String COMPACT_JSON_RETRY_PROMPT = SYSTEM_PROMPT + """

            The previous response was truncated before the JSON closed.
            Return a smaller but complete JSON object.
            Compression rules:
            - Keep exactly targetSlideCount slides when targetSlideCount is present.
            - Each slide has at most 3 bullets.
            - Each bullet is at most 18 Chinese characters.
            - notes is at most 35 Chinese characters.
            - Prefer empty metrics and imageId unless the evidence is essential.
            - Close every array and object. Valid complete JSON is more important than detail.
            """;
    private static final String TEMPLATE_FILL_PROMPT = """
            You are a senior presentation strategist filling a native PowerPoint template.
            Return ONLY valid JSON. Do not wrap it in Markdown.
            JSON shape:
            {
              "schema": "template_fill_pptx_plan.v1",
              "source_pptx": "template.pptx",
              "slides": [
                {
                  "source_slide": 1,
                  "purpose": "cover|toc|section|content|result|conclusion|thanks",
                  "layoutReason": "why this template slide fits this message",
                  "notes": "2-4 natural speaker-note sentences",
                  "replacements": [
                    {"slot_id": "s01_sh5", "text": "replacement text"}
                  ],
                  "image_edits": [
                    {"image_id": "source-image-1", "region_id": "s03_img8", "caption": "why this image belongs here"}
                  ],
                  "generated_visual": {"type":"workflow|architecture|comparison|matrix|timeline|chart","title":"optional generated visual title","items":["short labels"]},
                  "table_edits": [],
                  "chart_edits": []
                }
              ]
            }
            Rules:
            - Use Simplified Chinese unless the user's prompt explicitly requests another language.
            - Plan against slideLibrary, not source order. You may reuse, skip, and reorder template slides.
            - Choose source_slide by layout affordance: cover, contents, section divider, dense content, comparison, result, conclusion, thanks.
            - Uploaded templates are visual style references only. Do not reuse original body text, sample placeholders, original pictures, original chart data, or example table content.
            - Reuse only background board, palette, title/font style, page layout rhythm, header/footer style, logo, decorative lines, and structural frames.
            - Every replacement must target an existing slot_id from slideLibrary. Do not invent slot ids.
            - Keep text within each slot's visual capacity. Use geometry and text_metrics.font_size_px. Short label slots need very short text.
            - Prefer replacing title/body placeholders and meaningful labels. Do not replace decorative numbers unless they are contents entries.
            - Each slide should replace at most 4 text slots. Cover and contents slides may use up to 6 slots.
            - Replacement text must be concise: titles at most 34 Chinese characters, body slots at most 26 Chinese characters, labels at most 12 Chinese characters.
            - When imageManifest contains useful evidence, assign important figures/tables to image_edits on template slides with image_regions. Match image_id to the slide message; do not use decorative or low-importance images.
            - Prefer image_regions with large geometry for charts, tables, workflows, screenshots, and architecture figures. If no extracted image matches but the slide needs visuals, provide generated_visual so the backend can create a clean editable diagram.
            - Preserve the complete story arc across the requested slide count. Do not keep only early source sections.
            - If targetSlideCount is provided, return exactly that many slides.
            - Use table_edits/chart_edits only for existing native tables/charts from slideLibrary.
            - Speaker notes must be prose, not bullet lists, and must not merely repeat slide text. Keep notes under 55 Chinese characters.
            - All substantive claims must come from sourceText, imageManifest, or the user request.
            - If revisionInstruction or existingDeck is provided, preserve the useful parts of the previous deck and apply the requested text/layout changes instead of restarting with generic filler.
            - Prefer valid complete JSON over exhaustive detail. Do not output extra keys.
            """;
    private static final String TEMPLATE_FILL_REPAIR_PROMPT = TEMPLATE_FILL_PROMPT + """

            You are repairing a fill_plan rejected by the template capacity checker.
            Fix missing slot IDs, shorten overlong text, and remap to better template slides if needed.
            Return the complete corrected JSON plan only.
            """;
    private static final String TEMPLATE_FILL_COMPACT_RETRY_PROMPT = TEMPLATE_FILL_PROMPT + """

            The previous fill_plan response was malformed or too large to parse.
            Return a smaller but complete valid JSON object only.
            Compression rules:
            - Keep exactly targetSlideCount slides when targetSlideCount is present.
            - Each slide should replace at most 4 text slots.
            - Each replacement text is at most 32 Chinese characters unless it is the cover title.
            - notes is at most 45 Chinese characters.
            - Use image_edits only for the most important matching figures.
            - Keep table_edits and chart_edits as empty arrays unless absolutely necessary.
            - Close every array and object. Valid JSON is more important than detail.
            """;
    private static final String TEMPLATE_IMAGE_FILL_SCRIPT = """
            import json, sys
            from pathlib import Path

            from PIL import Image
            from pptx import Presentation
            from pptx.util import Inches

            EMU_PER_INCH = 914400
            PX_PER_INCH = 96

            def px_to_inches(value):
                try:
                    return max(float(value), 0.0) / PX_PER_INCH
                except (TypeError, ValueError):
                    return 0.0

            def contain(image_path, x, y, w, h):
                with Image.open(image_path) as img:
                    iw, ih = img.size
                if iw <= 0 or ih <= 0 or w <= 0 or h <= 0:
                    return x, y, w, h
                scale = min(w / iw, h / ih)
                rendered_w = iw * scale
                rendered_h = ih * scale
                return x + (w - rendered_w) / 2, y + (h - rendered_h) / 2, rendered_w, rendered_h

            pptx_path = Path(sys.argv[1])
            plan = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
            image_paths = json.loads(sys.argv[3])
            prs = Presentation(str(pptx_path))

            for index, slide_plan in enumerate(plan.get("slides", [])):
                if index >= len(prs.slides):
                    continue
                slide = prs.slides[index]
                for edit in slide_plan.get("image_edits", []) or []:
                    image_id = str(edit.get("image_id") or "").strip()
                    image_path = Path(image_paths.get(image_id, ""))
                    geometry = edit.get("geometry") or {}
                    if not image_id or not image_path.is_file():
                        continue
                    x = px_to_inches(geometry.get("x"))
                    y = px_to_inches(geometry.get("y"))
                    w = px_to_inches(geometry.get("width", geometry.get("w")))
                    h = px_to_inches(geometry.get("height", geometry.get("h")))
                    if w < 0.4 or h < 0.4:
                        continue
                    rx, ry, rw, rh = contain(image_path, 0, 0, w * PX_PER_INCH, h * PX_PER_INCH)
                    slide.shapes.add_picture(
                        str(image_path),
                        Inches(x + rx / PX_PER_INCH),
                        Inches(y + ry / PX_PER_INCH),
                        width=Inches(rw / PX_PER_INCH),
                        height=Inches(rh / PX_PER_INCH),
                    )

            prs.save(str(pptx_path))
            print(json.dumps({"ok": True}, ensure_ascii=False))
            """;

    private final PptGenerationConfig config;
    private final PptInputExtractor inputExtractor;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final QuotaService quotaService;
    private final ConcurrentHashMap<String, PptGenerationSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyClaims = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;
    private final SecureRandom secureRandom = new SecureRandom();
    private final PptxQualityGate qualityGate = new PptxQualityGate();
    private Path storageDir;

    private record ProcessResult(int exitCode, String output) {}

    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService llmService, ObjectMapper objectMapper) {
        this(config, inputExtractor, llmService, objectMapper, null);
    }

    @Autowired
    public PptGenerationService(PptGenerationConfig config, PptInputExtractor inputExtractor,
                                LlmService llmService, ObjectMapper objectMapper, QuotaService quotaService) {
        this.config = config;
        this.inputExtractor = inputExtractor;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.quotaService = quotaService;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "ppt-generation-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void initialize() throws IOException {
        storageDir = Path.of(config.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(storageDir);
        loadRecentSessions();
        reconcilePendingRefunds();
        cleanupHistory();
        log.info("PPT 生成队列已启动: workers=1, queueCapacity={}, maxHistory={}, storage={}",
                config.getQueueCapacity(), config.getMaxHistory(), storageDir);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        activeProcesses.forEach(this::terminateProcessTree);
        activeProcesses.clear();
        inputExtractor.shutdown();
    }

    public List<Map<String, Object>> templates() {
        return List.of(
                template("academic-blue", "学术蓝", "清晰克制，适合研究、课程和正式汇报", List.of("005BAC", "063A78", "D9A441", "EFF6FF", "1F2937"), "Marp Core · default", "MIT", "https://github.com/marp-team/marp-core", "academic"),
                template("minimal-ink", "极简黑白", "高对比、留白充足，适合技术分享和决策简报", List.of("111827", "374151", "0EA5E9", "F8FAFC", "1F2937"), "Marp Core · uncover", "MIT", "https://github.com/marp-team/marp-core", "minimal"),
                template("emerald-report", "数据绿", "沉稳、偏报告感，适合项目复盘和经营数据", List.of("047857", "064E3B", "F59E0B", "ECFDF5", "1F2937"), "Marp Core · gaia", "MIT", "https://github.com/marp-team/marp-core", "report"),
                template("warm-defense", "暖色演讲", "温和醒目，适合培训、主题分享和答辩", List.of("B45309", "7C2D12", "2563EB", "FFF7ED", "1F2937"), "Marp Core · gaia", "MIT", "https://github.com/marp-team/marp-core", "warm"),
                template("gaia-editorial", "Gaia 编辑感", "杂志式标题和强章节节奏，适合品牌故事、趋势与案例", List.of("9A3412", "431407", "0F766E", "FFF7ED", "292524"), "Marp Core · gaia", "MIT", "https://github.com/marp-team/marp-core", "editorial"),
                template("uncover-contrast", "Uncover 高对比", "大字号、强聚焦、演讲现场识别度高", List.of("0F172A", "020617", "F97316", "F8FAFC", "E2E8F0"), "Marp Core · uncover", "MIT", "https://github.com/marp-team/marp-core", "contrast"),
                template("dracula-night", "Dracula 夜色", "深色科技感，适合开发者、AI、产品和发布会", List.of("BD93F9", "282A36", "50FA7B", "282A36", "F8F8F2"), "Dracula Marp", "MIT", "https://github.com/dracula/marp", "dark-tech"),
                template("slidev-seriph", "Seriph 叙事", "优雅的衬线标题和细腻层次，适合长文档与知识分享", List.of("2563EB", "172554", "F59E0B", "F8FAFC", "334155"), "Slidev official theme", "MIT", "https://github.com/slidevjs/themes", "seriph"),
                template("slidev-apple-basic", "Apple Basic", "黑白极简和大面积留白，适合产品发布和创意提案", List.of("111827", "000000", "3B82F6", "FFFFFF", "374151"), "Slidev official theme", "MIT", "https://github.com/slidevjs/themes", "apple-basic"),
                template("startup-pitch", "Startup Pitch", "问题—方案—证据—行动，适合融资、产品和商业计划", List.of("7C3AED", "312E81", "F59E0B", "F5F3FF", "1F2937"), "Marp ecosystem", "MIT", "https://github.com/marp-team/awesome-marp", "pitch"),
                template("product-launch", "Product Launch", "大图、指标和场景切换，适合产品发布与增长复盘", List.of("0E7490", "164E63", "F43F5E", "ECFEFF", "164E63"), "Marp ecosystem", "MIT", "https://github.com/marp-team/awesome-marp", "product"),
                template("training-canvas", "Training Canvas", "清楚的模块化教学节奏，适合课程、培训和工作坊", List.of("2563EB", "1E3A8A", "F97316", "EFF6FF", "1E293B"), "Slidev", "MIT", "https://github.com/slidevjs/slidev", "training"),

                // PPT Master: complex native-PPT-inspired editorial, data and grid families.
                template("ppt-master-editorial", "Editorial Magazine", "杂志式图文叙事，适合品牌故事、案例和趋势洞察", List.of("C2410C", "431407", "F59E0B", "FFF7ED", "292524"), "PPT Master", "MIT", "https://github.com/hugohe3/ppt-master", "editorial", "editorial"),
                template("ppt-master-memphis", "Memphis Pop", "几何图形、强色块和活泼节奏，适合活动、教育和创意提案", List.of("F43F5E", "312E81", "FACC15", "FFF1F2", "1E1B4B"), "PPT Master", "MIT", "https://github.com/hugohe3/ppt-master", "memphis", "editorial"),
                template("ppt-master-data-journalism", "Data Journalism", "深色数据新闻风，适合经营分析、行业报告和复杂指标", List.of("38BDF8", "0F172A", "FBBF24", "111827", "E2E8F0"), "PPT Master", "MIT", "https://github.com/hugohe3/ppt-master", "data-journalism", "data"),
                template("ppt-master-swiss-grid", "Swiss Grid", "严格网格、红色强调和咨询感结构，适合策略与方案汇报", List.of("DC2626", "111827", "FDE047", "F8FAFC", "1F2937"), "PPT Master", "MIT", "https://github.com/hugohe3/ppt-master", "swiss-grid", "data"),
                template("ppt-master-glassmorphism", "Glassmorphism SaaS", "半透明层次、渐变深度和产品界面感，适合 SaaS 与 AI 产品", List.of("A78BFA", "111827", "22D3EE", "111827", "F8FAFC"), "PPT Master", "MIT", "https://github.com/hugohe3/ppt-master", "glass-saas", "product"),

                // Presenton: product-oriented open-source theme families.
                template("presenton-glass-saas", "Presenton Glass SaaS", "大图、渐变卡片和场景化产品页，适合商业发布与增长复盘", List.of("8B5CF6", "1E1B4B", "2DD4BF", "F5F3FF", "EDE9FE"), "Presenton", "Apache-2.0", "https://github.com/presenton/presenton", "glass-saas", "product"),
                template("presenton-gradient-pitch", "Presenton Gradient Pitch", "高对比渐变和路演节奏，适合融资、商业计划和产品策略", List.of("F97316", "4C1D95", "FDE68A", "F5F3FF", "312E81"), "Presenton", "Apache-2.0", "https://github.com/presenton/presenton", "gradient-pitch", "product"),
                template("presenton-product-studio", "Presenton Product Studio", "产品截图、指标和双栏证据页，适合产品方案和客户案例", List.of("06B6D4", "164E63", "FB7185", "ECFEFF", "164E63"), "Presenton", "Apache-2.0", "https://github.com/presenton/presenton", "product-studio", "product"),

                // Primer: GitHub-style design-system family, included as a style reference pack.
                template("primer-github-blueprint", "Primer Blueprint", "开源项目蓝图风，适合技术架构、开发者和项目路线图", List.of("0969DA", "1F2328", "54AEFF", "F6F8FA", "1F2328"), "GitHub Primer", "Design system", "https://primer.style/presentations/presentation-formats/powerpoint/", "primer", "corporate"),
                template("primer-data-report", "Primer Data Report", "清晰的企业报告结构，适合季度经营、项目复盘和数据说明", List.of("8250DF", "24292F", "BF8700", "FFFFFF", "24292F"), "GitHub Primer", "Design system", "https://primer.style/presentations/presentation-formats/powerpoint/", "primer-report", "data"),
                template("primer-open-source", "Primer Open Source", "社区与开源项目叙事，适合技术社区、产品生态和发布说明", List.of("1A7F37", "24292F", "9A6700", "F6F8FA", "24292F"), "GitHub Primer", "Design system", "https://primer.style/presentations/presentation-formats/powerpoint/", "primer-open-source", "corporate")
        );
    }

    private Map<String, Object> template(String key, String name, String description, List<String> palette,
                                         String source, String license, String sourceUrl, String design) {
        return template(key, name, description, palette, source, license, sourceUrl, design, categoryForDesign(design));
    }

    private Map<String, Object> template(String key, String name, String description, List<String> palette,
                                         String source, String license, String sourceUrl, String design,
                                         String category) {
        String categoryLabel = switch (category) {
            case "editorial" -> "杂志与创意";
            case "data" -> "数据与咨询";
            case "product" -> "产品与 SaaS";
            case "corporate" -> "企业与开源";
            case "training" -> "课程与培训";
            default -> "基础风格";
        };
        return Map.ofEntries(
                Map.entry("key", key),
                Map.entry("name", name),
                Map.entry("description", description),
                Map.entry("palette", palette),
                Map.entry("source", source),
                Map.entry("license", license),
                Map.entry("sourceUrl", sourceUrl),
                Map.entry("design", design),
                Map.entry("category", category),
                Map.entry("categoryLabel", categoryLabel),
                Map.entry("complexity", Set.of("editorial", "data", "product", "corporate").contains(category) ? "rich" : "standard"),
                Map.entry("supports", List.of("text", "image", "metrics", "charts")),
                Map.entry("recommendedFor", templateRecommendations(design)),
                Map.entry("openSource", !"GitHub Primer".equals(source)),
                Map.entry("usageNote", "GitHub Primer".equals(source)
                        ? "Primer-inspired 风格参考，非官方模板；本项目未分发 Primer 模板文件"
                        : "基于开源项目的风格与布局参考")
        );
    }

    private String categoryForDesign(String design) {
        return switch (design) {
            case "editorial", "memphis" -> "editorial";
            case "report", "data-journalism", "swiss-grid", "primer-report" -> "data";
            case "product", "pitch", "glass-saas", "gradient-pitch", "product-studio", "apple-basic" -> "product";
            case "training" -> "training";
            case "primer", "primer-open-source" -> "corporate";
            default -> "core";
        };
    }

    private List<String> templateRecommendations(String design) {
        return switch (design) {
            case "academic" -> List.of("研究汇报", "课程", "正式报告");
            case "minimal" -> List.of("技术分享", "决策简报", "架构说明");
            case "report" -> List.of("经营数据", "项目复盘", "季度报告");
            case "warm" -> List.of("培训", "答辩", "主题演讲");
            case "editorial" -> List.of("品牌故事", "趋势洞察", "案例研究");
            case "contrast" -> List.of("演讲", "发布会", "大会分享");
            case "dark-tech" -> List.of("AI", "开发者", "产品发布");
            case "seriph" -> List.of("知识分享", "长文档", "读书会");
            case "apple-basic" -> List.of("产品提案", "创意方案", "发布会");
            case "pitch" -> List.of("融资路演", "商业计划", "产品策略");
            case "product" -> List.of("产品发布", "增长复盘", "客户案例");
            case "training" -> List.of("课程", "工作坊", "企业培训");
            case "data-journalism" -> List.of("经营分析", "行业报告", "数据新闻");
            case "swiss-grid" -> List.of("战略咨询", "方案汇报", "复杂结构");
            case "glass-saas" -> List.of("SaaS", "AI 产品", "产品发布");
            case "memphis" -> List.of("创意提案", "活动", "教育内容");
            case "gradient-pitch" -> List.of("融资路演", "商业计划", "产品策略");
            case "product-studio" -> List.of("产品方案", "客户案例", "增长复盘");
            case "primer" -> List.of("技术架构", "开源项目", "路线图");
            case "primer-report" -> List.of("季度报告", "项目复盘", "经营数据");
            case "primer-open-source" -> List.of("技术社区", "产品生态", "开源发布");
            default -> List.of("通用汇报");
        };
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile paperFile) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, paperFile, null);
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile paperFile, AuthUser user) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, paperFile, user, null);
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile paperFile, AuthUser user,
                                           String clientRequestId) throws IOException {
        return createTask(prompt, templateKey, extractionPercent, templateFile, paperFile, user, clientRequestId, "pptx");
    }

    public PptGenerationSession createTask(String prompt, String templateKey, int extractionPercent,
                                           MultipartFile templateFile, MultipartFile paperFile, AuthUser user,
                                           String clientRequestId, String outputFormat) throws IOException {
        String cleanPrompt = validatePrompt(prompt);
        if (cleanPrompt.isBlank() && (paperFile == null || paperFile.isEmpty())) {
            throw new IllegalArgumentException("请输入提示词，或上传一份资料");
        }
        if (cleanPrompt.isBlank()) cleanPrompt = AUTO_PROMPT;
        String normalizedRequestId = normalizeClientRequestId(clientRequestId);
        String claimKey = null;
        if (user != null && !normalizedRequestId.isBlank()) {
            claimKey = user.id() + ":" + normalizedRequestId;
            String claimedTaskId = idempotencyClaims.get(claimKey);
            PptGenerationSession claimedSession = claimedTaskId == null || "__creating__".equals(claimedTaskId)
                    ? null : sessions.get(claimedTaskId);
            if (claimedSession != null && !"error".equals(claimedSession.getStatus())) return claimedSession;
            if (claimedSession != null && claimedSession.isRefundPending()) {
                throw new IllegalStateException("上一次任务正在补偿额度，请稍后再试");
            }
            if (claimedTaskId != null && !"__creating__".equals(claimedTaskId)) {
                idempotencyClaims.remove(claimKey, claimedTaskId);
            }
            PptGenerationSession existing = findIdempotentTask(user.id(), normalizedRequestId);
            if (existing != null) return existing;
            if (idempotencyClaims.putIfAbsent(claimKey, "__creating__") != null) {
                throw new IllegalStateException("任务正在创建，请稍后再试");
            }
        }
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        PptGenerationSession session = new PptGenerationSession(taskId, cleanPrompt, taskDir);
        if (user != null) session.setUserId(user.id());
        session.setClientRequestId(normalizedRequestId.isBlank() ? null : normalizedRequestId);
        session.setAccessToken(newAccessToken());
        session.setTemplateKey(normalizeTemplateKey(templateKey));
        session.setOutputFormat(normalizeOutputFormat(outputFormat));
        // Extraction is automatic. Keep the legacy field at 100 for old task metadata compatibility.
        session.setExtractionPercent(100);

        try {
            if (templateFile != null && !templateFile.isEmpty()) {
                validateFile(templateFile, ".pptx", config.getMaxTemplateBytes(), "PPT 模板");
                session.setTemplateFileName(templateFile.getOriginalFilename());
                copyUpload(templateFile, session.getTemplatePath());
                PptArchiveGuard.validate(session.getTemplatePath(), config.getMaxArchiveEntries(),
                        config.getMaxArchiveUncompressedBytes(), config.getMaxArchiveEntryBytes(),
                        config.getMaxArchiveCompressionRatio());
            }
            if (paperFile != null && !paperFile.isEmpty()) {
                validateSourceFile(paperFile);
                session.setPaperFileName(paperFile.getOriginalFilename());
                copyUpload(paperFile, session.getPaperPath());
                String sourceName = session.getPaperFileName().toLowerCase(Locale.ROOT);
                if (sourceName.endsWith(".docx") || sourceName.endsWith(".pptx") || sourceName.endsWith(".xlsx")) {
                    PptArchiveGuard.validate(session.getPaperPath(), config.getMaxArchiveEntries(),
                            config.getMaxArchiveUncompressedBytes(), config.getMaxArchiveEntryBytes(),
                            config.getMaxArchiveCompressionRatio());
                }
            }
            if (user != null && quotaService != null && !user.isRoot()) {
                int cost = quotaService.pptCreditPerTask();
                long tx = quotaService.spend(user.id(), cost, "PPT", taskId, "PPT 生成任务");
                session.setCreditCost(cost);
                session.setCreditTransactionId(tx);
                session.setCreditRefunded(false);
            }
            session.setOutputFileName(outputFileName(session));
            session.setStatus("queued");
            session.setProgressStage("queued");
            sessions.put(taskId, session);
            if (claimKey != null) idempotencyClaims.put(claimKey, taskId);
            saveMetadata(session);
            executor.execute(() -> runGenerationTask(session));
            updateQueuePositions();
            emit(session, "queued", Map.of("message", "任务已进入 PPT 生成队列", "queuePosition", session.getQueuePosition()));
            return session;
        } catch (RejectedExecutionException e) {
            boolean keepForRefund = persistCreationFailure(session, e, "PPT 队列已满自动退回额度");
            if (!keepForRefund) sessions.remove(taskId);
            if (claimKey != null) {
                if (keepForRefund) idempotencyClaims.put(claimKey, taskId);
                else {
                    idempotencyClaims.remove(claimKey, taskId);
                    idempotencyClaims.remove(claimKey, "__creating__");
                }
            }
            if (!keepForRefund) deleteRecursively(taskDir);
            throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
        } catch (Exception e) {
            boolean keepForRefund = persistCreationFailure(session, e, "PPT 任务创建失败自动退回额度");
            if (!keepForRefund) sessions.remove(taskId);
            if (claimKey != null) {
                if (keepForRefund) idempotencyClaims.put(claimKey, taskId);
                else {
                    idempotencyClaims.remove(claimKey, taskId);
                    idempotencyClaims.remove(claimKey, "__creating__");
                }
            }
            if (!keepForRefund) deleteRecursively(taskDir);
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Create a new generation task from a completed deck. The original source/template/assets stay
     * immutable; the new task receives the revision request and the previous deck/plan as context.
     */
    public PptGenerationSession createRevisionTask(PptGenerationSession original, String revisionPrompt,
                                                   List<Map<String, Object>> slideEdits, AuthUser user,
                                                   String clientRequestId) throws IOException {
        if (original == null || !"completed".equals(original.getStatus())) {
            throw new IllegalArgumentException("只有已生成完成的 PPT 才能二次修改");
        }
        if (user == null || (!user.isRoot() && original.getUserId() != user.id())) {
            throw new IllegalArgumentException("无权修改该 PPT 任务");
        }
        String cleanRevision = validateRevisionPrompt(revisionPrompt);
        String editInstruction = formatSlideEdits(slideEdits);
        if (cleanRevision.isBlank() && editInstruction.isBlank()) {
            throw new IllegalArgumentException("请输入二次修改要求，或编辑至少一页内容");
        }
        String revisionInstruction = String.join("\n\n", Stream.of(cleanRevision, editInstruction)
                .filter(value -> value != null && !value.isBlank()).toList());
        String basePrompt = original.getPrompt() == null ? "" : original.getPrompt();
        String combinedPrompt = validatePrompt(trimForPrompt(basePrompt, 4200)
                + "\n\n【二次修改要求】\n" + trimForPrompt(revisionInstruction, 3600));
        String normalizedRequestId = normalizeClientRequestId(clientRequestId);
        String claimKey = null;
        if (user != null && !normalizedRequestId.isBlank()) {
            claimKey = user.id() + ":" + normalizedRequestId;
            PptGenerationSession existing = findIdempotentTask(user.id(), normalizedRequestId);
            if (existing != null) return existing;
            String claimedTaskId = idempotencyClaims.get(claimKey);
            PptGenerationSession claimedSession = claimedTaskId == null || "__creating__".equals(claimedTaskId)
                    ? null : sessions.get(claimedTaskId);
            if (claimedSession != null && claimedSession.isRefundPending()) {
                throw new IllegalStateException("上一次任务正在补偿额度，请稍后再试");
            }
            if (idempotencyClaims.putIfAbsent(claimKey, "__creating__") != null) {
                throw new IllegalStateException("任务正在创建，请稍后再试");
            }
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path taskDir = Files.createDirectories(storageDir.resolve(taskId));
        PptGenerationSession session = new PptGenerationSession(taskId, combinedPrompt, taskDir);
        session.setUserId(user.id());
        session.setClientRequestId(normalizedRequestId.isBlank() ? null : normalizedRequestId);
        session.setAccessToken(newAccessToken());
        session.setTemplateKey(normalizeTemplateKey(original.getTemplateKey()));
        session.setOutputFormat(normalizeOutputFormat(original.getOutputFormat()));
        session.setTemplateFileName(original.getTemplateFileName());
        session.setPaperFileName(original.getPaperFileName());
        session.setRevisionOfTaskId(original.getTaskId());
        session.setRevisionPrompt(revisionInstruction);
        session.setExtractionPercent(100);

        try {
            if (hasTemplate(original)) {
                Files.copy(original.getTemplatePath(), session.getTemplatePath(), StandardCopyOption.REPLACE_EXISTING);
                PptArchiveGuard.validate(session.getTemplatePath(), config.getMaxArchiveEntries(),
                        config.getMaxArchiveUncompressedBytes(), config.getMaxArchiveEntryBytes(),
                        config.getMaxArchiveCompressionRatio());
            }
            if (hasPaper(original)) {
                Files.copy(original.getPaperPath(), session.getPaperPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            copyDirectoryContents(original.getImagesDir(), session.getImagesDir());
            copyOptional(original.getDeckJsonPath(), taskDir.resolve("revision-base-deck.json"));
            copyOptional(original.getTaskDir().resolve("fill-plan.json"), taskDir.resolve("revision-base-fill-plan.json"));
            copyOptional(original.getStyleJsonPath(), taskDir.resolve("revision-base-style.json"));
            copyOptional(original.getImageManifestPath(), taskDir.resolve("revision-base-image-manifest.json"));
            if (quotaService != null && !user.isRoot()) {
                int cost = quotaService.pptCreditPerTask();
                long tx = quotaService.spend(user.id(), cost, "PPT_REVISION", taskId, "PPT 二次修改");
                session.setCreditCost(cost);
                session.setCreditTransactionId(tx);
                session.setCreditRefunded(false);
            }
            session.setOutputFileName(outputFileName(session));
            session.setStatus("queued");
            session.setProgressStage("queued");
            sessions.put(taskId, session);
            if (claimKey != null) idempotencyClaims.put(claimKey, taskId);
            saveMetadata(session);
            executor.execute(() -> runGenerationTask(session));
            updateQueuePositions();
            emit(session, "queued", Map.of("message", "二次修改任务已进入 PPT 生成队列", "queuePosition", session.getQueuePosition()));
            return session;
        } catch (RejectedExecutionException e) {
            boolean keepForRefund = persistCreationFailure(session, e, "PPT 二次修改队列已满自动退回额度");
            if (!keepForRefund) sessions.remove(taskId);
            if (claimKey != null) {
                if (keepForRefund) idempotencyClaims.put(claimKey, taskId);
                else {
                    idempotencyClaims.remove(claimKey, taskId);
                    idempotencyClaims.remove(claimKey, "__creating__");
                }
            }
            if (!keepForRefund) deleteRecursively(taskDir);
            throw new IllegalStateException("PPT 生成队列已满，请稍后再试");
        } catch (Exception e) {
            boolean keepForRefund = persistCreationFailure(session, e, "PPT 二次修改创建失败自动退回额度");
            if (!keepForRefund) sessions.remove(taskId);
            if (claimKey != null) {
                if (keepForRefund) idempotencyClaims.put(claimKey, taskId);
                else {
                    idempotencyClaims.remove(claimKey, taskId);
                    idempotencyClaims.remove(claimKey, "__creating__");
                }
            }
            if (!keepForRefund) deleteRecursively(taskDir);
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public Map<String, Object> preview(PptGenerationSession session) throws IOException {
        if (session == null || !"completed".equals(session.getStatus())) {
            throw new IllegalArgumentException("PPT 尚未生成完成，暂时无法预览");
        }
        Path previewPath = session.getPreviewPath();
        if (Files.isRegularFile(previewPath)) {
            try {
                return objectMapper.readValue(previewPath.toFile(), Map.class);
            } catch (Exception ignored) {
                // Rebuild a stale/partially written preview below.
            }
        }
        Map<String, Object> preview = buildPreview(session);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(previewPath.toFile(), preview);
        return preview;
    }

    private void renderHtmlOutput(PptGenerationSession session, Map<String, Object> preview) throws IOException {
        new PptHtmlRenderer(objectMapper).render(session.getHtmlOutputPath(), preview, session.getImagesDir());
        if (!Files.isRegularFile(session.getHtmlOutputPath()) || Files.size(session.getHtmlOutputPath()) < 256) {
            throw new IllegalStateException("HTML 输出文件不存在");
        }
        String html = Files.readString(session.getHtmlOutputPath(), StandardCharsets.UTF_8);
        if (!html.contains("<!doctype html>") || !html.contains("id=\"ppt-data\"")
                || !html.contains("id=\"deck\"") || !html.contains("<script>")) {
            throw new IllegalStateException("HTML 输出结构不完整");
        }
    }

    public Path previewImage(PptGenerationSession session, String fileName) {
        if (session == null || !"completed".equals(session.getStatus())) {
            throw new IllegalArgumentException("PPT 尚未生成完成");
        }
        String safeName = fileName == null ? "" : fileName.trim();
        if (safeName.isBlank() || safeName.contains("/") || safeName.contains("\\") || !safeName.equals(Path.of(safeName).getFileName().toString())) {
            throw new IllegalArgumentException("预览素材不存在");
        }
        Path imagesDir = session.getImagesDir().toAbsolutePath().normalize();
        Path image = imagesDir.resolve(safeName).normalize();
        if (!image.startsWith(imagesDir) || !Files.isRegularFile(image)) {
            throw new IllegalArgumentException("预览素材不存在");
        }
        return image;
    }

    private Map<String, Object> buildPreview(PptGenerationSession session) throws IOException {
        JsonNode style = Files.isRegularFile(session.getStyleJsonPath())
                ? objectMapper.readTree(session.getStyleJsonPath().toFile()) : objectMapper.createObjectNode();
        JsonNode manifest = Files.isRegularFile(session.getImageManifestPath())
                ? objectMapper.readTree(session.getImageManifestPath().toFile()) : objectMapper.createObjectNode();
        JsonNode deck = Files.isRegularFile(session.getDeckJsonPath())
                ? objectMapper.readTree(session.getDeckJsonPath().toFile()) : null;
        List<Map<String, Object>> slides = new ArrayList<>();
        String source = "deck";
        if (deck != null && deck.path("slides").isArray()) {
            for (JsonNode slide : deck.path("slides")) slides.add(previewSlide(slide, manifest));
        } else {
            source = "template-plan";
            Path planPath = session.getTaskDir().resolve("fill-plan.json");
            JsonNode plan = Files.isRegularFile(planPath) ? objectMapper.readTree(planPath.toFile()) : null;
            if (plan != null && plan.path("slides").isArray()) {
                for (JsonNode slide : plan.path("slides")) slides.add(previewTemplateSlide(slide, manifest));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", session.getTaskId());
        result.put("source", source);
        result.put("title", deck == null ? "AI 生成 PPT" : textOr(deck, "title", "AI 生成 PPT"));
        result.put("theme", deck == null ? textOr(style, "builtInTemplateName", "专业演示") : textOr(deck, "theme", "专业演示"));
        result.put("palette", safePalette(style.path("palette")));
        result.put("templateKey", session.getTemplateKey());
        result.put("outputFormat", session.getOutputFormat());
        result.put("templateDesign", textOr(style, "builtInTemplateDesign", "academic"));
        result.put("templateName", textOr(style, "builtInTemplateName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName()));
        result.put("templateSource", textOr(style, "builtInTemplateSource", ""));
        result.put("templateLicense", textOr(style, "builtInTemplateLicense", ""));
        result.put("templateUrl", textOr(style, "builtInTemplateUrl", ""));
        String previewRoute = textOr(style, "templateRoute", hasTemplate(session) ? "template-fill" : "framework");
        result.put("templateRoute", previewRoute);
        result.put("previewFidelity", Set.of("template-fill", "native-fill").contains(previewRoute.toLowerCase(Locale.ROOT))
                ? "approximate-native-template" : "renderer-preview");
        result.put("slides", slides);
        return result;
    }

    private Map<String, Object> previewSlide(JsonNode slide, JsonNode manifest) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", safeSlideType(textOr(slide, "type", "content")));
        result.put("section", textOr(slide, "section", ""));
        result.put("title", textOr(slide, "title", "未命名页面"));
        result.put("headline", textOr(slide, "headline", ""));
        result.put("bullets", compactStringList(slide.path("bullets"), 6, 100));
        result.put("metrics", compactMetrics(slide.path("metrics")));
        result.put("layout", textOr(slide, "layout", "auto"));
        String imageId = textOr(slide, "imageId", "");
        result.put("imageId", imageId);
        result.put("imageFile", imageFileForId(manifest, imageId));
        return result;
    }

    private Map<String, Object> previewTemplateSlide(JsonNode slide, JsonNode manifest) {
        Map<String, Object> result = new LinkedHashMap<>();
        String purpose = textOr(slide, "purpose", "content");
        result.put("type", safeSlideType(purpose));
        result.put("section", purpose.toUpperCase(Locale.ROOT));
        List<String> replacements = new ArrayList<>();
        JsonNode replacementNode = slide.path("replacements");
        if (replacementNode.isArray()) {
            replacementNode.forEach(item -> {
                String text = textOr(item, "text", "");
                if (!text.isBlank()) replacements.add(text);
            });
        }
        String title = replacements.isEmpty() ? "模板页面" : replacements.get(0);
        List<String> bullets = replacements.size() <= 1 ? List.of() : replacements.subList(1, Math.min(replacements.size(), 7));
        result.put("title", title);
        result.put("headline", textOr(slide, "layoutReason", ""));
        result.put("bullets", bullets);
        result.put("metrics", List.of());
        result.put("layout", "auto");
        JsonNode edits = slide.path("image_edits");
        String imageId = edits.isArray() && edits.size() > 0 ? textOr(edits.get(0), "image_id", "") : "";
        result.put("imageId", imageId);
        result.put("imageFile", imageFileForId(manifest, imageId));
        return result;
    }

    private List<String> compactStringList(JsonNode node, int maxItems, int maxChars) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(trimForPrompt(value, maxChars));
            if (values.size() >= maxItems) break;
        }
        return values;
    }

    private List<Map<String, Object>> compactMetrics(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) continue;
            values.add(Map.of("value", trimForPrompt(textOr(item, "value", ""), 24),
                    "label", trimForPrompt(textOr(item, "label", ""), 30)));
            if (values.size() >= 3) break;
        }
        return values;
    }

    private String safeSlideType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return SAFE_SLIDE_TYPES.contains(normalized) ? normalized : "content";
    }

    private List<String> safePalette(JsonNode node) {
        List<String> fallback = List.of("005BAC", "063A78", "D9A441", "EFF6FF", "1F2937");
        if (node == null || !node.isArray()) return fallback;
        List<String> result = new ArrayList<>();
        for (int index = 0; index < fallback.size(); index++) {
            String candidate = index < node.size() ? node.get(index).asText("").trim() : "";
            result.add(HEX_COLOR.matcher(candidate).matches()
                    ? candidate.replace("#", "") : fallback.get(index));
        }
        return result;
    }

    private String imageFileForId(JsonNode manifest, String imageId) {
        if (imageId == null || imageId.isBlank() || manifest == null || !manifest.path("images").isArray()) return "";
        for (JsonNode image : manifest.path("images")) {
            if (imageId.equals(image.path("id").asText(""))) return image.path("filename").asText("");
        }
        return "";
    }

    PptGenerationSession getSession(String taskId) {
        updateQueuePositions();
        return sessions.get(taskId);
    }

    public boolean canAccess(PptGenerationSession session, AuthUser user) {
        return session != null && user != null && (user.isRoot() || session.getUserId() == user.id());
    }

    public PptGenerationSession getAuthorizedSession(String taskId, String accessToken) {
        updateQueuePositions();
        return requireAuthorizedSession(taskId, accessToken);
    }

    public List<PptGenerationSession> getRecentSessions(String accessTokens) {
        updateQueuePositions();
        Set<String> allowedTokens = parseAccessTokens(accessTokens);
        if (allowedTokens.isEmpty()) return List.of();
        return sessions.values().stream()
                .filter(session -> allowedTokens.contains(session.getAccessToken()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed())
                .limit(Math.max(1, config.getMaxHistory()))
                .toList();
    }

    public List<PptGenerationSession> getRecentSessions(AuthUser user, String accessTokens) {
        updateQueuePositions();
        Set<String> allowedTokens = parseAccessTokens(accessTokens);
        return sessions.values().stream()
                .filter(session -> Set.of("completed", "error").contains(session.getStatus()))
                .filter(session -> (user != null && (user.isRoot() || session.getUserId() == user.id()))
                        || allowedTokens.contains(session.getAccessToken()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed())
                .limit(Math.max(1, config.getMaxHistory()))
                .toList();
    }

    Path getOutput(String taskId) {
        PptGenerationSession session = requireSession(taskId);
        return getOutput(session);
    }

    public Path getOutput(String taskId, String accessToken) {
        return getOutput(requireAuthorizedSession(taskId, accessToken));
    }

    private Path getOutput(PptGenerationSession session) {
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("PPT 尚未生成完成");
        }
        Path output = session.getOutputPath();
        if (!Files.isRegularFile(output)) {
            throw new IllegalStateException("PPT 文件不存在，请重新生成");
        }
        return output;
    }

    public void subscribe(String taskId, String accessToken, SseEmitter emitter) {
        PptGenerationSession session;
        try {
            session = requireAuthorizedSession(taskId, accessToken);
        } catch (IllegalArgumentException e) {
            sendAndComplete(emitter, "task-error", Map.of("message", "任务不存在"));
            return;
        }
        subscribe(session, emitter);
    }

    public void subscribe(PptGenerationSession session, SseEmitter emitter) {
        if (session == null) {
            sendAndComplete(emitter, "task-error", Map.of("message", "任务不存在"));
            return;
        }
        String taskId = session.getTaskId();
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> removeEmitter(taskId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        sendSnapshot(session, emitter);
    }

    private void runGenerationTask(PptGenerationSession session) {
        session.setStatus("generating");
        session.setQueuePosition(0);
        saveAndProgress(session, 8, "extracting", "正在读取资料和模板");
        updateQueuePositions();

        try {
            Files.createDirectories(session.getImagesDir());
            Optional<Integer> targetSlideCount = effectiveTargetSlideCount(session);
            int imageBudget = imageBudgetFor(session, targetSlideCount);
            int minCandidateImages = minCandidateImagesFor(session, targetSlideCount, imageBudget);
            String paperText = inputExtractor.extractPaperText(
                    hasPaper(session) ? session.getPaperPath() : null,
                    session.getPaperFileName(),
                    session.getImagesDir(),
                    100,
                    imageBudget,
                    minCandidateImages);
            inputExtractor.extractTemplateStyle(hasTemplate(session) ? session.getTemplatePath() : null,
                    session.getTaskDir(), 100);
            applyBuiltInTemplateStyle(session);
            List<String> imagePaths = new ArrayList<>(inputExtractor.listImagePaths(session.getImagesDir()));
            JsonNode imageManifest = buildImageManifest(session, imagePaths);

            String templateRoute = readStyleField(session, "templateRoute", "");
            boolean useNativeTemplate = hasTemplate(session)
                    && (templateRoute.isBlank() || Set.of("template-fill", "native-fill").contains(templateRoute.toLowerCase(Locale.ROOT)));
            if (useNativeTemplate) {
                log.info("PPT 生成使用上传模板原生填充链路: taskId={}, template={}, source={}",
                        session.getTaskId(), session.getTemplateFileName(), session.getPaperFileName());
                saveAndProgress(session, 28, "template_analyzing", "正在分析上传 PPT 模板槽位");
                JsonNode slideLibrary = analyzeTemplateLibrary(session);
                saveAndProgress(session, 42, "planning", "正在按模板槽位规划 PPT");
                String templatePaperText = compactPaperTextForTemplate(paperText);
                JsonNode fillPlan = buildTemplateFillPlan(session, templatePaperText, imageManifest, slideLibrary);
                saveAndProgress(session, 62, "template_checking", "正在检查模板填充容量");
                fillPlan = ensureTemplateImageAssignments(fillPlan, imageManifest, slideLibrary);
                imageManifest = ensureGeneratedVisualsForTemplate(session, fillPlan, imageManifest, slideLibrary);
                imagePaths = new ArrayList<>(inputExtractor.listImagePaths(session.getImagesDir()));
                fillPlan = ensureTemplateImageAssignments(fillPlan, imageManifest, slideLibrary);
                writeTemplateFillPlan(session, fillPlan, "fill-plan.json");
                fillPlan = checkAndRepairTemplateFillPlan(session, fillPlan, slideLibrary, templatePaperText, imageManifest);
                fillPlan = ensureTemplateImageAssignments(fillPlan, imageManifest, slideLibrary);
                imageManifest = ensureGeneratedVisualsForTemplate(session, fillPlan, imageManifest, slideLibrary);
                imagePaths = new ArrayList<>(inputExtractor.listImagePaths(session.getImagesDir()));
                fillPlan = ensureTemplateImageAssignments(fillPlan, imageManifest, slideLibrary);
                writeTemplateFillPlan(session, fillPlan, "fill-plan.json");
                writeTemplateImageFillReport(session, fillPlan, imageManifest, slideLibrary);
                saveAndProgress(session, 76, "rendering", "正在原生填充 PPT 模板");
                runTemplateFillApply(session, fillPlan, imagePaths);
                int removedParts = PptxPackageCleaner.clean(session.getPptxOutputPath());
                if (removedParts > 0) {
                    log.info("PPT 模板输出清理未引用资源: taskId={}, removedParts={}", session.getTaskId(), removedParts);
                }
            } else {
                log.info("PPT 生成使用自由 python renderer 链路: taskId={}, source={}",
                        session.getTaskId(), session.getPaperFileName());
                saveAndProgress(session, 36, "planning", "正在调用 mimo 规划 PPT 内容");
                String deckJson = buildDeckJson(session, paperText, imageManifest);
                Files.writeString(session.getDeckJsonPath(), deckJson, StandardCharsets.UTF_8);
                JsonNode renderDeck = objectMapper.readTree(deckJson);
                JsonNode assignedDeck = ensureImageAssignments(renderDeck, imageManifest);
                imageManifest = ensureGeneratedVisualsForDeck(session, assignedDeck, imageManifest);
                imagePaths = new ArrayList<>(inputExtractor.listImagePaths(session.getImagesDir()));
                assignedDeck = ensureImageAssignments(assignedDeck, imageManifest);
                assignedDeck = attachTemplateMetadata(session, assignedDeck, imageManifest);
                Files.writeString(session.getDeckJsonPath(),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(assignedDeck), StandardCharsets.UTF_8);

                saveAndProgress(session, 72, "rendering", "正在直接生成 PPTX");
                runPythonRenderer(session, imagePaths);
            }

            // Never expose a partially written or structurally broken package as a completed result.
            qualityGate.validate(session.getPptxOutputPath(), targetSlideCount.orElse(null));
            Files.writeString(session.getTaskDir().resolve("quality-report.json"),
                    objectMapper.createObjectNode()
                            .put("valid", true)
                            .put("expectedSlideCount", targetSlideCount.orElse(-1))
                            .put("checkedAt", System.currentTimeMillis())
                            .toString(), StandardCharsets.UTF_8);
            try {
                Map<String, Object> preview = buildPreview(session);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getPreviewPath().toFile(), preview);
                if ("html".equalsIgnoreCase(session.getOutputFormat())) {
                    renderHtmlOutput(session, preview);
                }
            } catch (Exception previewError) {
                if ("html".equalsIgnoreCase(session.getOutputFormat())) {
                    throw new IllegalStateException("HTML 输出生成失败: " + previewError.getMessage(), previewError);
                }
                log.warn("PPT 网页预览数据生成失败，仍保留可下载 PPTX: taskId={}", session.getTaskId(), previewError);
            }
            session.setStatus("completed");
            session.setProgress(100);
            session.setProgressStage("completed");
            session.setCompletedAt(System.currentTimeMillis());
            saveMetadata(session);
            emit(session, "done", Map.of("taskId", session.getTaskId()));
            completeEmitters(session.getTaskId());
        } catch (Exception e) {
            failTask(session, e);
        } finally {
            cleanupHistory();
            updateQueuePositions();
        }
    }

    private String buildDeckJson(PptGenerationSession session, String sourceText, JsonNode imageManifest) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userPrompt", session.getPrompt());
        payload.put("sourceFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        payload.put("sourceText", sourceText == null ? "" : sourceText);
        payload.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
        payload.put("outputFormat", session.getOutputFormat());
        payload.put("templateStyle", objectMapper.readValue(session.getStyleJsonPath().toFile(), Map.class));
        payload.put("templateFit", readStyleField(session, "templateFit", "weak"));
        payload.put("templateRoute", readStyleField(session, "templateRoute", "framework"));
        payload.put("builtInTemplate", templateByKey(session.getTemplateKey()));
        Optional<Integer> targetSlideCount = effectiveTargetSlideCount(session);
        payload.put("targetSlideCount", targetSlideCount.orElse(null));
        payload.put("defenseMode", defenseMode(session));
        payload.put("slideCountPolicy", slideCountPolicy(session, targetSlideCount));
        payload.put("visualPolicy", visualPolicy(session));
        payload.put("imageManifest", imageManifest == null || imageManifest.isNull() ? Map.of("images", List.of()) : imageManifest);
        addRevisionContext(payload, session, "deck");
        String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        int maxTokens = deckMaxTokens(targetSlideCount);
        String response = llmService.complete(deckSystemPrompt(session), payloadJson, maxTokens);
        JsonNode root;
        try {
            root = parseLlmJson(response);
        } catch (Exception firstError) {
            if (!looksLikeTruncatedJson(response, firstError)) {
                if (firstError instanceof IOException io) throw io;
                throw firstError;
            }
            log.warn("PPT 结构 JSON 疑似被截断，使用紧凑格式重试: taskId={}, maxTokens={}, error={}",
                    session.getTaskId(), maxTokens, firstError.getMessage());
            Map<String, Object> retryPayload = new LinkedHashMap<>(payload);
            retryPayload.put("retryReason", "Previous response was truncated. Return compact complete JSON only.");
            retryPayload.put("outputBudget", "Use short titles, max 3 bullets per slide, short notes, and close the JSON.");
            String retryResponse = llmService.complete(compactRetryPrompt(session),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(retryPayload),
                    Math.min(32768, maxTokens + 4096));
            root = parseLlmJson(retryResponse);
        }
        root = repairSlideCountIfNeeded(payload, root, targetSlideCount);
        root = ensureImageAssignments(root, imageManifest);
        root = attachTemplateMetadata(session, root, imageManifest);
        validateDeck(root);
        if (!root.hasNonNull("slides") || !root.get("slides").isArray() || root.get("slides").isEmpty()) {
            throw new IllegalStateException("mimo 返回的 PPT 结构缺少 slides");
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private void addRevisionContext(Map<String, Object> payload, PptGenerationSession session, String kind) {
        if (session == null || session.getRevisionPrompt() == null || session.getRevisionPrompt().isBlank()) return;
        payload.put("revisionInstruction", trimForPrompt(session.getRevisionPrompt(), 3600));
        String fileName = "deck".equals(kind) ? "revision-base-deck.json" : "revision-base-fill-plan.json";
        Path base = session.getTaskDir().resolve(fileName);
        if (!Files.isRegularFile(base)) return;
        try {
            payload.put("existingDeck", objectMapper.readTree(base.toFile()));
        } catch (IOException e) {
            log.warn("读取 PPT 二次修改基线失败: taskId={}, file={}", session.getTaskId(), base, e);
        }
    }

    private JsonNode repairSlideCountIfNeeded(Map<String, Object> originalPayload, JsonNode deck, Optional<Integer> targetSlideCount) throws IOException {
        if (targetSlideCount.isEmpty()) return deck;
        JsonNode slides = deck == null ? null : deck.get("slides");
        int currentCount = slides != null && slides.isArray() ? slides.size() : 0;
        int requiredCount = targetSlideCount.get();
        if (currentCount == requiredCount) return deck;

        Map<String, Object> repairPayload = new LinkedHashMap<>(originalPayload);
        repairPayload.put("requiredSlideCount", requiredCount);
        repairPayload.put("previousSlideCount", currentCount);
        repairPayload.put("currentDeck", deck);
        repairPayload.put("repairInstruction", "Revise currentDeck to exactly requiredSlideCount slides. Merge, compress, or expand across the whole source material; do not truncate early sections.");
        String response = llmService.complete(slideCountRepairPrompt(),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(repairPayload),
                deckMaxTokens(targetSlideCount));
        JsonNode repaired = parseLlmJson(response);
        JsonNode repairedSlides = repaired == null ? null : repaired.get("slides");
        int repairedCount = repairedSlides != null && repairedSlides.isArray() ? repairedSlides.size() : 0;
        if (repairedCount != requiredCount) {
            log.warn("mimo PPT 页数修复后仍不匹配，使用本地兜底调整: required={}, actual={}",
                    requiredCount, repairedCount);
            return locallyRepairSlideCount(repaired, requiredCount);
        }
        return repaired;
    }

    private JsonNode locallyRepairSlideCount(JsonNode deck, int requiredCount) {
        if (!(deck instanceof com.fasterxml.jackson.databind.node.ObjectNode objectDeck)) {
            throw new IllegalStateException("mimo 返回的 PPT 页数不符合要求: required=" + requiredCount + ", actual=0");
        }
        JsonNode slides = objectDeck.path("slides");
        if (!slides.isArray() || slides.isEmpty()) {
            throw new IllegalStateException("mimo 返回的 PPT 页数不符合要求: required=" + requiredCount + ", actual=0");
        }
        if (requiredCount < 1 || requiredCount > 40) {
            throw new IllegalArgumentException("PPT 页数超过 40 页限制");
        }

        com.fasterxml.jackson.databind.node.ArrayNode repaired = objectMapper.createArrayNode();
        slides.forEach(repaired::add);
        while (repaired.size() < requiredCount) {
            int insertAt = Math.max(1, thanksSlideIndex(repaired));
            repaired.insert(insertAt, supplementalSlide(insertAt + 1, requiredCount));
        }
        while (repaired.size() > requiredCount) {
            repaired.remove(removableSlideIndex(repaired));
        }
        objectDeck.set("slides", repaired);
        objectDeck.put("slideCountAdjustedLocally", true);
        return objectDeck;
    }

    private int thanksSlideIndex(JsonNode slides) {
        for (int index = slides.size() - 1; index >= 0; index--) {
            if ("thanks".equals(slides.get(index).path("type").asText(""))) return index;
        }
        return slides.size();
    }

    private int removableSlideIndex(JsonNode slides) {
        for (int index = slides.size() - 2; index > 0; index--) {
            String type = slides.get(index).path("type").asText("content");
            if (!Set.of("cover", "thanks").contains(type)) return index;
        }
        return Math.max(0, slides.size() - 1);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode supplementalSlide(int ordinal, int requiredCount) {
        com.fasterxml.jackson.databind.node.ObjectNode slide = objectMapper.createObjectNode();
        slide.put("type", "content");
        slide.put("section", "SUPPLEMENT");
        slide.put("title", "补充分析 " + ordinal + "/" + requiredCount);
        slide.put("headline", "围绕核心问题补充说明研究依据与实现细节");
        com.fasterxml.jackson.databind.node.ArrayNode bullets = slide.putArray("bullets");
        bullets.add("补充研究背景与问题边界");
        bullets.add("梳理方法设计中的关键取舍");
        bullets.add("承接前后章节形成完整叙事");
        slide.put("notes", "本页由系统在页数校验阶段补足，可按实际内容继续调整。");
        slide.put("layout", "auto");
        slide.put("localSlideCountFallback", true);
        return slide;
    }

    private JsonNode analyzeTemplateLibrary(PptGenerationSession session) throws IOException, InterruptedException {
        Path libraryPath = session.getTaskDir().resolve("slide-library.json");
        runTemplateFillCommand(List.of(
                "analyze",
                session.getTemplatePath().toString(),
                "-o",
                libraryPath.toString()
        ), "PPT 模板分析");
        if (!Files.isRegularFile(libraryPath)) {
            throw new IllegalStateException("PPT 模板分析未输出 slide-library.json");
        }
        return objectMapper.readTree(libraryPath.toFile());
    }

    private JsonNode buildTemplateFillPlan(PptGenerationSession session, String sourceText,
                                           JsonNode imageManifest, JsonNode slideLibrary) throws IOException {
        Optional<Integer> targetSlideCount = effectiveTargetSlideCount(session);
        if (targetSlideCount.isPresent() && targetSlideCount.get() > 8) {
            return buildTemplateFillPlanBatched(session, sourceText, imageManifest, slideLibrary, targetSlideCount.get());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userPrompt", session.getPrompt());
        payload.put("sourceFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
        payload.put("sourceText", sourceText == null ? "" : sourceText);
        payload.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
        payload.put("targetSlideCount", targetSlideCount.orElse(null));
        payload.put("defenseMode", defenseMode(session));
        payload.put("imageManifest", imageManifest == null || imageManifest.isNull() ? Map.of("images", List.of()) : imageManifest);
        payload.put("slideLibrary", compactSlideLibrary(slideLibrary));
        addRevisionContext(payload, session, "fill-plan");
        payload.put("planningInstruction", "Choose template slides by layout affordance. Reuse or skip source slides as needed. Use exact slot_id values only.");
        JsonNode plan = requestTemplateFillPlan(session, payload, "fill-plan");
        enforceTemplateSlideCount(plan, targetSlideCount);
        writeTemplateFillPlan(session, plan, "fill-plan.json");
        return plan;
    }

    private JsonNode buildTemplateFillPlanBatched(PptGenerationSession session, String sourceText,
                                                  JsonNode imageManifest, JsonNode slideLibrary,
                                                  int requestedSlides) throws IOException {
        int totalSlides = clamp(requestedSlides, 3, 40);
        int batchSize = 6;
        com.fasterxml.jackson.databind.node.ObjectNode merged = objectMapper.createObjectNode();
        merged.put("schema", "template_fill_pptx_plan.v1");
        merged.put("source_pptx", "template.pptx");
        com.fasterxml.jackson.databind.node.ArrayNode mergedSlides = objectMapper.createArrayNode();
        JsonNode compactLibrary = compactSlideLibrary(slideLibrary);
        for (int start = 0, batchIndex = 1; start < totalSlides; start += batchSize, batchIndex++) {
            int count = Math.min(batchSize, totalSlides - start);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userPrompt", session.getPrompt());
            payload.put("sourceFileName", session.getPaperFileName() == null ? "" : session.getPaperFileName());
            payload.put("sourceText", sourceText == null ? "" : sourceText);
            payload.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
            payload.put("targetSlideCount", count);
            payload.put("overallTargetSlideCount", totalSlides);
            payload.put("defenseMode", defenseMode(session));
            payload.put("batchIndex", batchIndex);
            payload.put("slideRange", Map.of("from", start + 1, "to", start + count));
            payload.put("batchStoryFocus", templateBatchFocus(start, count, totalSlides));
            payload.put("imageManifest", imageManifest == null || imageManifest.isNull() ? Map.of("images", List.of()) : imageManifest);
            payload.put("slideLibrary", compactLibrary);
            addRevisionContext(payload, session, "fill-plan");
            payload.put("planningInstruction", "Plan only this slideRange and return exactly targetSlideCount slides. Do not include slides outside this range. Use exact slot_id values only.");
            JsonNode batchPlan = requestTemplateFillPlan(session, payload, "fill-plan-batch-" + batchIndex);
            JsonNode slides = batchPlan.path("slides");
            if (slides.isArray()) {
                slides.forEach(mergedSlides::add);
            }
            int totalBatches = (totalSlides + batchSize - 1) / batchSize;
            saveAndProgress(session,
                    Math.min(58, 42 + batchIndex * 3),
                    "planning",
                    "正在按模板槽位分批规划 PPT（第 " + batchIndex + "/" + totalBatches + " 批）");
        }
        merged.set("slides", mergedSlides);
        JsonNode plan = normalizeTemplateFillPlan(merged);
        validateTemplateFillPlan(plan);
        enforceTemplateSlideCount(plan, Optional.of(totalSlides));
        writeTemplateFillPlan(session, plan, "fill-plan.json");
        return plan;
    }

    private JsonNode requestTemplateFillPlan(PptGenerationSession session, Map<String, Object> payload,
                                             String debugName) throws IOException {
        String response = llmService.complete(TEMPLATE_FILL_PROMPT,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                templateFillMaxTokens(payload.get("targetSlideCount")));
        JsonNode plan;
        try {
            plan = parseLlmJson(response);
        } catch (Exception firstError) {
            if (!shouldRetryTemplateFillJson(response, firstError)) {
                if (firstError instanceof IOException io) throw io;
                throw firstError;
            }
            Path rawPath = session.getTaskDir().resolve(debugName + "-malformed-response.txt");
            Files.writeString(rawPath, response == null ? "" : response, StandardCharsets.UTF_8);
            log.warn("PPT 模板填充计划 JSON 解析失败，使用紧凑格式重试: taskId={}, error={}",
                    session.getTaskId(), firstError.getMessage());
            Map<String, Object> retryPayload = new LinkedHashMap<>(payload);
            retryPayload.put("retryReason", "Previous fill_plan was malformed. Return compact complete JSON only.");
            retryPayload.put("outputBudget", "Use short text, few replacements per slide, short notes, and close the JSON.");
            String retryResponse = llmService.complete(TEMPLATE_FILL_COMPACT_RETRY_PROMPT,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(retryPayload),
                    Math.min(32768, templateFillMaxTokens(payload.get("targetSlideCount"))));
            plan = parseLlmJson(retryResponse);
        }
        plan = normalizeTemplateFillPlan(plan);
        validateTemplateFillPlan(plan);
        if (payload.get("targetSlideCount") instanceof Number number) {
            Optional<Integer> required = Optional.of(clamp(number.intValue(), 1, 40));
            if (!templateSlideCountMatches(plan, required)) {
                Map<String, Object> retryPayload = new LinkedHashMap<>(payload);
                retryPayload.put("retryReason", "Previous fill_plan had the wrong slide count.");
                retryPayload.put("currentFillPlan", plan);
                retryPayload.put("repairInstruction", "Return exactly targetSlideCount slides.");
                String retryResponse = llmService.complete(TEMPLATE_FILL_COMPACT_RETRY_PROMPT,
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(retryPayload),
                        Math.min(32768, templateFillMaxTokens(payload.get("targetSlideCount"))));
                plan = normalizeTemplateFillPlan(parseLlmJson(retryResponse));
                validateTemplateFillPlan(plan);
                enforceTemplateSlideCount(plan, required);
            }
        }
        return plan;
    }

    private String templateBatchFocus(int start, int count, int total) {
        double position = total <= 1 ? 0 : (double) start / total;
        boolean first = start == 0;
        boolean last = start + count >= total;
        if (first) return "cover, agenda, context, goals and key message";
        if (last) return "results synthesis, conclusions, recommendations, next steps and thanks";
        if (position < 0.35) return "context, audience needs, goals, requirements and overall approach";
        if (position < 0.7) return "core content, process, solution, examples, evidence and implementation";
        return "results, comparison, impact, practical value and decisions";
    }

    private JsonNode checkAndRepairTemplateFillPlan(PptGenerationSession session, JsonNode fillPlan,
                                                    JsonNode slideLibrary, String paperText,
                                                    JsonNode imageManifest) throws IOException, InterruptedException {
        Path reportPath = session.getTaskDir().resolve("fill-check-report.json");
        int exit = runTemplateFillCommandAllowFailure(List.of(
                "check-plan",
                session.getTaskDir().resolve("slide-library.json").toString(),
                session.getTaskDir().resolve("fill-plan.json").toString(),
                "-o",
                reportPath.toString()
        ), "PPT 模板填充检查");
        JsonNode report = Files.isRegularFile(reportPath)
                ? objectMapper.readTree(reportPath.toFile())
                : objectMapper.createObjectNode().put("message", "check-plan failed without report");
        boolean reportValid = report != null && report.isObject()
                && (report.path("summary").isObject() || report.has("ok"));
        int warnings = report.path("summary").path("warn").asInt(0);
        int errors = report.path("summary").isObject()
                ? report.path("summary").path("error").asInt(exit == 0 ? 0 : 1)
                : (report.path("ok").asBoolean(false) && exit == 0 ? 0 : 1);
        if (exit == 0 && reportValid && errors == 0 && warnings <= Math.max(0, config.getMaxTemplateWarnings())) {
            return fillPlan;
        }
        Map<String, Object> repairPayload = new LinkedHashMap<>();
        repairPayload.put("userPrompt", session.getPrompt());
        repairPayload.put("sourceText", paperText == null ? "" : paperText);
        repairPayload.put("imageManifest", imageManifest == null || imageManifest.isNull() ? Map.of("images", List.of()) : imageManifest);
        repairPayload.put("slideLibrary", compactSlideLibrary(slideLibrary));
        repairPayload.put("currentFillPlan", fillPlan);
        repairPayload.put("checkReport", report);
        addRevisionContext(repairPayload, session, "fill-plan");
        repairPayload.put("repairInstruction", "Fix all errors. Prefer shorter Chinese text and valid existing slot_id values.");
        String response = llmService.complete(TEMPLATE_FILL_REPAIR_PROMPT,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(repairPayload),
                templateFillMaxTokens(effectiveTargetSlideCount(session).orElse(null)));
        JsonNode repaired = normalizeTemplateFillPlan(parseLlmJson(response));
        validateTemplateFillPlan(repaired);
        enforceTemplateSlideCount(repaired, effectiveTargetSlideCount(session));
        writeTemplateFillPlan(session, repaired, "fill-plan.json");

        Path repairedReportPath = session.getTaskDir().resolve("fill-check-report-repaired.json");
        int repairedExit = runTemplateFillCommandAllowFailure(List.of(
                "check-plan",
                session.getTaskDir().resolve("slide-library.json").toString(),
                session.getTaskDir().resolve("fill-plan.json").toString(),
                "-o",
                repairedReportPath.toString()
        ), "PPT 模板填充复查");
        JsonNode repairedReport = Files.isRegularFile(repairedReportPath)
                ? objectMapper.readTree(repairedReportPath.toFile())
                : objectMapper.createObjectNode();
        boolean repairedReportValid = repairedReport != null && repairedReport.isObject()
                && (repairedReport.path("summary").isObject() || repairedReport.has("ok"));
        int repairedWarnings = repairedReport.path("summary").path("warn").asInt(0);
        int repairedErrors = repairedReport.path("summary").isObject()
                ? repairedReport.path("summary").path("error").asInt(repairedExit == 0 ? 0 : 1)
                : (repairedReport.path("ok").asBoolean(false) && repairedExit == 0 ? 0 : 1);
        if (repairedExit != 0 || !repairedReportValid || repairedErrors > 0 || repairedWarnings > Math.max(0, config.getMaxTemplateWarnings())) {
            String reportText = Files.isRegularFile(repairedReportPath)
                    ? Files.readString(repairedReportPath, StandardCharsets.UTF_8)
                    : "";
            throw new IllegalStateException("PPT 模板填充计划仍未通过检查: " + trimLog(reportText));
        }
        return repaired;
    }

    private void runTemplateFillApply(PptGenerationSession session, JsonNode fillPlan,
                                      List<String> imagePaths) throws IOException, InterruptedException {
        writeTemplateFillPlan(session, fillPlan, "fill-plan.json");
        runTemplateFillCommand(List.of(
                "apply",
                session.getTemplatePath().toString(),
                session.getTaskDir().resolve("fill-plan.json").toString(),
                "-o",
                session.getPptxOutputPath().toString(),
                "--transition",
                "keep",
                "--strip-source-content"
        ), "PPT 模板填充");
        Path timestamped = findTimestampedTemplateOutput(session);
        if (timestamped != null && !timestamped.equals(session.getPptxOutputPath())) {
            Files.move(timestamped, session.getPptxOutputPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(session.getPptxOutputPath())) {
            throw new IllegalStateException("PPT 模板填充未输出文件");
        }
        applyTemplateImages(session, fillPlan, imagePaths);
    }

    private void applyTemplateImages(PptGenerationSession session, JsonNode fillPlan,
                                     List<String> imagePaths) throws IOException, InterruptedException {
        JsonNode slides = fillPlan == null ? null : fillPlan.path("slides");
        if (slides == null || !slides.isArray()) return;
        Map<String, String> byId = imagePathById(imagePaths);
        boolean hasImageEdits = StreamSupport.stream(slides.spliterator(), false)
                .anyMatch(slide -> slide.path("image_edits").isArray() && !slide.path("image_edits").isEmpty());
        if (!hasImageEdits || byId.isEmpty()) return;
        if (!looksLikePptx(session.getPptxOutputPath())) {
            log.warn("PPT 模板图片填充跳过，输出不是有效 PPTX: taskId={}", session.getTaskId());
            return;
        }
        Path script = session.getTaskDir().resolve("template-image-fill.py");
        Files.writeString(script, TEMPLATE_IMAGE_FILL_SCRIPT, StandardCharsets.UTF_8);
        Path planPath = session.getTaskDir().resolve("fill-plan.json");
        try {
            List<String> command = new ArrayList<>(splitCommand(config.getRendererCommand()));
            command.add(script.toString());
            command.add(session.getPptxOutputPath().toString());
            command.add(planPath.toString());
            command.add(objectMapper.writeValueAsString(byId));
            ProcessResult result = runProcess(command, session.getTaskDir(), Math.max(30, Math.min(config.getTimeoutSeconds(), 180)));
            if (result.exitCode() != 0) {
                throw new IllegalStateException("PPT 模板图片填充失败: " + trimLog(result.output()));
            }
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
                // Best effort cleanup for per-task helper.
            }
        }
    }

    private boolean looksLikePptx(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            return header.length == 4 && header[0] == 'P' && header[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private JsonNode compactSlideLibrary(JsonNode library) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", library.path("schema").asText(""));
        root.put("slide_count", library.path("slide_count").asInt(0));
        root.put("canvas_px", library.path("canvas_px"));
        List<Map<String, Object>> slides = new ArrayList<>();
        JsonNode slideNodes = library.path("slides");
        if (slideNodes.isArray()) {
            for (JsonNode slide : slideNodes) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("slide_index", slide.path("slide_index").asInt());
                item.put("page_type", slide.path("page_type").asText(""));
                item.put("text_summary", "");
                List<Map<String, Object>> slots = new ArrayList<>();
                JsonNode slotNodes = slide.path("slots");
                if (slotNodes.isArray()) {
                    List<JsonNode> sortedSlots = StreamSupport.stream(slotNodes.spliterator(), false)
                            .filter(slot -> !slot.path("text").asText("").isBlank())
                            .sorted(Comparator
                                    .comparingInt((JsonNode slot) -> slotPriority(slot.path("role").asText("")))
                                    .thenComparing((JsonNode slot) -> -slotArea(slot)))
                            .limit(8)
                            .toList();
                    for (JsonNode slot : sortedSlots) {
                        String text = slot.path("text").asText("");
                        Map<String, Object> slotItem = new LinkedHashMap<>();
                        slotItem.put("slot_id", slot.path("slot_id").asText(""));
                        slotItem.put("role", slot.path("role").asText(""));
                        slotItem.put("text", "");
                        slotItem.put("paragraph_count", slot.path("paragraph_count").asInt(0));
                        slotItem.put("geometry", slot.path("geometry"));
                        slotItem.put("text_metrics", slot.path("text_metrics"));
                        slots.add(slotItem);
                    }
                }
                item.put("slots", slots);
                item.put("tables", slide.path("tables").isArray() ? slide.path("tables").size() : 0);
                item.put("charts", slide.path("charts").isArray() ? slide.path("charts").size() : 0);
                JsonNode imageRegions = slide.path("image_regions");
                String pageType = slide.path("page_type").asText("");
                if (imageRegions.isArray()) {
                    item.put("image_regions", StreamSupport.stream(imageRegions.spliterator(), false)
                            .filter(this::isTemplateRegionFillable)
                            .filter(region -> isTemplateSlideImageFillable(pageType, region))
                            .sorted(Comparator.comparingInt((JsonNode region) -> -slotArea(region)))
                            .limit(3)
                            .toList());
                } else {
                    item.put("image_regions", List.of());
                }
                slides.add(item);
            }
        }
        root.put("slides", slides);
        return objectMapper.valueToTree(root);
    }

    private int slotPriority(String role) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (normalized.contains("title")) return 0;
        if (normalized.contains("body")) return 1;
        if (normalized.contains("label")) return 2;
        return 3;
    }

    private int slotArea(JsonNode node) {
        JsonNode geometry = node.path("geometry");
        return dimension(geometry, "width", "w") * dimension(geometry, "height", "h");
    }

    private int dimension(JsonNode geometry, String primary, String fallback) {
        if (geometry == null || geometry.isMissingNode() || geometry.isNull()) return 0;
        JsonNode primaryValue = geometry.get(primary);
        if (primaryValue != null && primaryValue.isNumber()) return Math.max(0, primaryValue.asInt(0));
        JsonNode fallbackValue = geometry.get(fallback);
        if (fallbackValue != null && fallbackValue.isNumber()) return Math.max(0, fallbackValue.asInt(0));
        return 0;
    }

    private JsonNode ensureTemplateImageAssignments(JsonNode plan, JsonNode imageManifest, JsonNode slideLibrary) {
        if (!(plan instanceof com.fasterxml.jackson.databind.node.ObjectNode) || imageManifest == null || slideLibrary == null) {
            return plan;
        }
        JsonNode slides = plan.path("slides");
        if (!slides.isArray()) return plan;

        List<JsonNode> images = StreamSupport.stream(imageManifest.path("images").spliterator(), false)
                .filter(image -> image.path("useful").asBoolean(false) && image.path("importance").asInt(0) >= 3)
                .toList();
        if (images.isEmpty()) return plan;

        Map<Integer, List<JsonNode>> regionsBySourceSlide = templateImageRegions(slideLibrary);
        Set<String> validImageIds = images.stream()
                .map(image -> image.path("id").asText(""))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> assignedImageIds = new HashSet<>();
        Set<String> usedTargets = new HashSet<>();

        for (int slideIndex = 0; slideIndex < slides.size(); slideIndex++) {
            JsonNode slide = slides.get(slideIndex);
            if (!(slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide)) continue;
            int sourceSlide = slide.path("source_slide").asInt(0);
            List<JsonNode> sourceRegions = regionsBySourceSlide.getOrDefault(sourceSlide, List.of());
            com.fasterxml.jackson.databind.node.ArrayNode normalized = objectMapper.createArrayNode();
            JsonNode edits = objectSlide.path("image_edits");
            if (edits.isArray()) {
                for (JsonNode edit : edits) {
                    String imageId = edit.path("image_id").asText("");
                    if (!validImageIds.contains(imageId)) continue;
                    JsonNode region = matchingTemplateRegion(edit.path("region_id").asText(""), sourceRegions);
                    if (region == null) continue;
                    String targetKey = slideIndex + ":" + region.path("region_id").asText("");
                    if (!usedTargets.add(targetKey)) continue;
                    normalized.add(templateImageEdit(imageId, region, edit.path("caption").asText("")));
                    assignedImageIds.add(imageId);
                }
            }
            objectSlide.set("image_edits", normalized);
        }

        if (regionsBySourceSlide.isEmpty()) return plan;
        for (JsonNode image : images) {
            String imageId = image.path("id").asText("");
            if (assignedImageIds.contains(imageId)) continue;
            int slideIndex = bestTemplateSlideForImage(slides, image, regionsBySourceSlide, usedTargets);
            if (slideIndex < 0) continue;
            JsonNode slide = slides.get(slideIndex);
            if (!(slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide)) continue;
            int sourceSlide = slide.path("source_slide").asInt(0);
            JsonNode region = bestTemplateRegion(regionsBySourceSlide.getOrDefault(sourceSlide, List.of()), usedTargets, slideIndex);
            if (region == null) continue;
            String targetKey = slideIndex + ":" + region.path("region_id").asText("");
            usedTargets.add(targetKey);
            JsonNode edits = objectSlide.path("image_edits");
            com.fasterxml.jackson.databind.node.ArrayNode editArray = edits.isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) edits
                    : objectMapper.createArrayNode();
            editArray.add(templateImageEdit(imageId, region, textOr(image, "bestUse", textOr(image, "title", "资料素材"))));
            objectSlide.set("image_edits", editArray);
            assignedImageIds.add(imageId);
        }
        return plan;
    }

    private JsonNode ensureGeneratedVisualsForDeck(PptGenerationSession session, JsonNode deck, JsonNode imageManifest) throws IOException {
        if (!(deck instanceof com.fasterxml.jackson.databind.node.ObjectNode) || imageManifest == null) return imageManifest;
        JsonNode slides = deck.path("slides");
        if (!slides.isArray()) return imageManifest;
        Set<String> existingIds = manifestImageIds(imageManifest);
        List<Map<String, Object>> generated = new ArrayList<>();
        int generatedIndex = 1;
        for (int index = 0; index < slides.size(); index++) {
            JsonNode slide = slides.get(index);
            if (!(slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide)) continue;
            if (!slide.path("imageId").asText("").isBlank()) continue;
            JsonNode spec = slide.path("visualSpec");
            if (!spec.isObject()) continue;
            String imageId = uniqueGeneratedImageId(existingIds, generatedIndex++);
            Path target = session.getImagesDir().resolve(imageId + ".png");
            try {
                renderGeneratedVisual(target, spec, slide, generatedVisualPalette(session));
            } catch (IOException e) {
                log.warn("生成 PPT 补充示意图失败，跳过该页视觉兜底: taskId={}, slideIndex={}",
                        session.getTaskId(), index + 1, e);
                objectSlide.remove(List.of("imageId", "imageHint"));
                continue;
            }
            objectSlide.put("imageId", imageId);
            objectSlide.put("imageHint", textOr(spec, "title", slide.path("title").asText("生成示意图")));
            objectSlide.put("layout", generatedLayoutHint(spec.path("type").asText("")));
            generated.add(generatedManifestItem(imageId, target.getFileName().toString(), spec, slide));
        }
        JsonNode updated = appendGeneratedImages(imageManifest, generated);
        writeImageManifest(session, updated);
        return updated;
    }

    private JsonNode ensureGeneratedVisualsForTemplate(PptGenerationSession session, JsonNode plan,
                                                       JsonNode imageManifest, JsonNode slideLibrary) throws IOException {
        if (!(plan instanceof com.fasterxml.jackson.databind.node.ObjectNode) || imageManifest == null || slideLibrary == null) {
            return imageManifest;
        }
        JsonNode slides = plan.path("slides");
        if (!slides.isArray()) return imageManifest;
        Map<Integer, List<JsonNode>> regionsBySourceSlide = templateImageRegions(slideLibrary);
        if (regionsBySourceSlide.isEmpty()) return imageManifest;
        Set<String> existingIds = manifestImageIds(imageManifest);
        List<Map<String, Object>> generated = new ArrayList<>();
        int generatedIndex = 1;
        for (int slideIndex = 0; slideIndex < slides.size(); slideIndex++) {
            JsonNode slide = slides.get(slideIndex);
            if (!(slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide)) continue;
            JsonNode edits = slide.path("image_edits");
            if (edits.isArray() && !edits.isEmpty()) continue;
            JsonNode spec = slide.path("generated_visual");
            if (!spec.isObject()) continue;
            int sourceSlide = slide.path("source_slide").asInt(0);
            JsonNode region = bestTemplateRegion(regionsBySourceSlide.getOrDefault(sourceSlide, List.of()), Set.of(), slideIndex);
            if (region == null) continue;
            String imageId = uniqueGeneratedImageId(existingIds, generatedIndex++);
            Path target = session.getImagesDir().resolve(imageId + ".png");
            try {
                renderGeneratedVisual(target, spec, slide, generatedVisualPalette(session));
            } catch (IOException e) {
                log.warn("生成模板 PPT 补充示意图失败，跳过该页图片填充: taskId={}, slideIndex={}",
                        session.getTaskId(), slideIndex + 1, e);
                objectSlide.remove("generated_visual");
                continue;
            }
            com.fasterxml.jackson.databind.node.ArrayNode editArray = objectMapper.createArrayNode();
            editArray.add(templateImageEdit(imageId, region, textOr(spec, "title", replacementText(slide))));
            objectSlide.set("image_edits", editArray);
            generated.add(generatedManifestItem(imageId, target.getFileName().toString(), spec, slide));
        }
        JsonNode updated = appendGeneratedImages(imageManifest, generated);
        writeImageManifest(session, updated);
        return updated;
    }

    private Set<String> manifestImageIds(JsonNode manifest) {
        Set<String> ids = new HashSet<>();
        JsonNode images = manifest == null ? null : manifest.path("images");
        if (images != null && images.isArray()) {
            for (JsonNode image : images) {
                String id = image.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    private String uniqueGeneratedImageId(Set<String> existingIds, int index) {
        String id;
        int current = index;
        do {
            id = "generated-visual-" + current++;
        } while (!existingIds.add(id));
        return id;
    }

    private Map<String, Object> generatedManifestItem(String id, String filename, JsonNode spec, JsonNode slide) {
        return Map.of(
                "id", id,
                "index", 0,
                "filename", filename,
                "kind", generatedKind(spec.path("type").asText("")),
                "title", textOr(spec, "title", slide.path("title").asText("生成示意图")),
                "summary", "根据页面内容生成的学术示意图",
                "bestUse", slide.path("title").asText("图文结合页面"),
                "importance", 4,
                "useful", true,
                "layoutHint", generatedLayoutHint(spec.path("type").asText(""))
        );
    }

    private JsonNode appendGeneratedImages(JsonNode imageManifest, List<Map<String, Object>> generated) {
        if (generated.isEmpty()) return imageManifest;
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        String source = imageManifest.path("source").asText("vision");
        root.put("source", "none".equals(source) ? "generated" : source);
        root.put("model", imageManifest.path("model").asText(""));
        root.put("fallbackReason", imageManifest.path("fallbackReason").asText(""));
        com.fasterxml.jackson.databind.node.ArrayNode images = objectMapper.createArrayNode();
        imageManifest.path("images").forEach(images::add);
        generated.forEach(item -> images.add(objectMapper.valueToTree(item)));
        root.set("images", images);
        return renumberManifest(root);
    }

    private String generatedKind(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (containsAny(value, "workflow", "timeline", "流程", "时间线")) return "workflow";
        if (containsAny(value, "architecture", "framework", "结构", "架构")) return "architecture";
        if (containsAny(value, "chart", "comparison", "matrix", "对比", "矩阵")) return "chart";
        return "workflow";
    }

    private String generatedLayoutHint(String type) {
        String kind = generatedKind(type);
        if ("architecture".equals(kind) || "workflow".equals(kind)) return "full-image";
        return "image-top";
    }

    private List<String> generatedVisualPalette(PptGenerationSession session) {
        try {
            if (session != null && Files.isRegularFile(session.getStyleJsonPath())) {
                return safePalette(objectMapper.readTree(session.getStyleJsonPath().toFile()).path("palette"));
            }
        } catch (Exception ignored) {
            // Fall back to the default palette below; a missing style must not fail the task.
        }
        return List.of("005BAC", "063A78", "D9A441", "EFF6FF", "1F2937");
    }

    private Color visualColor(List<String> palette, int index, String fallback) {
        String value = palette != null && index >= 0 && index < palette.size() ? palette.get(index) : fallback;
        String normalized = value == null ? "" : value.trim().replace("#", "");
        if (!HEX_COLOR.matcher(normalized).matches()) normalized = fallback;
        return new Color(Integer.parseInt(normalized.substring(0, 2), 16),
                Integer.parseInt(normalized.substring(2, 4), 16),
                Integer.parseInt(normalized.substring(4, 6), 16));
    }

    private void renderGeneratedVisual(Path target, JsonNode spec, JsonNode slide, List<String> palette) throws IOException {
        Files.createDirectories(target.getParent());
        int width = 1280;
        int height = 720;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color pale = visualColor(palette, 3, "EFF6FF");
            Color text = visualColor(palette, 4, "1F2937");
            graphics.setColor(pale);
            graphics.fillRect(0, 0, width, height);
            Color accent = visualColor(palette, 0, "005BAC");
            Color deep = visualColor(palette, 1, "063A78");
            Color muted = new Color(0x64, 0x74, 0x8B);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 34));
            graphics.setColor(text.equals(pale) ? deep : text);
            graphics.drawString(trimVisualText(textOr(spec, "title", slide.path("title").asText("生成示意图")), 28), 58, 72);
            List<String> items = visualItems(spec, slide);
            String type = spec.path("type").asText("workflow").toLowerCase(Locale.ROOT);
            if (containsAny(type, "matrix", "comparison", "对比", "矩阵")) {
                drawMatrixVisual(graphics, items, accent, deep, muted);
            } else if (containsAny(type, "architecture", "framework", "结构", "架构")) {
                drawArchitectureVisual(graphics, items, accent, deep, muted);
            } else {
                drawWorkflowVisual(graphics, items, accent, deep, muted);
            }
            graphics.setColor(new Color(0xE2, 0xE8, 0xF0));
            graphics.setStroke(new BasicStroke(3f));
            graphics.drawRoundRect(34, 30, width - 68, height - 60, 20, 20);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", target.toFile());
        image.flush();
    }

    private List<String> visualItems(JsonNode spec, JsonNode slide) {
        List<String> items = new ArrayList<>();
        JsonNode nodes = spec.path("items");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                String text = trimVisualText(node.asText(""), 16);
                if (!text.isBlank()) items.add(text);
            }
        }
        if (items.isEmpty()) {
            JsonNode bullets = slide.path("bullets");
            if (bullets.isArray()) {
                for (JsonNode bullet : bullets) {
                    String text = trimVisualText(bullet.asText(""), 16);
                    if (!text.isBlank()) items.add(text);
                }
            }
        }
        if (items.isEmpty()) {
            items.addAll(List.of("问题定义", "方法设计", "实验验证", "结果分析"));
        }
        return items.stream().limit(6).toList();
    }

    private String trimVisualText(String value, int maxChars) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= maxChars ? text : text.substring(0, Math.max(1, maxChars - 1)) + "...";
    }

    private void drawWorkflowVisual(Graphics2D graphics, List<String> items, Color accent, Color deep, Color muted) {
        int count = Math.max(3, Math.min(6, items.size()));
        int boxW = 170;
        int boxH = 92;
        int gap = (1040 - count * boxW) / Math.max(1, count - 1);
        int y = 310;
        graphics.setStroke(new BasicStroke(4f));
        for (int i = 0; i < count; i++) {
            int x = 120 + i * (boxW + gap);
            if (i > 0) {
                graphics.setColor(accent);
                graphics.drawLine(x - gap + 18, y + boxH / 2, x - 18, y + boxH / 2);
                graphics.fillPolygon(new int[]{x - 18, x - 34, x - 34}, new int[]{y + boxH / 2, y + boxH / 2 - 10, y + boxH / 2 + 10}, 3);
            }
            graphics.setColor(new Color(0xF8, 0xFA, 0xFC));
            graphics.fillRoundRect(x, y, boxW, boxH, 18, 18);
            graphics.setColor(accent);
            graphics.drawRoundRect(x, y, boxW, boxH, 18, 18);
            drawCenteredText(graphics, items.get(Math.min(i, items.size() - 1)), x, y + 10, boxW, boxH - 20, deep, 22);
        }
        graphics.setColor(muted);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 22));
        graphics.drawString("技术路线 / 处理流程", 120, 535);
    }

    private void drawArchitectureVisual(Graphics2D graphics, List<String> items, Color accent, Color deep, Color muted) {
        int[][] boxes = {{100, 170, 450, 110}, {730, 170, 450, 110}, {100, 420, 450, 110}, {730, 420, 450, 110}};
        graphics.setStroke(new BasicStroke(4f));
        for (int i = 0; i < boxes.length; i++) {
            int[] box = boxes[i];
            graphics.setColor(new Color(0xF8, 0xFA, 0xFC));
            graphics.fillRoundRect(box[0], box[1], box[2], box[3], 18, 18);
            graphics.setColor(i == 0 ? accent : new Color(0xCB, 0xD5, 0xE1));
            graphics.drawRoundRect(box[0], box[1], box[2], box[3], 18, 18);
            drawCenteredText(graphics, items.get(Math.min(i, items.size() - 1)), box[0], box[1] + 8, box[2], box[3] - 16, deep, 24);
        }
        graphics.setColor(accent);
        graphics.drawLine(550, 225, 730, 225);
        graphics.drawLine(325, 280, 325, 420);
        graphics.drawLine(955, 280, 955, 420);
        graphics.drawLine(550, 475, 730, 475);
        graphics.setColor(muted);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 22));
        graphics.drawString("系统框架 / 模型结构", 100, 610);
    }

    private void drawMatrixVisual(Graphics2D graphics, List<String> items, Color accent, Color deep, Color muted) {
        for (int i = 0; i < 4; i++) {
            int row = i / 2;
            int col = i % 2;
            int x = 118 + col * 545;
            int y = 170 + row * 210;
            graphics.setColor(new Color(0xF8, 0xFA, 0xFC));
            graphics.fillRoundRect(x, y, 480, 145, 18, 18);
            graphics.setColor(i == 0 ? accent : new Color(0xCB, 0xD5, 0xE1));
            graphics.setStroke(new BasicStroke(3f));
            graphics.drawRoundRect(x, y, 480, 145, 18, 18);
            graphics.setColor(accent);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 24));
            graphics.drawString(String.format("%02d", i + 1), x + 30, y + 48);
            drawCenteredText(graphics, items.get(Math.min(i, items.size() - 1)), x + 80, y + 15, 360, 110, deep, 24);
        }
        graphics.setColor(muted);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 22));
        graphics.drawString("对比分析 / 关键维度", 118, 610);
    }

    private void drawCenteredText(Graphics2D graphics, String text, int x, int y, int width, int height, Color color, int size) {
        graphics.setColor(color);
        graphics.setFont(new Font("SansSerif", Font.BOLD, size));
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = wrapVisualText(text, metrics, width - 24);
        int totalHeight = lines.size() * metrics.getHeight();
        int top = y + Math.max(metrics.getAscent(), (height - totalHeight) / 2 + metrics.getAscent());
        for (String line : lines) {
            int left = x + (width - metrics.stringWidth(line)) / 2;
            graphics.drawString(line, left, top);
            top += metrics.getHeight();
        }
    }

    private List<String> wrapVisualText(String text, FontMetrics metrics, int maxWidth) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) return List.of("");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            String candidate = line + value.substring(i, i + 1);
            if (!line.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(value.charAt(i));
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.stream().limit(2).toList();
    }

    private void writeTemplateImageFillReport(PptGenerationSession session, JsonNode fillPlan,
                                              JsonNode imageManifest, JsonNode slideLibrary) {
        try {
            List<JsonNode> candidates = imageManifest == null
                    ? List.of()
                    : StreamSupport.stream(imageManifest.path("images").spliterator(), false)
                    .filter(image -> image.path("useful").asBoolean(false) && image.path("importance").asInt(0) >= 3)
                    .toList();
            Map<Integer, List<JsonNode>> regionsBySourceSlide = templateImageRegions(slideLibrary);
            int fillableRegionCount = regionsBySourceSlide.values().stream().mapToInt(List::size).sum();
            JsonNode slides = fillPlan == null ? null : fillPlan.path("slides");
            int assignedImageCount = 0;
            boolean hasPotentialSemanticMatch = false;
            if (slides != null && slides.isArray()) {
                for (JsonNode slide : slides) {
                    JsonNode edits = slide.path("image_edits");
                    if (edits.isArray()) assignedImageCount += edits.size();
                    int sourceSlide = slide.path("source_slide").asInt(0);
                    if (regionsBySourceSlide.containsKey(sourceSlide)) {
                        String slideText = (slide.path("purpose").asText("") + " " + slide.path("layoutReason").asText("") + " "
                                + slide.path("notes").asText("") + " " + replacementText(slide)).toLowerCase(Locale.ROOT);
                        hasPotentialSemanticMatch = hasPotentialSemanticMatch || candidates.stream()
                                .anyMatch(image -> imageMatchesSlide(slideText, image));
                    }
                }
            }
            String skippedReason = "";
            if (candidates.isEmpty()) {
                skippedReason = "no-useful-images";
            } else if (fillableRegionCount == 0) {
                skippedReason = "no-fillable-template-regions";
            } else if (assignedImageCount == 0 && !hasPotentialSemanticMatch) {
                skippedReason = "no-semantic-match";
            } else if (assignedImageCount == 0) {
                skippedReason = "no-assignments";
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("fillableRegionCount", fillableRegionCount);
            report.put("candidateImageCount", candidates.size());
            report.put("assignedImageCount", assignedImageCount);
            report.put("skippedReason", skippedReason);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(session.getTaskDir().resolve("template-image-fill-report.json").toFile(), report);
        } catch (Exception e) {
            log.warn("保存模板图片填充报告失败: taskId={}", session.getTaskId(), e);
        }
    }

    private Map<Integer, List<JsonNode>> templateImageRegions(JsonNode slideLibrary) {
        Map<Integer, List<JsonNode>> regions = new HashMap<>();
        JsonNode slides = slideLibrary.path("slides");
        if (!slides.isArray()) return regions;
        for (JsonNode slide : slides) {
            int sourceSlide = slide.path("slide_index").asInt(0);
            JsonNode nodes = slide.path("image_regions");
            if (sourceSlide > 0 && nodes.isArray() && !nodes.isEmpty()) {
                String pageType = slide.path("page_type").asText("");
                List<JsonNode> fillableRegions = StreamSupport.stream(nodes.spliterator(), false)
                        .filter(this::isTemplateRegionFillable)
                        .filter(region -> isTemplateSlideImageFillable(pageType, region))
                        .toList();
                if (!fillableRegions.isEmpty()) {
                    regions.put(sourceSlide, fillableRegions);
                }
            }
        }
        return regions;
    }

    private boolean isTemplateRegionFillable(JsonNode region) {
        if (region == null || region.isMissingNode()) return false;
        if (region.has("fillable") && !region.path("fillable").asBoolean(false)) return false;
        String role = region.path("role").asText("");
        String rejectReason = region.path("rejectReason").asText("");
        String shapeName = region.path("shape_name").asText("").toLowerCase(Locale.ROOT);
        if (containsAny(role, "background", "decorative")) return false;
        if (!rejectReason.isBlank()) return false;
        if (isDecorativeShapeName(shapeName)) {
            return false;
        }
        JsonNode geometry = region.path("geometry");
        int width = dimension(geometry, "width", "w");
        int height = dimension(geometry, "height", "h");
        return width >= 160 && height >= 140 && width * height >= 80_000;
    }

    private boolean isDecorativeShapeName(String shapeName) {
        if (shapeName == null || shapeName.isBlank()) return false;
        String normalized = shapeName.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "background", "logo", "icon", "badge", "decor", "decoration", "ornament",
                "校徽", "徽标", "标志", "图标", "装饰", "背景")) {
            return true;
        }
        Set<String> tokens = new HashSet<>();
        Matcher matcher = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+").matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens.stream().anyMatch(DECORATIVE_SHAPE_TOKENS::contains);
    }

    private boolean isTemplateSlideImageFillable(String pageType, JsonNode region) {
        String normalized = pageType == null ? "" : pageType.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "cover", "toc", "chapter", "ending")) {
            return false;
        }
        return isTemplateRegionFillable(region);
    }

    private JsonNode matchingTemplateRegion(String regionId, List<JsonNode> regions) {
        if (regionId == null || regionId.isBlank()) return null;
        return regions.stream()
                .filter(region -> regionId.equals(region.path("region_id").asText("")))
                .findFirst()
                .orElse(null);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode templateImageEdit(String imageId, JsonNode region, String caption) {
        com.fasterxml.jackson.databind.node.ObjectNode edit = objectMapper.createObjectNode();
        edit.put("image_id", imageId);
        edit.put("region_id", region.path("region_id").asText(""));
        edit.put("caption", caption == null ? "" : caption);
        edit.set("geometry", region.path("geometry"));
        return edit;
    }

    private int bestTemplateSlideForImage(JsonNode slides, JsonNode image, Map<Integer, List<JsonNode>> regionsBySourceSlide,
                                          Set<String> usedTargets) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        boolean table = "table".equalsIgnoreCase(image.path("kind").asText())
                || image.path("filename").asText("").startsWith("paper-table-")
                || image.path("filename").asText("").startsWith("paper-excel-");
        for (int index = 0; index < slides.size(); index++) {
            JsonNode slide = slides.get(index);
            int sourceSlide = slide.path("source_slide").asInt(0);
            if (bestTemplateRegion(regionsBySourceSlide.getOrDefault(sourceSlide, List.of()), usedTargets, index) == null) {
                continue;
            }
            String text = (slide.path("purpose").asText("") + " " + slide.path("layoutReason").asText("") + " "
                    + slide.path("notes").asText("") + " " + replacementText(slide)).toLowerCase(Locale.ROOT);
            if (!imageMatchesSlide(text, image)) continue;
            int score = 0;
            if (table && containsAny(text, "result", "data", "experiment", "结果", "数据", "实验", "对比", "性能", "表")) score += 8;
            if (!table && containsAny(text, "method", "route", "model", "background", "framework", "方法", "路线", "模型", "背景", "结构")) score += 6;
            if (containsAny(text, "result", "conclusion", "结果", "结论", "验证", "评估")) score += 3;
            score += semanticOverlapScore(text, imageText(image));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestScore > 0 ? bestIndex : -1;
    }

    private String imageText(JsonNode image) {
        if (image == null || image.isMissingNode()) return "";
        return (image.path("title").asText("") + " "
                + image.path("summary").asText("") + " "
                + image.path("bestUse").asText("") + " "
                + image.path("kind").asText("")).toLowerCase(Locale.ROOT);
    }

    private boolean imageMatchesSlide(String slideText, JsonNode image) {
        String normalizedSlide = slideText == null ? "" : slideText.toLowerCase(Locale.ROOT);
        String normalizedImage = imageText(image);
        if (normalizedSlide.isBlank() || normalizedImage.isBlank()) return false;
        return semanticOverlapScore(normalizedSlide, normalizedImage) > 0;
    }

    private int semanticOverlapScore(String left, String right) {
        String leftText = left == null ? "" : left.toLowerCase(Locale.ROOT);
        String rightText = right == null ? "" : right.toLowerCase(Locale.ROOT);
        if (leftText.isBlank() || rightText.isBlank()) return 0;
        int score = 0;
        for (String keyword : SEMANTIC_KEYWORDS) {
            if (leftText.contains(keyword) && rightText.contains(keyword)) {
                score += 4;
            }
        }
        Set<String> leftTokens = meaningfulTokens(leftText);
        Set<String> rightTokens = meaningfulTokens(rightText);
        leftTokens.retainAll(rightTokens);
        return score + leftTokens.size() * 2;
    }

    private Set<String> meaningfulTokens(String text) {
        if (text == null || text.isBlank()) return new HashSet<>();
        Set<String> tokens = new HashSet<>();
        Matcher matcher = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]{3,}|[\\p{IsHan}]{2,}").matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!GENERIC_IMAGE_TERMS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private JsonNode bestTemplateRegion(List<JsonNode> regions, Set<String> usedTargets, int slideIndex) {
        return regions.stream()
                .filter(region -> !usedTargets.contains(slideIndex + ":" + region.path("region_id").asText("")))
                .max(Comparator.comparingInt(this::slotArea))
                .orElse(null);
    }

    private String replacementText(JsonNode slide) {
        JsonNode replacements = slide.path("replacements");
        if (!replacements.isArray()) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonNode replacement : replacements) {
            builder.append(' ').append(replacement.path("text").asText(""));
        }
        return builder.toString();
    }

    private Map<String, String> imagePathById(List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty()) return Map.of();
        Map<String, String> byId = new LinkedHashMap<>();
        for (String imagePath : imagePaths) {
            if (imagePath == null || imagePath.isBlank()) continue;
            Path path = Path.of(imagePath);
            if (Files.isRegularFile(path)) {
                byId.put(imageId(path), path.toString());
            }
        }
        return byId;
    }

    private JsonNode normalizeTemplateFillPlan(JsonNode plan) {
        if (!(plan instanceof com.fasterxml.jackson.databind.node.ObjectNode objectPlan)) {
            throw new IllegalArgumentException("PPT 模板填充计划必须是 JSON 对象");
        }
        objectPlan.put("schema", "template_fill_pptx_plan.v1");
        objectPlan.put("source_pptx", "template.pptx");
        return objectPlan;
    }

    private void validateTemplateFillPlan(JsonNode plan) {
        JsonNode slides = plan == null ? null : plan.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            throw new IllegalArgumentException("PPT 模板填充计划缺少 slides");
        }
        if (slides.size() > 40) throw new IllegalArgumentException("PPT 页数超过 40 页限制");
        for (JsonNode slide : slides) {
            if (slide.path("source_slide").asInt(0) <= 0) {
                throw new IllegalArgumentException("PPT 模板填充计划存在无效 source_slide");
            }
            JsonNode replacements = slide.path("replacements");
            if (!replacements.isArray()) {
                throw new IllegalArgumentException("PPT 模板填充计划 replacements 必须是数组");
            }
        }
    }

    private void enforceTemplateSlideCount(JsonNode plan, Optional<Integer> targetSlideCount) {
        if (targetSlideCount.isEmpty()) return;
        JsonNode slides = plan == null ? null : plan.path("slides");
        int actual = slides != null && slides.isArray() ? slides.size() : 0;
        int required = targetSlideCount.get();
        if (actual != required) {
            throw new IllegalStateException("PPT 模板填充计划页数不符合要求: required=" + required + ", actual=" + actual);
        }
    }

    private boolean templateSlideCountMatches(JsonNode plan, Optional<Integer> targetSlideCount) {
        if (targetSlideCount.isEmpty()) return true;
        JsonNode slides = plan == null ? null : plan.path("slides");
        return slides != null && slides.isArray() && slides.size() == targetSlideCount.get();
    }

    private void writeTemplateFillPlan(PptGenerationSession session, JsonNode plan, String filename) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(session.getTaskDir().resolve(filename).toFile(), plan);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(session.getDeckJsonPath().toFile(), plan);
    }

    private JsonNode buildImageManifest(PptGenerationSession session, List<String> imagePaths) {
        List<Path> paths = imagePaths == null ? List.of() : imagePaths.stream()
                .filter(Objects::nonNull)
                .map(Path::of)
                .filter(Files::isRegularFile)
                .limit(Math.max(0, config.getMaxVisionImages()))
                .toList();
        String visionModel = config.getVisionModel();
        JsonNode fallback = fallbackImageManifest(paths, paths.isEmpty() ? "none" : "fallback", visionModel,
                paths.isEmpty() ? "no-images" : "not-run");
        if (paths.isEmpty()) {
            writeImageManifest(session, fallback);
            return fallback;
        }
        com.fasterxml.jackson.databind.node.ArrayNode merged = objectMapper.createArrayNode();
        List<String> fallbackReasons = new ArrayList<>();
        int successfulBatches = 0;
        int failedBatches = 0;
        int batchSize = 8;
        for (int start = 0, batchIndex = 1; start < paths.size(); start += batchSize, batchIndex++) {
            List<Path> batch = paths.subList(start, Math.min(paths.size(), start + batchSize));
            try {
                StringBuilder prompt = new StringBuilder();
                prompt.append("Source file: ").append(session.getPaperFileName() == null ? "" : session.getPaperFileName()).append('\n');
                prompt.append("User request: ").append(session.getPrompt()).append('\n');
                prompt.append("Attached images correspond to these ids in order:\n");
                for (int i = 0; i < batch.size(); i++) {
                    prompt.append(i + 1).append(". ").append(imageId(batch.get(i))).append(" - ")
                            .append(batch.get(i).getFileName()).append('\n');
                }
                String response = llmService.completeWithImages(visionModel, VISION_PROMPT,
                        prompt.toString(), batch, config.getVisionMaxTokens());
                JsonNode root = parseVisionManifestResponse(response);
                JsonNode normalizedBatch = normalizeImageManifest(batch, root, visionModel);
                JsonNode batchImages = normalizedBatch.path("images");
                if (batchImages.isArray()) {
                    batchImages.forEach(merged::add);
                }
                successfulBatches++;
            } catch (Exception e) {
                failedBatches++;
                String reason = "batch " + batchIndex + ": " + trimLog(e.getMessage());
                fallbackReasons.add(reason);
                log.warn("PPT 图片视觉摘要分批失败，当前批次使用启发式 manifest: taskId={}, batch={}, model={}",
                        session.getTaskId(), batchIndex, visionModel, e);
                JsonNode fallbackBatch = fallbackImageManifest(batch, "fallback", visionModel, reason);
                JsonNode batchImages = fallbackBatch.path("images");
                if (batchImages.isArray()) {
                    batchImages.forEach(merged::add);
                }
            }
        }
        String source = failedBatches == 0 ? "vision" : successfulBatches == 0 ? "fallback" : "partial";
        JsonNode normalized = renumberManifest(Map.of(
                "source", source,
                "model", visionModel == null || visionModel.isBlank() ? "" : visionModel,
                "fallbackReason", String.join("; ", fallbackReasons),
                "images", merged));
        writeImageManifest(session, normalized);
        return normalized;
    }

    private JsonNode renumberManifest(Object manifest) {
        JsonNode root = objectMapper.valueToTree(manifest);
        com.fasterxml.jackson.databind.node.ObjectNode normalizedRoot = objectMapper.createObjectNode();
        normalizedRoot.put("source", root.path("source").asText(""));
        normalizedRoot.put("model", root.path("model").asText(""));
        normalizedRoot.put("fallbackReason", root.path("fallbackReason").asText(""));
        com.fasterxml.jackson.databind.node.ArrayNode images = objectMapper.createArrayNode();
        int index = 1;
        for (JsonNode image : root.path("images")) {
            if (!(image instanceof com.fasterxml.jackson.databind.node.ObjectNode objectImage)) continue;
            com.fasterxml.jackson.databind.node.ObjectNode copy = objectImage.deepCopy();
            copy.put("index", index++);
            images.add(copy);
        }
        normalizedRoot.set("images", images);
        return normalizedRoot;
    }

    private JsonNode parseVisionManifestResponse(String response) throws IOException {
        try {
            return parseLlmJson(response);
        } catch (Exception firstError) {
            String text = response == null ? "" : response;
            Matcher matcher = JSON_IMAGE_OBJECT.matcher(text);
            List<JsonNode> recovered = new ArrayList<>();
            while (matcher.find()) {
                try {
                    recovered.add(objectMapper.readTree(matcher.group()));
                } catch (Exception ignored) {
                    // Skip incomplete object fragments.
                }
            }
            if (!recovered.isEmpty()) {
                return objectMapper.valueToTree(Map.of("images", recovered));
            }
            if (firstError instanceof IOException io) throw io;
            throw new IOException(firstError.getMessage(), firstError);
        }
    }

    private JsonNode fallbackImageManifest(List<Path> paths, String source, String model, String reason) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            String filename = path.getFileName().toString();
            boolean tableImage = filename.startsWith("paper-table-") || filename.startsWith("paper-excel-");
            int heuristicImportance = fallbackImportance(path, tableImage);
            boolean useful = heuristicImportance >= 3;
            images.add(Map.of(
                    "id", imageId(path),
                    "index", i + 1,
                    "filename", filename,
                    "kind", tableImage ? "table" : fallbackKind(filename),
                    "title", tableImage ? "资料表格 " + (i + 1) : fallbackTitle(filename, i + 1),
                    "summary", tableImage ? "从上传资料表格渲染的图片" : "从上传资料提取的候选图片",
                    "bestUse", tableImage ? "结果或数据说明页" : fallbackBestUse(filename),
                    "importance", heuristicImportance,
                    "useful", useful,
                    "layoutHint", useful ? fallbackLayoutHint(filename, heuristicImportance) : "none"));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", source);
        root.put("model", model == null || model.isBlank() ? "" : model);
        root.put("fallbackReason", reason == null ? "" : reason);
        root.put("images", images);
        return objectMapper.valueToTree(root);
    }

    private int fallbackImportance(Path path, boolean tableImage) {
        String filename = path == null ? "" : path.getFileName().toString();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (tableImage) return 4;
        int score = 1;
        if (containsAny(lower, "caption", "figure", "fig", "chart", "plot", "result", "compare", "comparison",
                "architecture", "framework", "workflow", "pipeline", "network", "model", "dataset",
                "visualization", "heatmap", "图", "结果", "对比", "架构", "流程", "可视化")) {
            score += 2;
        }
        if (containsAny(lower, "paper-page-", "screenshot", "screen", "page")) {
            score -= 1;
        }
        if (hasStrongImageDimensions(path)) {
            score += 1;
        }
        if (containsAny(lower, "logo", "icon", "blank", "cover", "formula", "watermark")) {
            score -= 3;
        }
        return clamp(score, 1, 5);
    }

    private boolean hasStrongImageDimensions(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) return false;
            int width = image.getWidth();
            int height = image.getHeight();
            image.flush();
            if (width < 240 || height < 160) return false;
            double ratio = width / (double) Math.max(1, height);
            return ratio >= 0.6 && ratio <= 4.5;
        } catch (IOException e) {
            return false;
        }
    }

    private String fallbackKind(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "workflow", "pipeline", "流程")) return "workflow";
        if (containsAny(lower, "architecture", "framework", "network", "model", "结构", "架构")) return "architecture";
        if (containsAny(lower, "chart", "plot", "result", "compare", "结果", "对比")) return "chart";
        return "other";
    }

    private String fallbackTitle(String filename, int index) {
        String kind = fallbackKind(filename);
        return switch (kind) {
            case "workflow" -> "流程图 " + index;
            case "architecture" -> "结构图 " + index;
            case "chart" -> "结果图 " + index;
            default -> "资料图片 " + index;
        };
    }

    private String fallbackBestUse(String filename) {
        return switch (fallbackKind(filename)) {
            case "workflow" -> "技术路线或方法流程页";
            case "architecture" -> "系统框架或模型结构页";
            case "chart" -> "实验结果或对比分析页";
            default -> "相关正文图文页";
        };
    }

    private String fallbackLayoutHint(String filename, int importance) {
        String kind = fallbackKind(filename);
        if ("workflow".equals(kind) || "architecture".equals(kind)) return "full-image";
        if ("chart".equals(kind) || importance >= 4) return "image-top";
        return "image-right";
    }

    private JsonNode normalizeImageManifest(List<Path> paths, JsonNode root, String model) {
        Map<Integer, JsonNode> byIndex = new HashMap<>();
        JsonNode imagesNode = root == null ? null : root.get("images");
        if (imagesNode != null && imagesNode.isArray()) {
            for (JsonNode item : imagesNode) {
                int index = item.path("index").asInt(0);
                if (index > 0) byIndex.put(index, item);
            }
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            int index = i + 1;
            Path path = paths.get(i);
            JsonNode item = byIndex.get(index);
            boolean trustedTable = path.getFileName().toString().startsWith("paper-table-")
                    || path.getFileName().toString().startsWith("paper-excel-");
            int fallbackImportance = fallbackImportance(path, trustedTable);
            int importance = clamp(item == null ? fallbackImportance : item.path("importance").asInt(fallbackImportance), 1, 5);
            boolean useful = item != null && item.has("useful") && item.path("useful").isBoolean()
                    && item.path("useful").asBoolean(false);
            if (trustedTable) {
                importance = Math.max(3, importance);
                useful = true;
            }
            String layoutHint = normalizeLayoutHint(textOr(item, "layoutHint", importance >= 4 ? "full-image" : "image-right"));
            if (trustedTable) layoutHint = "full-image";
            if (!useful || importance < 3) {
                layoutHint = "none";
            }
            images.add(Map.of(
                    "id", imageId(path),
                    "index", index,
                    "filename", path.getFileName().toString(),
                    "kind", textOr(item, "kind", "other"),
                    "title", textOr(item, "title", "资料图片 " + index),
                    "summary", textOr(item, "summary", "从上传资料中提取的图片素材"),
                    "bestUse", textOr(item, "bestUse", "结合相邻内容作为图文页素材"),
                    "importance", importance,
                    "useful", useful,
                    "layoutHint", layoutHint));
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("source", "vision");
        normalized.put("model", model == null || model.isBlank() ? "" : model);
        normalized.put("fallbackReason", "");
        normalized.put("images", images);
        return objectMapper.valueToTree(normalized);
    }

    private JsonNode ensureImageAssignments(JsonNode deck, JsonNode imageManifest) {
        if (deck == null || !deck.isObject() || imageManifest == null) return deck;
        JsonNode slides = deck.path("slides");
        JsonNode images = imageManifest.path("images");
        if (!slides.isArray() || !images.isArray()) return deck;
        List<JsonNode> candidates = StreamSupport.stream(images.spliterator(), false)
                .filter(image -> image.path("useful").asBoolean(false) && image.path("importance").asInt(0) >= 3)
                .toList();
        Set<String> candidateIds = candidates.stream()
                .map(image -> image.path("id").asText(""))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> usedSlides = new HashSet<>();
        Set<String> assignedIds = new HashSet<>();
        for (int index = 0; index < slides.size(); index++) {
            JsonNode slide = slides.get(index);
            String imageId = slide.path("imageId").asText("");
            if (!imageId.isBlank()) {
                if (candidateIds.contains(imageId)) {
                    usedSlides.add(index);
                    assignedIds.add(imageId);
                } else if (slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide) {
                    objectSlide.remove(List.of("imageId", "imageHint"));
                    String layout = objectSlide.path("layout").asText("");
                    if (IMAGE_LAYOUTS.contains(layout)) {
                        objectSlide.put("layout", "auto");
                    }
                }
            }
        }
        for (JsonNode image : candidates) {
            if (assignedIds.contains(image.path("id").asText())) continue;
            int slideIndex = bestSlideForImage(slides, image, usedSlides);
            if (slideIndex < 0) continue;
            JsonNode slide = slides.get(slideIndex);
            if (slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide) {
                objectSlide.put("imageId", image.path("id").asText());
                objectSlide.put("imageHint", textOr(image, "bestUse", textOr(image, "title", "资料素材")));
                objectSlide.put("layout", normalizeLayoutHint(image.path("layoutHint").asText("image-right")));
                usedSlides.add(slideIndex);
            }
        }
        if (deck instanceof com.fasterxml.jackson.databind.node.ObjectNode objectDeck) {
            List<Map<String, Object>> assignments = new ArrayList<>();
            for (int index = 0; index < slides.size(); index++) {
                JsonNode slide = slides.get(index);
                String imageId = slide.path("imageId").asText("");
                if (imageId.isBlank()) continue;
                JsonNode matched = candidates.stream()
                        .filter(image -> image.path("id").asText("").equals(imageId))
                        .findFirst()
                        .orElse(null);
                assignments.add(Map.of(
                        "slideIndex", index + 1,
                        "imageId", imageId,
                        "imageHint", slide.path("imageHint").asText(""),
                        "layout", slide.path("layout").asText("auto"),
                        "kind", matched == null ? "" : matched.path("kind").asText(""),
                        "importance", matched == null ? 0 : matched.path("importance").asInt(0)
                ));
            }
            objectDeck.putPOJO("slotAssignments", assignments);
        }
        return deck;
    }

    private JsonNode attachTemplateMetadata(PptGenerationSession session, JsonNode deck, JsonNode imageManifest) {
        if (!(deck instanceof com.fasterxml.jackson.databind.node.ObjectNode objectDeck)) return deck;
        try {
            JsonNode style = objectMapper.readTree(session.getStyleJsonPath().toFile());
            objectDeck.set("templateAnalysis", style.path("templateAnalysis"));
            objectDeck.put("templateKey", session.getTemplateKey());
            objectDeck.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
            objectDeck.put("templateDesign", style.path("builtInTemplateDesign").asText(""));
            objectDeck.put("templateFrameworkMode", style.path("frameworkMode").asBoolean(false));
            objectDeck.put("templateFit", style.path("templateFit").asText("weak"));
            objectDeck.put("templateRoute", style.path("templateRoute").asText("framework"));
            objectDeck.putPOJO("evidenceCandidates", imageManifest == null ? List.of() : imageManifest.path("images"));
        } catch (Exception e) {
            log.warn("附加 PPT 模板元数据失败: taskId={}", session.getTaskId(), e);
        }
        return deck;
    }

    private JsonNode decorateDeckMetadata(PptGenerationSession session, JsonNode deck) {
        if (!(deck instanceof com.fasterxml.jackson.databind.node.ObjectNode objectDeck)) return deck;
        try {
            JsonNode style = Files.isRegularFile(session.getStyleJsonPath())
                    ? objectMapper.readTree(session.getStyleJsonPath().toFile())
                    : objectMapper.createObjectNode();
            JsonNode manifest = Files.isRegularFile(session.getImageManifestPath())
                    ? objectMapper.readTree(session.getImageManifestPath().toFile())
                    : objectMapper.createObjectNode().putArray("images");
            objectDeck.set("templateAnalysis", style.path("templateAnalysis"));
            objectDeck.set("templateFramework", style.path("templateFramework"));
            objectDeck.set("evidenceCandidates", manifest.path("images"));
            objectDeck.put("templateKey", session.getTemplateKey());
            objectDeck.put("templateFileName", session.getTemplateFileName() == null ? "" : session.getTemplateFileName());
            objectDeck.put("templateDesign", style.path("builtInTemplateDesign").asText(""));
            objectDeck.put("templateFrameworkMode", style.path("frameworkMode").asBoolean(false));
            objectDeck.put("templateFit", style.path("templateFit").asText("weak"));
            objectDeck.put("templateRoute", style.path("templateRoute").asText("framework"));
        } catch (Exception e) {
            log.warn("装饰 PPT 结构元数据失败: taskId={}", session.getTaskId(), e);
        }
        return deck;
    }

    private int bestSlideForImage(JsonNode slides, JsonNode image, Set<Integer> usedSlides) {
        boolean table = "table".equalsIgnoreCase(image.path("kind").asText())
                || image.path("filename").asText().startsWith("paper-table-")
                || image.path("filename").asText().startsWith("paper-excel-");
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < slides.size(); index++) {
            if (usedSlides.contains(index)) continue;
            JsonNode slide = slides.get(index);
            String type = slide.path("type").asText("content");
            if (!Set.of("content", "image", "conclusion").contains(type)) continue;
            String text = (slide.path("section").asText("") + " " + slide.path("title").asText("") + " "
                    + slide.path("headline").asText("")).toLowerCase(Locale.ROOT);
            int score = 0;
            if (table && containsAny(text, "result", "data", "experiment", "结果", "数据", "实验", "对比", "性能", "特征")) score += 6;
            if (!table && containsAny(text, "method", "route", "model", "background", "方法", "路线", "模型", "背景", "结构")) score += 5;
            if ("image".equals(type)) score += 3;
            if ("content".equals(type)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestScore > 0 ? bestIndex : -1;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private String normalizeLayoutHint(String value) {
        return switch (value == null ? "" : value.trim()) {
            case "full-image", "image-left", "image-right", "image-top", "evidence-strip", "none" -> value.trim();
            default -> "image-right";
        };
    }

    private String imageId(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void writeImageManifest(PptGenerationSession session, JsonNode manifest) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getImageManifestPath().toFile(), manifest);
        } catch (IOException e) {
            log.warn("保存 PPT 图片 manifest 失败: taskId={}", session.getTaskId(), e);
        }
    }

    private void runPythonRenderer(PptGenerationSession session, List<String> imagePaths) throws IOException, InterruptedException {
        Path runner = Path.of(config.getRendererScript());
        if (!runner.isAbsolute()) runner = Path.of("").toAbsolutePath().resolve(runner).normalize();
        if (!Files.isRegularFile(runner)) {
            throw new IllegalStateException("PPT 渲染脚本不存在: " + runner);
        }
        Path imagesJson = session.getTaskDir().resolve("images.json");
        objectMapper.writeValue(imagesJson.toFile(), imagePaths);

        List<String> command = new ArrayList<>(splitCommand(config.getRendererCommand()));
        command.add(runner.toString());
        command.add("--deck");
        command.add(session.getDeckJsonPath().toString());
        command.add("--style");
        command.add(session.getStyleJsonPath().toString());
        command.add("--images");
        command.add(imagesJson.toString());
        command.add("--manifest");
        command.add(session.getImageManifestPath().toString());
        if (hasTemplate(session)) {
            command.add("--template");
            command.add(session.getTemplatePath().toString());
        }
        command.add("--out");
        command.add(session.getPptxOutputPath().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(runner.getParent().toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        activeProcesses.add(process);
        try {
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean exited;
            try {
                exited = process.waitFor(Math.max(30, config.getTimeoutSeconds()), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                terminateProcessTree(process);
                outputFuture.cancel(true);
                throw e;
            }
            if (!exited) {
                terminateProcessTree(process);
                outputFuture.cancel(true);
                throw new IllegalStateException("PPT 渲染脚本超时");
            }
            String output = outputFuture.orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> "").join();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("PPT 渲染脚本失败: " + trimLog(output));
            }
            if (!Files.isRegularFile(session.getPptxOutputPath())) {
                throw new IllegalStateException("PPT 渲染脚本未输出文件: " + trimLog(output));
            }
        } finally {
            activeProcesses.remove(process);
            if (process.isAlive()) terminateProcessTree(process);
        }
    }

    private void runTemplateFillCommand(List<String> args, String label) throws IOException, InterruptedException {
        int exit = runTemplateFillCommandAllowFailure(args, label);
        if (exit != 0) {
            throw new IllegalStateException(label + "失败");
        }
    }

    private int runTemplateFillCommandAllowFailure(List<String> args, String label) throws IOException, InterruptedException {
        Path runner = Path.of(config.getTemplateFillScript());
        if (!runner.isAbsolute()) runner = Path.of("").toAbsolutePath().resolve(runner).normalize();
        if (!Files.isRegularFile(runner)) {
            throw new IllegalStateException("PPT 模板填充脚本不存在: " + runner);
        }
        List<String> command = new ArrayList<>(splitCommand(config.getTemplateFillCommand()));
        command.add(runner.toString());
        command.addAll(args);
        ProcessResult result = runProcess(command, runner.getParent(), Math.max(30, config.getTimeoutSeconds()));
        if (result.exitCode() != 0) {
            log.warn("{}失败: exit={}, output={}", label, result.exitCode(), trimLog(result.output()));
        }
        return result.exitCode();
    }

    private ProcessResult runProcess(List<String> command, Path directory, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        activeProcesses.add(process);
        try {
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean exited;
            try {
                exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                terminateProcessTree(process);
                outputFuture.cancel(true);
                throw e;
            }
            if (!exited) {
                terminateProcessTree(process);
                outputFuture.cancel(true);
                throw new IllegalStateException("外部脚本执行超时");
            }
            String output = outputFuture.orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> "").join();
            return new ProcessResult(process.exitValue(), output);
        } finally {
            activeProcesses.remove(process);
            if (process.isAlive()) terminateProcessTree(process);
        }
    }

    private String readProcessOutput(Process process) {
        final int maxChars = 64 * 1024;
        StringBuilder tail = new StringBuilder(maxChars);
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                tail.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (tail.length() > maxChars) tail.delete(0, tail.length() - maxChars);
            }
        } catch (IOException e) {
            if (tail.isEmpty()) return e.getMessage() == null ? "" : e.getMessage();
            tail.append("\n[process output read error] ").append(e.getMessage());
        }
        return tail.toString();
    }

    private void terminateProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private Path findTimestampedTemplateOutput(PptGenerationSession session) throws IOException {
        Path output = session.getPptxOutputPath();
        if (Files.isRegularFile(output)) return output;
        String stem = output.getFileName().toString().replaceFirst("\\.pptx$", "");
        try (Stream<Path> files = Files.list(session.getTaskDir())) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(stem + "_"))
                    .filter(path -> path.getFileName().toString().endsWith(".pptx"))
                    .max(Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .orElse(null);
        }
    }

    private int templateFillMaxTokens(Object targetSlideCount) {
        int count = targetSlideCount instanceof Number number ? number.intValue() : 16;
        return Math.min(24576, Math.max(8192, 4096 + clamp(count, 3, 40) * 520));
    }

    private String compactPaperTextForTemplate(String paperText) {
        if (paperText == null || paperText.isBlank()) return "";
        String text = paperText.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.length() <= 18000) return text;
        List<String> sections = new ArrayList<>();
        sections.add(trimForPrompt(text, 5000));
        String[] keywords = {
                "摘要", "背景", "目标", "需求", "方案", "方法", "流程", "产品",
                "数据", "实验", "结果", "结论", "建议", "下一步", "展望"
        };
        for (String keyword : keywords) {
            int index = text.indexOf(keyword);
            if (index < 0) continue;
            int start = Math.max(0, index - 500);
            int end = Math.min(text.length(), index + 1800);
            sections.add(text.substring(start, end));
        }
        sections.add(text.substring(Math.max(0, text.length() - 3500)));
        String joined = String.join("\n\n---\n\n", sections);
        return trimForPrompt(joined, 18000);
    }

    private String trimForPrompt(String value, int maxChars) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("PPT 渲染命令未配置");
        }
        return Stream.of(command.trim().split("\\s+")).toList();
    }

    private String readStyleField(PptGenerationSession session, String field, String fallback) {
        try {
            if (!Files.isRegularFile(session.getStyleJsonPath())) return fallback;
            JsonNode style = objectMapper.readTree(session.getStyleJsonPath().toFile());
            return style.path(field).asText(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void saveAndProgress(PptGenerationSession session, double progress, String stage, String message) {
        session.setProgress(progress);
        session.setProgressStage(stage);
        saveMetadata(session);
        emit(session, "progress", Map.of(
                "progress", progress,
                "stage", stage,
                "stageLabel", stageLabel(stage),
                "message", message));
    }

    private void failTask(PptGenerationSession session, Exception e) {
        log.error("PPT 生成任务失败: taskId={}", session.getTaskId(), e);
        session.setStatus("error");
        session.setProgressStage("error");
        session.setErrorMessage(e.getMessage() == null ? "PPT 生成失败" : e.getMessage());
        saveMetadata(session);
        try {
            refundIfNeeded(session, "PPT 生成失败自动退回额度");
        } catch (Exception refundError) {
            session.setRefundPending(true);
            session.setRefundError(trimLog(refundError.getMessage()));
            saveMetadata(session);
            log.error("PPT 额度退回失败，已记录待补偿状态: taskId={}", session.getTaskId(), refundError);
        }
        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("message", session.getErrorMessage());
        if (session.isRefundPending()) errorPayload.put("refundPending", true);
        emit(session, "task-error", errorPayload);
        completeEmitters(session.getTaskId());
    }

    /**
     * Creation can fail after charging credits but before the task is queued. Keep a durable
     * error record when the compensating refund itself fails so the startup reconciler can retry
     * it instead of silently deleting the charged task.
     */
    private boolean persistCreationFailure(PptGenerationSession session, Exception cause, String refundReason) {
        session.setStatus("error");
        session.setProgressStage("error");
        session.setErrorMessage(cause == null || cause.getMessage() == null
                ? "PPT 任务创建失败" : cause.getMessage());
        boolean keepRecord = false;
        try {
            refundIfNeeded(session, refundReason);
        } catch (Exception refundError) {
            keepRecord = true;
            session.setRefundPending(true);
            session.setRefundError(trimLog(refundError.getMessage()));
            log.error("PPT 创建阶段额度退回失败，已记录待补偿状态: taskId={}", session.getTaskId(), refundError);
        }
        if (keepRecord) sessions.put(session.getTaskId(), session);
        saveMetadata(session);
        return keepRecord;
    }

    private void refundIfNeeded(PptGenerationSession session, String reason) {
        if (quotaService == null || session.getCreditTransactionId() == null || session.isCreditRefunded()) return;
        quotaService.refund(session.getCreditTransactionId(), reason);
        session.setCreditRefunded(true);
        session.setRefundPending(false);
        session.setRefundError(null);
        saveMetadata(session);
    }

    private String validatePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.isBlank()) return "";
        if (value.length() > Math.max(100, config.getMaxPromptChars())) {
            throw new IllegalArgumentException("提示词超过 " + config.getMaxPromptChars() + " 字限制");
        }
        return value;
    }

    private String validateRevisionPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.length() > 4000) {
            throw new IllegalArgumentException("二次修改要求不能超过 4000 字");
        }
        return value;
    }

    private String formatSlideEdits(List<Map<String, Object>> slideEdits) {
        if (slideEdits == null || slideEdits.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("【网页手动修改】\n");
        int count = 0;
        for (Map<String, Object> edit : slideEdits) {
            if (edit == null || count >= 40) break;
            int index = numberValue(edit.get("slideIndex"));
            if (index < 1 || index > 40) continue;
            String title = trimForPrompt(stringValue(edit.get("title")), 120);
            String headline = trimForPrompt(stringValue(edit.get("headline")), 180);
            List<String> bullets = new ArrayList<>();
            Object rawBullets = edit.get("bullets");
            if (rawBullets instanceof Collection<?> collection) {
                for (Object item : collection) {
                    String value = trimForPrompt(stringValue(item), 120);
                    if (!value.isBlank()) bullets.add(value);
                    if (bullets.size() >= 6) break;
                }
            }
            if (title.isBlank() && headline.isBlank() && bullets.isEmpty()) continue;
            builder.append("第").append(index).append("页：");
            if (!title.isBlank()) builder.append("标题=【").append(title).append("】；");
            if (!headline.isBlank()) builder.append("核心句=【").append(headline).append("】；");
            if (!bullets.isEmpty()) builder.append("要点=【").append(String.join(" / ", bullets)).append("】；");
            builder.append('\n');
            count++;
        }
        return count == 0 ? "" : trimForPrompt(builder.toString(), 3600);
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(stringValue(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeClientRequestId(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= 128 && normalized.matches("[A-Za-z0-9._:-]+") ? normalized : "";
    }

    private PptGenerationSession findIdempotentTask(long userId, String clientRequestId) {
        return sessions.values().stream()
                .filter(session -> session.getUserId() == userId)
                .filter(session -> clientRequestId.equals(session.getClientRequestId()))
                .filter(session -> !"error".equals(session.getStatus()))
                .max(Comparator.comparingLong(PptGenerationSession::getCreatedAt))
                .orElse(null);
    }

    private String normalizeTemplateKey(String templateKey) {
        String value = templateKey == null || templateKey.isBlank() ? "academic-blue" : templateKey.trim();
        return templates().stream().anyMatch(item -> value.equals(item.get("key"))) ? value : "academic-blue";
    }

    private String normalizeOutputFormat(String outputFormat) {
        String value = outputFormat == null ? "pptx" : outputFormat.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("pptx", "html").contains(value)) {
            throw new IllegalArgumentException("输出格式只能是 PPTX 或 HTML");
        }
        return value;
    }

    private String outputFileName(PptGenerationSession session) {
        String extension = "html".equalsIgnoreCase(session.getOutputFormat()) ? ".html" : ".pptx";
        return "AI生成PPT-" + session.getTaskId() + extension;
    }

    private Map<String, Object> templateByKey(String templateKey) {
        String key = normalizeTemplateKey(templateKey);
        return templates().stream()
                .filter(item -> key.equals(item.get("key")))
                .findFirst()
                .orElse(templates().get(0));
    }

    private void applyBuiltInTemplateStyle(PptGenerationSession session) throws IOException {
        Map<String, Object> style = objectMapper.readValue(session.getStyleJsonPath().toFile(), Map.class);
        Map<String, Object> builtIn = templateByKey(session.getTemplateKey());
        String route = String.valueOf(style.getOrDefault("templateRoute", "")).toLowerCase(Locale.ROOT);
        boolean nativeUploadedTemplate = hasTemplate(session)
                && (route.isBlank() || Set.of("template-fill", "native-fill").contains(route));
        Object scannedPalette = style.get("palette");
        boolean hasScannedPalette = scannedPalette instanceof List<?> values && !values.isEmpty();
        if (!nativeUploadedTemplate || !hasScannedPalette) {
            style.put("palette", builtIn.get("palette"));
            style.put("paletteSource", "built-in-template");
        } else {
            style.put("paletteSource", "uploaded-template-scan");
        }
        style.put("builtInTemplatePalette", builtIn.get("palette"));
        style.put("uploadedTemplateUsage", nativeUploadedTemplate ? "native-template-style" : "assets-and-text-samples-only");
        style.put("builtInTemplateKey", builtIn.get("key"));
        style.put("builtInTemplateName", builtIn.get("name"));
        style.put("builtInTemplateSource", builtIn.get("source"));
        style.put("builtInTemplateLicense", builtIn.get("license"));
        style.put("builtInTemplateUrl", builtIn.get("sourceUrl"));
        style.put("builtInTemplateDesign", builtIn.get("design"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.getStyleJsonPath().toFile(), style);
    }

    private void validateDeck(JsonNode deck) {
        if (deck == null || !deck.isObject()) throw new IllegalArgumentException("PPT 结构格式错误");
        JsonNode slides = deck.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            throw new IllegalArgumentException("PPT 至少需要 1 页");
        }
        if (slides.size() > 40) throw new IllegalArgumentException("PPT 页数超过 40 页限制");
        for (JsonNode slide : slides) {
            if (!slide.isObject()) throw new IllegalArgumentException("PPT 页面结构格式错误");
            if (slide instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSlide) {
                objectSlide.put("type", safeSlideType(slide.path("type").asText("content")));
                validateTextField(slide, "title", 240);
                validateTextField(slide, "headline", 400);
                validateTextField(slide, "notes", 1200);
            }
        }
    }

    private void validateTextField(JsonNode node, String field, int maxChars) {
        if (node.has(field) && !node.get(field).isTextual()) {
            throw new IllegalArgumentException("PPT 页面字段格式错误: " + field);
        }
        if (node.path(field).asText("").length() > maxChars) {
            throw new IllegalArgumentException("PPT 页面字段过长: " + field);
        }
    }

    private Optional<Integer> extractTargetSlideCount(String prompt) {
        if (prompt == null || prompt.isBlank()) return Optional.empty();
        Matcher arabic = ARABIC_SLIDE_COUNT.matcher(prompt);
        if (arabic.find()) {
            String upper = arabic.group(2);
            return Optional.of(clamp(Integer.parseInt(upper == null ? arabic.group(1) : upper), 1, 40));
        }
        Matcher chinese = CHINESE_SLIDE_COUNT.matcher(prompt);
        if (chinese.find()) {
            int value = parseChineseNumber(chinese.group(1));
            if (value > 0) return Optional.of(clamp(value, 1, 40));
        }
        return Optional.empty();
    }

    private boolean defenseMode(PptGenerationSession session) {
        String prompt = session == null ? "" : session.getPrompt();
        if (prompt == null) return false;
        String normalized = prompt.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "答辩", "毕业", "论文汇报", "论文", "thesis", "defense", "dissertation", "学位");
    }

    private Optional<Integer> effectiveTargetSlideCount(PptGenerationSession session) {
        Optional<Integer> explicit = extractTargetSlideCount(session == null ? "" : session.getPrompt());
        if (explicit.isPresent()) return explicit;
        if (defenseMode(session)) return Optional.of(22);
        return hasPaper(session) ? Optional.of(12) : Optional.empty();
    }

    private String slideCountPolicy(PptGenerationSession session, Optional<Integer> targetSlideCount) {
        if (targetSlideCount.isPresent()) {
            return "Return exactly targetSlideCount slides. Compress and merge across the whole material instead of truncating early sections.";
        }
        if (defenseMode(session)) {
            return "No explicit page count was provided. For this defense/research task, create a complete deck of about 22 slides.";
        }
        return "No explicit page count was provided. Infer a natural, concise length from the user request and source material, normally around 10-14 slides.";
    }

    private String visualPolicy(PptGenerationSession session) {
        if (defenseMode(session)) {
            return "For defense body slides, prefer one relevant figure or a generated visualSpec per slide. Use generated visuals for workflows, architecture, comparisons, matrices, timelines, or result summaries when source images are insufficient.";
        }
        return "Use extracted images only when they clearly support the requested deck. Prefer concise, editable slides over forced image placement, and use generated visuals for simple processes, comparisons, timelines, or data summaries when helpful.";
    }

    private int imageBudgetFor(PptGenerationSession session, Optional<Integer> targetSlideCount) {
        int configured = Math.max(1, config.getMaxExtractedImages());
        int slides = targetSlideCount.orElse(defenseMode(session) ? 22 : 16);
        if (defenseMode(session) && slides >= 20 && slides <= 30) {
            return Math.min(configured, 25);
        }
        if (defenseMode(session)) {
            return Math.min(configured, Math.max(12, slides - 4));
        }
        return configured;
    }

    private int minCandidateImagesFor(PptGenerationSession session, Optional<Integer> targetSlideCount, int imageBudget) {
        if (!defenseMode(session)) return 0;
        int slides = targetSlideCount.orElse(22);
        if (slides >= 20 && slides <= 30) {
            return Math.min(Math.max(1, imageBudget), 15);
        }
        return 0;
    }

    private int deckMaxTokens(Optional<Integer> targetSlideCount) {
        int configured = Math.max(4096, config.getLlmMaxTokens());
        int slideCount = targetSlideCount.orElse(20);
        int estimated = 4096 + clamp(slideCount, 3, 40) * 700;
        return Math.min(32768, Math.max(configured, estimated));
    }

    private String deckSystemPrompt(PptGenerationSession session) {
        if (defenseMode(session)) return SYSTEM_PROMPT;
        return SYSTEM_PROMPT.lines()
                .filter(line -> !line.contains("defenseMode=true")
                        && !line.contains("formal thesis defense rhythm")
                        && !line.contains("thesis defense rhythm"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String compactRetryPrompt(PptGenerationSession session) {
        return deckSystemPrompt(session) + """

                The previous response was truncated before the JSON closed.
                Return a smaller but complete JSON object.
                Compression rules:
                - Keep exactly targetSlideCount slides when targetSlideCount is present.
                - Each slide has at most 3 bullets.
                - Each bullet is at most 18 Chinese characters.
                - notes is at most 35 Chinese characters.
                - Prefer empty metrics and imageId unless the evidence is essential.
                - Close every array and object. Valid complete JSON is more important than detail.
                """;
    }

    private String slideCountRepairPrompt() {
        return SYSTEM_PROMPT + """

                You are correcting a deck that missed the requested slide count.
                Return a revised deck with exactly requiredSlideCount slides, including cover and thanks.
                Preserve the full story arc by merging or expanding content across the whole deck. Do not keep only the first slides.
                """;
    }

    private int parseChineseNumber(String value) {
        if (value == null || value.isBlank()) return 0;
        Map<Character, Integer> digits = Map.of(
                '一', 1, '二', 2, '两', 2, '三', 3, '四', 4,
                '五', 5, '六', 6, '七', 7, '八', 8, '九', 9
        );
        String text = value.trim();
        if ("十".equals(text)) return 10;
        int tenIndex = text.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : digits.getOrDefault(text.charAt(0), 0);
            int ones = tenIndex == text.length() - 1 ? 0 : digits.getOrDefault(text.charAt(tenIndex + 1), 0);
            return tens * 10 + ones;
        }
        return digits.getOrDefault(text.charAt(0), 0);
    }

    private void validateSourceFile(MultipartFile file) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".pptx")
                || name.endsWith(".xlsx") || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".csv") || name.endsWith(".html") || name.endsWith(".htm"))) {
            throw new IllegalArgumentException("资料文件支持 PDF、DOCX、PPTX、XLSX、TXT、MD、CSV 或 HTML");
        }
        if (file.getSize() > config.getMaxPaperBytes()) {
            throw new IllegalArgumentException("资料文件超过 30MB 限制");
        }
    }

    private void validateFile(MultipartFile file, String suffix, long maxBytes, String label) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(suffix)) {
            throw new IllegalArgumentException(label + "仅支持 " + suffix + " 格式");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(label + "超过 30MB 限制");
        }
    }

    private void copyUpload(MultipartFile file, Path target) throws IOException {
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String extractJson(String response) {
        String text = response == null ? "" : response.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("mimo 未返回有效 JSON");
        }
        return text.substring(start, end + 1);
    }

    private JsonNode parseLlmJson(String response) throws IOException {
        String json = extractJson(response);
        try {
            return objectMapper.readTree(json);
        } catch (IOException firstError) {
            String repaired = repairSmartQuotedJson(json);
            if (!repaired.equals(json)) {
                try {
                    return objectMapper.readTree(repaired);
                } catch (IOException ignored) {
                    // Keep the original parser error; it points to the real malformed output.
                }
            }
            throw firstError;
        }
    }

    private boolean looksLikeTruncatedJson(String response, Exception error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("unexpected end-of-input") || message.contains("expected close marker")) {
            return true;
        }
        String text = response == null ? "" : response.trim();
        if (text.isBlank()) return false;
        int openBraces = 0;
        int openBrackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (current == '{') openBraces++;
            if (current == '}') openBraces--;
            if (current == '[') openBrackets++;
            if (current == ']') openBrackets--;
        }
        return inString || openBraces > 0 || openBrackets > 0;
    }

    private boolean shouldRetryTemplateFillJson(String response, Exception error) {
        if (looksLikeTruncatedJson(response, error)) return true;
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("unexpected character")
                || message.contains("was expecting a colon")
                || message.contains("was expecting comma")
                || message.contains("illegal unquoted character")
                || message.contains("unrecognized token");
    }

    private String repairSmartQuotedJson(String json) {
        StringBuilder repaired = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (current == '“' || current == '”') {
                char previous = previousNonWhitespace(json, index);
                char next = nextNonWhitespace(json, index);
                if (previous == ':' || previous == '[' || previous == ',' || previous == '{'
                        || next == ':' || next == ',' || next == ']' || next == '}') {
                    repaired.append('"');
                } else {
                    repaired.append(current);
                }
            } else {
                repaired.append(current);
            }
        }
        return repaired.toString();
    }

    private char previousNonWhitespace(String value, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) return c;
        }
        return '\0';
    }

    private char nextNonWhitespace(String value, int index) {
        for (int i = index + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) return c;
        }
        return '\0';
    }

    private boolean hasPaper(PptGenerationSession session) {
        return session.getPaperFileName() != null && Files.isRegularFile(session.getPaperPath());
    }

    private boolean hasTemplate(PptGenerationSession session) {
        return session.getTemplateFileName() != null && Files.isRegularFile(session.getTemplatePath());
    }

    private void sendSnapshot(PptGenerationSession session, SseEmitter emitter) {
        if ("completed".equals(session.getStatus())) {
            sendAndComplete(emitter, "done", Map.of("taskId", session.getTaskId()));
        } else if ("error".equals(session.getStatus())) {
            sendAndComplete(emitter, "task-error", Map.of("message",
                    session.getErrorMessage() == null ? "PPT 生成失败" : session.getErrorMessage()));
        } else if ("queued".equals(session.getStatus())) {
            send(emitter, "queued", Map.of("message", "任务正在等待后台生成", "queuePosition", session.getQueuePosition()));
        } else {
            send(emitter, "progress", Map.of(
                    "progress", session.getProgress(),
                    "stage", session.getProgressStage(),
                    "stageLabel", stageLabel(session.getProgressStage())));
        }
    }

    private void emit(PptGenerationSession session, String eventName, Object data) {
        List<SseEmitter> taskEmitters = emitters.get(session.getTaskId());
        if (taskEmitters == null) return;
        taskEmitters.forEach(emitter -> {
            if (!send(emitter, eventName, data)) removeEmitter(session.getTaskId(), emitter);
        });
    }

    private boolean send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void sendAndComplete(SseEmitter emitter, String eventName, Object data) {
        send(emitter, eventName, data);
        emitter.complete();
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters == null) return;
        taskEmitters.remove(emitter);
        if (taskEmitters.isEmpty()) emitters.remove(taskId, taskEmitters);
    }

    private void completeEmitters(String taskId) {
        List<SseEmitter> taskEmitters = emitters.remove(taskId);
        if (taskEmitters != null) taskEmitters.forEach(SseEmitter::complete);
    }

    private void updateQueuePositions() {
        List<PptGenerationSession> queued = sessions.values().stream()
                .filter(session -> "queued".equals(session.getStatus()))
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt))
                .toList();
        for (int index = 0; index < queued.size(); index++) {
            queued.get(index).setQueuePosition(index + 1);
        }
    }

    private void loadRecentSessions() {
        try (Stream<Path> taskDirs = Files.list(storageDir)) {
            taskDirs.filter(Files::isDirectory).forEach(taskDir -> {
                Path metadata = taskDir.resolve("task.json");
                if (!Files.isRegularFile(metadata)) return;
                try {
                    PptGenerationSession session = objectMapper.readValue(metadata.toFile(), PptGenerationSession.class);
                    session.setTaskDir(taskDir);
                    if (session.getAccessToken() == null || session.getAccessToken().isBlank()) {
                        session.setAccessToken(newAccessToken());
                        saveMetadata(session);
                    }
                    if (!Set.of("completed", "error").contains(session.getStatus())) {
                        session.setStatus("error");
                        session.setProgressStage("error");
                        session.setErrorMessage("任务来自旧流程或已中断，请重新提交");
                        saveMetadata(session);
                    }
                    if ("completed".equals(session.getStatus()) && !Files.isRegularFile(session.getOutputPath())) {
                        session.setStatus("error");
                        session.setProgressStage("error");
                        session.setErrorMessage("任务输出文件缺失，请重新生成");
                        saveMetadata(session);
                    }
                    sessions.put(session.getTaskId(), session);
                    if (session.getUserId() > 0 && session.getClientRequestId() != null
                            && !session.getClientRequestId().isBlank()
                            && (!"error".equals(session.getStatus()) || session.isRefundPending())) {
                        idempotencyClaims.put(session.getUserId() + ":" + session.getClientRequestId(), session.getTaskId());
                    }
                } catch (Exception e) {
                    log.warn("读取 PPT 生成任务记录失败: {}", metadata, e);
                }
            });
        } catch (IOException e) {
            log.warn("读取 PPT 生成任务目录失败: {}", storageDir, e);
        }
    }

    private void saveMetadata(PptGenerationSession session) {
        try {
            Path metadata = session.getMetadataPath();
            Path temp = metadata.resolveSibling("task.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), session);
            try {
                Files.move(temp, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, metadata, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("保存 PPT 生成任务记录失败: taskId={}", session.getTaskId(), e);
        }
    }

    private void reconcilePendingRefunds() {
        if (quotaService == null) return;
        sessions.values().stream()
                .filter(session -> "error".equals(session.getStatus()))
                .filter(session -> session.getCreditTransactionId() != null)
                .filter(session -> !session.isCreditRefunded())
                .forEach(session -> {
                    try {
                        refundIfNeeded(session, "PPT 生成失败自动补偿额度");
                    } catch (Exception e) {
                        session.setRefundPending(true);
                        session.setRefundError(trimLog(e.getMessage()));
                        saveMetadata(session);
                        log.warn("启动时补偿 PPT 额度失败: taskId={}", session.getTaskId(), e);
                    }
                });
    }

    private void cleanupHistory() {
        int keep = Math.max(1, config.getMaxHistory());
        List<PptGenerationSession> terminal = sessions.values().stream()
                .filter(session -> Set.of("completed", "error").contains(session.getStatus()))
                .filter(session -> !session.isRefundPending())
                .sorted(Comparator.comparingLong(PptGenerationSession::getCreatedAt).reversed())
                .toList();
        for (int index = keep; index < terminal.size(); index++) {
            PptGenerationSession session = terminal.get(index);
            sessions.remove(session.getTaskId(), session);
            deleteRecursively(session.getTaskDir());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.warn("清理 PPT 任务文件失败: {}", item, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理 PPT 任务目录失败: {}", path, e);
        }
    }

    private void copyOptional(Path source, Path target) throws IOException {
        if (source == null || target == null || !Files.isRegularFile(source)) return;
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) return;
        Files.createDirectories(target);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target.toAbsolutePath().normalize())) continue;
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else if (Files.isRegularFile(path)) Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private PptGenerationSession requireSession(String taskId) {
        PptGenerationSession session = sessions.get(taskId);
        if (session == null) throw new IllegalArgumentException("任务不存在");
        return session;
    }

    private PptGenerationSession requireAuthorizedSession(String taskId, String accessToken) {
        PptGenerationSession session = requireSession(taskId);
        if (!tokenMatches(session.getAccessToken(), accessToken)) {
            throw new IllegalArgumentException("任务不存在");
        }
        return session;
    }

    private boolean tokenMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }

    private Set<String> parseAccessTokens(String accessTokens) {
        if (accessTokens == null || accessTokens.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String token : accessTokens.split(",")) {
            String value = token.trim();
            if (!value.isBlank() && value.length() <= 128) tokens.add(value);
        }
        return tokens;
    }

    private String newAccessToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String trimLog(String output) {
        if (output == null) return "";
        int max = 2000;
        return output.length() <= max ? output : output.substring(output.length() - max);
    }

    public String stageLabel(String stage) {
        return switch (stage == null ? "" : stage) {
            case "queued" -> "等待后台生成";
            case "extracting" -> "读取资料和模板";
            case "refreshing_assets" -> "刷新资料和模板素材";
            case "reusing_assets" -> "复用已提取素材";
            case "template_analyzing" -> "分析 PPT 模板";
            case "planning" -> "mimo 规划 PPT 结构";
            case "template_checking" -> "检查模板填充";
            case "rendering" -> "生成 PPTX";
            case "completed" -> "PPT 生成完成";
            case "error" -> "PPT 生成失败";
            default -> "处理中";
        };
    }
}
