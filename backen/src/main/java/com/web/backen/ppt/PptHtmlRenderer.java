package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Produces a self-contained HTML presentation from the sanitized preview model.
 * Images are embedded as data URIs so the downloaded file works without a sibling assets folder.
 */
public final class PptHtmlRenderer {

    private static final long MAX_EMBEDDED_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_SINGLE_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final Set<String> SAFE_TYPES = Set.of(
            "cover", "contents", "section", "content", "image", "conclusion", "thanks", "chapter");
    private static final List<String> DEFAULT_PALETTE = List.of("005BAC", "063A78", "D9A441", "EFF6FF", "1F2937");

    private final ObjectMapper objectMapper;

    public PptHtmlRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void render(Path output, Map<String, Object> preview, Path imagesDir) throws IOException {
        Map<String, Object> safePreview = sanitizePreview(preview);
        Map<String, String> images = embeddedImages(safePreview, imagesDir);
        String dataJson = safeJson(safePreview);
        String imagesJson = safeJson(images);
        String title = escapeHtml(String.valueOf(safePreview.getOrDefault("title", "AI 生成演示文稿")));
        String template = escapeHtml(String.valueOf(safePreview.getOrDefault("templateName", "")));
        String html = "<!doctype html>\n"
                + "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><style>" + CSS + "</style></head><body>"
                + "<header class=\"toolbar\"><div><strong>" + title + "</strong>"
                + (template.isBlank() ? "" : "<span> · " + template + "</span>")
                + "</div><div><button id=\"prev\">上一页</button><span id=\"counter\"></span>"
                + "<button id=\"next\">下一页</button><button id=\"fullscreen\">全屏</button></div></header>"
                + "<main id=\"deck\" aria-live=\"polite\"></main>"
                + "<script type=\"application/json\" id=\"ppt-data\">" + dataJson + "</script>"
                + "<script type=\"application/json\" id=\"ppt-images\">" + imagesJson + "</script>"
                + "<script>" + JS + "</script></body></html>";
        Files.writeString(output, html);
    }

