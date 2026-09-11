package com.web.backen.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared vision model; the historical PPT setting remains a compatible fallback. */
@Component
public class VisionConfig {
    private final String model;
    public VisionConfig(@Value("${llm.vision-model:${ppt-generation.vision-model:mimo-v2.5}}") String model) {
        this.model = model;
    }
    public String getModel() { return model; }
}
