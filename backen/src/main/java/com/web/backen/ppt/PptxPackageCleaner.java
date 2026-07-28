package com.web.backen.ppt;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Removes unreferenced template media/chart parts after native template filling. */
public final class PptxPackageCleaner {

    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String RELS_ENTRY = "_rels/.rels";

    private PptxPackageCleaner() {}

    public static int clean(Path pptx) throws IOException {
        if (pptx == null || !Files.isRegularFile(pptx)) return 0;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        Set<String> reachable = reachableParts(entries);
        Set<String> removed = new HashSet<>();
        for (String name : entries.keySet()) {
            if (isRemovablePart(name) && !reachable.contains(name)) removed.add(name);
        }
        if (removed.isEmpty()) return 0;
        for (String name : Set.copyOf(removed)) {
            String rels = relationshipsName(name);
            if (entries.containsKey(rels)) removed.add(rels);
        }
        rewriteContentTypes(entries, removed);
        Path temp = pptx.resolveSibling(pptx.getFileName() + ".clean.tmp");
        try (OutputStream output = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (removed.contains(entry.getKey())) continue;
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        try {
            Files.move(temp, pptx, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, pptx, StandardCopyOption.REPLACE_EXISTING);
        }
        return removed.size();
    }

    private static boolean isRemovablePart(String name) {
        return name.startsWith("ppt/media/") || name.startsWith("ppt/charts/")
                || name.startsWith("ppt/embeddings/") || name.startsWith("ppt/diagrams/");
    }

    private static Set<String> reachableParts(Map<String, byte[]> entries) throws IOException {
        Set<String> reachable = new HashSet<>();
        Set<String> visitedRels = new HashSet<>();
        Deque<String> parts = new ArrayDeque<>();
        parts.add("");
        while (!parts.isEmpty()) {
            String part = parts.removeFirst();
            String rels = relationshipsName(part);
            if (!entries.containsKey(rels) || !visitedRels.add(rels)) continue;
            Document document = parse(entries.get(rels));
            NodeList relationships = document.getElementsByTagNameNS(REL_NS, "Relationship");
            for (int index = 0; index < relationships.getLength(); index++) {
                var node = relationships.item(index);
                String target = attribute(node, "Target");
                if (target.isBlank()) continue;
                if ("External".equalsIgnoreCase(attribute(node, "TargetMode"))
                        || target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) continue;
                String normalized = resolveTarget(rels, target);
                if (!entries.containsKey(normalized)) continue;
                reachable.add(normalized);
                parts.addLast(normalized);
            }
        }
        return reachable;
    }

    private static String relationshipsName(String part) {
        if (part == null || part.isBlank()) return RELS_ENTRY;
        int slash = part.lastIndexOf('/');
        String parent = slash < 0 ? "" : part.substring(0, slash);
        String file = slash < 0 ? part : part.substring(slash + 1);
        return (parent.isBlank() ? "_rels/" : parent + "/_rels/") + file + ".rels";
    }

    private static String resolveTarget(String relsName, String target) throws IOException {
        String sourcePart = RELS_ENTRY.equals(relsName) ? ""
                : relsName.substring(0, relsName.length() - ".rels".length()).replace("/_rels/", "/");
        int slash = sourcePart.lastIndexOf('/');
        String base = slash < 0 ? "" : sourcePart.substring(0, slash);
        Path normalized = Path.of((base.isBlank() ? "" : base + "/") + target.replace('\\', '/')).normalize();
        String result = normalized.toString().replace('\\', '/');
        if (result.isBlank() || result.startsWith("..") || result.startsWith("/")) {
            throw new IOException("PPTX relationship 路径不安全: " + relsName + " -> " + target);
        }
        return result;
    }

    private static void rewriteContentTypes(Map<String, byte[]> entries, Set<String> removed) {
        byte[] raw = entries.get("[Content_Types].xml");
        if (raw == null) return;
        String xml = new String(raw, StandardCharsets.UTF_8);
        for (String part : removed) {
            if (!part.startsWith("ppt/")) continue;
            String escaped = Pattern.quote("/" + part);
            xml = xml.replaceAll("(?s)<Override\\b[^>]*PartName=\"" + escaped + "\"[^>]*/>", "");
        }
        entries.put("[Content_Types].xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                return factory.newDocumentBuilder().parse(input);
            }
        } catch (Exception e) {
            throw new IOException("PPTX relationship 解析失败", e);
        }
    }

    private static String attribute(org.w3c.dom.Node node, String name) {
        var attribute = node == null || node.getAttributes() == null
                ? null : node.getAttributes().getNamedItem(name);
        return attribute == null ? "" : attribute.getNodeValue();
    }
}