    /** Sanitize values that are interpolated into CSS class names or custom properties in JS. */
    private Map<String, Object> sanitizePreview(Map<String, Object> preview) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (preview == null) return result;
        result.putAll(preview);
        Object rawPalette = preview.get("palette");
        List<String> palette = new java.util.ArrayList<>();
        if (rawPalette instanceof List<?> values) {
            for (int i = 0; i < DEFAULT_PALETTE.size(); i++) {
                String value = i < values.size() ? String.valueOf(values.get(i)) : "";
                palette.add(normalizeHex(value, DEFAULT_PALETTE.get(i)));
            }
        } else {
            palette.addAll(DEFAULT_PALETTE);
        }
        result.put("palette", palette);
        Object rawSlides = preview.get("slides");
        if (rawSlides instanceof List<?> slides) {
            List<Map<String, Object>> safeSlides = new java.util.ArrayList<>();
            for (Object rawSlide : slides) {
                if (!(rawSlide instanceof Map<?, ?> slide)) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                slide.forEach((key, value) -> copy.put(String.valueOf(key), value));
                String type = String.valueOf(copy.getOrDefault("type", "content")).toLowerCase(Locale.ROOT);
                copy.put("type", SAFE_TYPES.contains(type) ? type : "content");
                Object imageFile = copy.get("imageFile");
                if (imageFile != null) {
                    String fileName = String.valueOf(imageFile);
                    if (fileName.contains("/") || fileName.contains("\\")
                            || !fileName.equals(Path.of(fileName).getFileName().toString())) {
                        copy.remove("imageFile");
                    }
                }
                safeSlides.add(copy);
            }
            result.put("slides", safeSlides);
        }
        return result;
    }

    private String normalizeHex(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replace("#", "");
        return normalized.matches("[0-9A-Fa-f]{6}") ? normalized : fallback;
    }

    private Map<String, String> embeddedImages(Map<String, Object> preview, Path imagesDir) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        long totalBytes = 0;
        Object rawSlides = preview.get("slides");
        if (!(rawSlides instanceof List<?> slides) || imagesDir == null || !Files.isDirectory(imagesDir)) return result;
        for (Object rawSlide : slides) {
            if (!(rawSlide instanceof Map<?, ?> slide)) continue;
            Object rawFileName = slide.get("imageFile");
            String fileName = rawFileName == null ? "" : String.valueOf(rawFileName);
            if (fileName.isBlank() || result.containsKey(fileName)) continue;
            Path image = imagesDir.resolve(fileName).normalize();
            if (!image.startsWith(imagesDir.normalize()) || !Files.isRegularFile(image)) continue;
            long size = Files.size(image);
            if (size <= 0 || size > MAX_SINGLE_IMAGE_BYTES || totalBytes + size > MAX_EMBEDDED_IMAGE_BYTES) continue;
            String mime = Files.probeContentType(image);
            if (mime == null || !mime.startsWith("image/")) mime = "image/png";
            result.put(fileName, "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(image)));
            totalBytes += size;
        }
        return result;
    }

    private String safeJson(Object value) throws IOException {
        return objectMapper.writeValueAsString(value)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String CSS = """
            :root{color-scheme:dark;font-family:Inter,"Noto Sans CJK SC","PingFang SC",Arial,sans-serif;background:#111827;color:#f8fafc}
            *{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(circle at 15% 0,#26334d,#0b1020 55%,#070a12);overflow-x:hidden}
            .toolbar{height:58px;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:0 24px;background:rgba(7,10,18,.86);backdrop-filter:blur(16px);position:sticky;top:0;z-index:5;border-bottom:1px solid rgba(255,255,255,.1);font-size:14px}
            .toolbar strong{font-size:16px}.toolbar span{color:#94a3b8}.toolbar button{border:1px solid rgba(255,255,255,.18);background:#172036;color:#e2e8f0;border-radius:8px;padding:7px 12px;margin-left:7px;cursor:pointer}.toolbar button:hover{background:#263653}
            #counter{display:inline-block;min-width:72px;text-align:center;color:#94a3b8;font-variant-numeric:tabular-nums}
            #deck{width:min(1180px,calc(100vw - 32px));margin:34px auto 60px}.slide{display:none;position:relative;aspect-ratio:16/9;overflow:hidden;border-radius:18px;padding:5.5%;background:var(--bg,#f8fafc);color:var(--text,#172033);box-shadow:0 26px 80px rgba(0,0,0,.32);isolation:isolate}.slide.active{display:block}.slide:before{content:"";position:absolute;inset:0 auto 0 0;width:10px;background:var(--accent,#2563eb);z-index:-1}.slide.dark{--bg:var(--deep,#172554);--text:#fff}.slide.cover,.slide.section,.slide.thanks{--bg:var(--deep,#172554);--text:#fff}.slide .eyebrow{font-size:clamp(10px,1.15vw,16px);font-weight:700;letter-spacing:.14em;color:var(--accent,#2563eb);text-transform:uppercase}.slide.dark .eyebrow,.slide.cover .eyebrow,.slide.section .eyebrow,.slide.thanks .eyebrow{color:var(--highlight,#f59e0b)}.slide h1{font-size:clamp(26px,4.4vw,68px);line-height:1.08;margin:7% 0 2%;max-width:78%;letter-spacing:-.04em}.slide.cover h1,.slide.section h1,.slide.thanks h1{font-size:clamp(32px,5.2vw,82px);margin-top:17%}.slide .headline{font-size:clamp(14px,1.7vw,26px);line-height:1.42;color:color-mix(in srgb,var(--text) 72%,transparent);max-width:70%}.slide.dark .headline,.slide.cover .headline,.slide.section .headline,.slide.thanks .headline{color:rgba(255,255,255,.72)}.slide ul{margin:5% 0 0;padding:0;list-style:none;max-width:64%;font-size:clamp(13px,1.55vw,23px);line-height:1.5}.slide li{margin:.8em 0;padding-left:1.1em;position:relative}.slide li:before{content:"";position:absolute;left:0;top:.6em;width:.42em;height:.42em;border-radius:50%;background:var(--accent,#2563eb)}.slide img{position:absolute;right:6%;top:26%;width:34%;height:45%;object-fit:contain;border-radius:12px;border:1px solid color-mix(in srgb,var(--accent) 48%,transparent);background:rgba(255,255,255,.08)}.metric-row{display:flex;gap:14px;position:absolute;left:6%;right:6%;bottom:9%}.metric{min-width:0;flex:1;border:1px solid color-mix(in srgb,var(--accent) 50%,transparent);background:color-mix(in srgb,var(--text) 8%,transparent);border-radius:10px;padding:12px 16px}.metric b{display:block;color:var(--accent);font-size:clamp(18px,2.3vw,36px)}.metric span{display:block;margin-top:4px;font-size:clamp(10px,1.1vw,16px);color:color-mix(in srgb,var(--text) 72%,transparent)}.page-no{position:absolute;right:6%;bottom:4%;font-size:12px;color:color-mix(in srgb,var(--text) 55%,transparent)}.slide.cover:after,.slide.section:after{content:"";position:absolute;right:-8%;bottom:-28%;width:45%;height:75%;border:1px solid color-mix(in srgb,var(--accent) 42%,transparent);border-radius:50%;transform:rotate(-18deg);z-index:-1}.empty{padding:80px;text-align:center;color:#94a3b8}@media(max-width:720px){.toolbar{padding:0 10px}.toolbar>div:first-child{max-width:42%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.toolbar button{padding:6px 8px;margin-left:3px}.slide{border-radius:12px;padding:7% 8%}.slide h1{max-width:92%}.slide .headline,.slide ul{max-width:92%}.slide img{position:relative;right:auto;top:auto;width:100%;height:26%;margin-top:5%}.slide ul{font-size:14px;margin-top:3%}.metric-row{position:relative;left:auto;right:auto;bottom:auto;margin-top:5%;gap:6px}.metric{padding:7px}.slide.cover h1,.slide.section h1,.slide.thanks h1{margin-top:20%}}
            """;

    private static final String JS = """
            (()=>{const data=JSON.parse(document.getElementById('ppt-data').textContent||'{}');const images=JSON.parse(document.getElementById('ppt-images').textContent||'{}');const deck=document.getElementById('deck');const slides=Array.isArray(data.slides)?data.slides:[];let current=0;const esc=v=>String(v??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));const colors=(data.palette||['005BAC','063A78','D9A441','EFF6FF','1F2937']).map(x=>'#'+String(x).replace('#',''));const dark=t=>['cover','section','thanks','chapter'].includes(String(t||'').toLowerCase());function render(){if(!slides.length){deck.innerHTML='<div class=\"empty\">没有可展示的页面</div>';return}deck.innerHTML=slides.map((s,i)=>{const cls=dark(s.type)?' dark '+String(s.type||''):' '+String(s.type||'');const bullets=Array.isArray(s.bullets)?s.bullets.slice(0,6):[];const metrics=Array.isArray(s.metrics)?s.metrics.slice(0,3):[];const image=s.imageFile&&images[s.imageFile]?'<img alt=\"\" src=\"'+images[s.imageFile]+'\">':'';return '<article class=\"slide'+cls+(i===current?' active':'')+'\" style=\"--accent:'+colors[0]+';--deep:'+colors[1]+';--highlight:'+colors[2]+';--bg:'+colors[3]+';--text:'+colors[4]+'\"><div class=\"eyebrow\">'+esc(s.section||s.type||'CONTENT')+'</div><h1>'+esc(s.title||'未命名页面')+'</h1>'+(s.headline?'<div class=\"headline\">'+esc(s.headline)+'</div>':'')+(bullets.length?'<ul>'+bullets.map(x=>'<li>'+esc(x)+'</li>').join('')+'</ul>':'')+image+(metrics.length?'<div class=\"metric-row\">'+metrics.map(m=>'<div class=\"metric\"><b>'+esc(m.value||'')+'</b><span>'+esc(m.label||'')+'</span></div>').join('')+'</div>':'')+'<div class=\"page-no\">'+String(i+1).padStart(2,'0')+' / '+String(slides.length).padStart(2,'0')+'</div></article>'}).join('');document.getElementById('counter').textContent=(current+1)+' / '+slides.length}function move(step){current=(current+step+slides.length)%slides.length;render()}document.getElementById('prev').onclick=()=>move(-1);document.getElementById('next').onclick=()=>move(1);document.getElementById('fullscreen').onclick=()=>document.documentElement.requestFullscreen?.();document.addEventListener('keydown',e=>{if(e.key==='ArrowRight'||e.key==='PageDown')move(1);if(e.key==='ArrowLeft'||e.key==='PageUp')move(-1)});render()})();
            """;
}
