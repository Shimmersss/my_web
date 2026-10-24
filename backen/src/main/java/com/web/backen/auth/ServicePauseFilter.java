package com.web.backen.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Rejects every request to a module paused in runtime settings, before any controller work. */
@Component
public class ServicePauseFilter extends OncePerRequestFilter {
    private static final Map<String, String> PREFIX_FEATURES = Map.of(
            "/api/ppt-generate", "Contact",
            "/api/image-generate", "ImageGenerate",
            "/api/zotero", "Publications");
    private static final Map<String, String> FEATURE_NAMES = Map.of(
            "Contact", "PPT 生成", "ImageGenerate", "GPT 生图", "Publications", "文献库");

    private final RuntimeConfigService runtimeConfig;

    public ServicePauseFilter(RuntimeConfigService runtimeConfig) { this.runtimeConfig = runtimeConfig; }

    static String featureFor(String path) {
        for (Map.Entry<String, String> entry : PREFIX_FEATURES.entrySet()) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return entry.getValue();
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return featureFor(request.getRequestURI().substring(request.getContextPath().length())) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String feature = featureFor(request.getRequestURI().substring(request.getContextPath().length()));
        if (!runtimeConfig.isPaused(feature)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        String message = FEATURE_NAMES.get(feature) + "暂停服务，服务器资源正优先保障论文翻译";
        response.getOutputStream().write(("{\"code\":503,\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }
}
