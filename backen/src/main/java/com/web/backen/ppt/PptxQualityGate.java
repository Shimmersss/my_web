package com.web.backen.ppt;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lightweight package/relationship gate that runs before a task is marked completed. */
public final class PptxQualityGate {

    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    public void validate(Path output, Integer expectedSlides) throws IOException {
        if (output == null || !Files.isRegularFile(output) || Files.size(output) < 512) {
            throw new IOException("PPTX 输出文件为空或过小");
        }
        try (ZipFile zip = new ZipFile(output.toFile())) {
            Set<String> names = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                names.add(entry.getName());
                if (!entry.isDirectory()) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                }
            }
            require(names, "[Content_Types].xml");
            require(names, "ppt/presentation.xml");
            require(names, "ppt/_rels/presentation.xml.rels");
            require(names, "_rels/.rels");

            Document presentation = parse(zip, "ppt/presentation.xml");
            NodeList slideIds = presentation.getElementsByTagNameNS(P_NS, "sldId");
            int slideCount = slideIds.getLength();
            if (slideCount <= 0 || slideCount > 40) {
                throw new IOException("PPTX 页数无效: " + slideCount);
            }
            if (expectedSlides != null && expectedSlides > 0 && slideCount != expectedSlides) {
                throw new IOException("PPTX 页数与计划不一致: expected=" + expectedSlides + ", actual=" + slideCount);
            }

            Document presentationRels = parse(zip, "ppt/_rels/presentation.xml.rels");
            NodeList relationships = presentationRels.getElementsByTagNameNS(REL_NS, "Relationship");
            int slideRelationships = 0;
            for (int i = 0; i < relationships.getLength(); i++) {
                var node = relationships.item(i);
                if (!"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
                        .equals(attribute(node, "Type"))) continue;
                slideRelationships++;
                String target = attribute(node, "Target");
                if (target.isBlank()) throw new IOException("PPTX slide relationship 缺少 Target");
                String normalized = target.startsWith("/") ? target.substring(1) : "ppt/" + target;
                require(names, normalized.replace("//", "/"));
            }
            if (slideRelationships != slideCount) {
                throw new IOException("PPTX slide relationship 数量不一致");
            }
            validateInternalRelationships(zip, names);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PPTX 结构检查失败: " + e.getMessage(), e);
        }
    }

    /** Validate every reachable OOXML relationship, not just the presentation slide list. */
    private void validateInternalRelationships(ZipFile zip, Set<String> names) throws Exception {
        Set<String> visitedRels = new HashSet<>();
        Deque<String> parts = new ArrayDeque<>();
        parts.add(""); // package root relationship part
        while (!parts.isEmpty()) {
            String part = parts.removeFirst();
            String relsName = relationshipsName(part);
            if (!names.contains(relsName) || !visitedRels.add(relsName)) continue;
            Document relationships = parse(zip, relsName);
            NodeList nodes = relationships.getElementsByTagNameNS(REL_NS, "Relationship");
            for (int index = 0; index < nodes.getLength(); index++) {
                var node = nodes.item(index);
                String target = attribute(node, "Target");
                if (target.isBlank()) throw new IOException("PPTX relationship 缺少 Target: " + relsName);
                String mode = attribute(node, "TargetMode");
                if ("External".equalsIgnoreCase(mode) || target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                    throw new IOException("PPTX 不允许外部 relationship: " + relsName);
                }
                String normalized = resolveTarget(relsName, target);
                require(names, normalized);
                parts.addLast(normalized);
            }
        }
    }

    private String relationshipsName(String part) {
        if (part == null || part.isBlank()) return "_rels/.rels";
        int slash = part.lastIndexOf('/');
        String parent = slash < 0 ? "" : part.substring(0, slash);
        String file = slash < 0 ? part : part.substring(slash + 1);
        return (parent.isBlank() ? "_rels/" : parent + "/_rels/") + file + ".rels";
    }

    private String resolveTarget(String relsName, String target) throws IOException {
        String sourcePart;
        if ("_rels/.rels".equals(relsName)) {
            sourcePart = "";
        } else {
            sourcePart = relsName.substring(0, relsName.length() - ".rels".length())
                    .replace("/_rels/", "/");
        }
        int slash = sourcePart.lastIndexOf('/');
        String base = slash < 0 ? "" : sourcePart.substring(0, slash);
        String combined = (base.isBlank() ? "" : base + "/") + target.replace('\\', '/');
        Path normalized = Path.of(combined).normalize();
        String result = normalized.toString().replace('\\', '/');
        if (result.isBlank() || result.startsWith("..") || result.startsWith("/")) {
            throw new IOException("PPTX relationship 路径不安全: " + relsName + " -> " + target);
        }
        return result;
    }

    private Document parse(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("PPTX 缺少部件: " + name);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = zip.getInputStream(entry)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private void require(Set<String> names, String name) throws IOException {
        if (!names.contains(name)) throw new IOException("PPTX 缺少部件: " + name);
    }

    private String attribute(org.w3c.dom.Node node, String name) {
        var attribute = node == null || node.getAttributes() == null
                ? null : node.getAttributes().getNamedItem(name);
        return attribute == null ? "" : attribute.getNodeValue();
    }
}
