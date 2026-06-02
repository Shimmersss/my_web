package com.web.backen.translate;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TranslationProtector {

    private static final Pattern PROTECTED_PATTERN = Pattern.compile(
            "\\b(?:CNN|RNN|LSTM|GRU|Transformer|YOLO|PointPillars|VoxelNet|KITTI|Waymo|NuScenes|LiDAR|SLAM|mAP|IoU|GPU|CPU|RGB|BEV)\\b"
                    + "|\\[[0-9,\\-\\s]+]"
                    + "|\\([0-9]+\\)"
                    + "|\\b[A-Za-z]+_[A-Za-z0-9]+\\b"
                    + "|\\b[A-Za-z]+\\^[A-Za-z0-9]+\\b"
                    + "|\\b\\d+(?:\\.\\d+)?%"
                    + "|\\b\\d+(?:\\.\\d+)?\\s?(?:ms|s|m|cm|mm|km|Hz|GHz|MB|GB|K)\\b"
    );

    public ProtectedText protect(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PROTECTED_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String placeholder = placeholder(tokens.size());
            tokens.add(token);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return new ProtectedText(sb.toString(), tokens);
    }

    public String restore(String text, ProtectedText protectedText) {
        String out = normalizeCaptions(text);
        for (int i = 0; i < protectedText.tokens().size(); i++) {
            out = out.replace(placeholder(i), protectedText.tokens().get(i));
        }
        return out;
    }

    private String normalizeCaptions(String text) {
        return text
                .replaceAll("\\bFigure\\s+(\\d+)", "图 $1")
                .replaceAll("\\bFig\\.\\s*(\\d+)", "图 $1")
                .replaceAll("\\bTable\\s+(\\d+)", "表 $1");
    }

    private String placeholder(int index) {
        return "⟦KEEP" + index + "⟧";
    }

    public record ProtectedText(String text, List<String> tokens) {}
}
