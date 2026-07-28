package com.web.backen.ppt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rejects malformed or explosive Office ZIP packages before the heavier
 * template/parser code starts reading their entries.
 */
public final class PptArchiveGuard {

    private PptArchiveGuard() {}

    public static void validate(Path path, int maxEntries, long maxUncompressedBytes,
                                 long maxEntryBytes, int maxCompressionRatio) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Office 文件不存在");
        }
        long compressedBytes = Math.max(1L, Files.size(path));
        long totalUncompressed = 0L;
        int entries = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) continue;
                entries++;
                if (entries > Math.max(1, maxEntries)) {
                    throw new IOException("Office 文件包含过多压缩条目");
                }
                String name = entry.getName();
                if (name == null || name.isBlank() || name.startsWith("/") || name.contains("..")) {
                    throw new IOException("Office 文件包含不安全路径");
                }
                if (!names.add(name)) {
                    throw new IOException("Office 文件包含重复压缩条目");
                }
                long size = entry.getSize();
                if (size < 0) {
                    throw new IOException("Office 文件缺少可靠的解压大小信息");
                }
                if (size > maxEntryBytes) {
                    throw new IOException("Office 文件单个条目过大");
                }
                totalUncompressed = Math.addExact(totalUncompressed, size);
                if (totalUncompressed > maxUncompressedBytes) {
                    throw new IOException("Office 文件解压后超过资源上限");
                }
                if (size > 0 && size / compressedBytes > Math.max(1, maxCompressionRatio)) {
                    throw new IOException("Office 文件压缩比异常，疑似压缩炸弹");
                }
            }
        } catch (ArithmeticException e) {
            throw new IOException("Office 文件解压大小溢出", e);
        }
    }
}
