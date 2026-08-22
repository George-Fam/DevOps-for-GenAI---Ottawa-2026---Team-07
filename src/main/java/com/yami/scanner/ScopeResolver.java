package com.yami.scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared by {@link CheckovAdapter} and {@link TrivyAdapter}: both scanners take a single
 * directory to scan (checkov's {@code -d}, trivy's {@code fs <dir>}), not a glob list, so
 * a scope/{@code YAMI_SCOPE} override only usefully restricts a scan when every entry
 * cleanly names one real subdirectory. Anything else (the default policy's mixed
 * glob/bare-filename scope: {@code **}/*.tf, .github/workflows/**, Dockerfile, pom.xml)
 * can't be expressed this way, so callers fall back to a single full-root scan.
 */
final class ScopeResolver {

    private ScopeResolver() {
    }

    /** Returns one directory per scanPath entry, or an empty list if any entry doesn't
     * resolve to a real directory (glob metacharacters, a bare filename, etc.) - callers
     * should fall back to scanning root wholesale in that case. */
    static List<Path> resolveScanDirectories(Path root, List<String> scanPaths) {
        List<Path> dirs = new ArrayList<>();
        for (String p : scanPaths) {
            String stripped = p.endsWith("/**") ? p.substring(0, p.length() - 3) : p;
            if (stripped.isEmpty() || stripped.chars().anyMatch(c -> c == '*' || c == '?' || c == '[')) {
                return List.of();
            }
            Path candidate = root.resolve(stripped).normalize();
            if (!Files.isDirectory(candidate)) {
                return List.of();
            }
            dirs.add(candidate);
        }
        return dirs;
    }
}
