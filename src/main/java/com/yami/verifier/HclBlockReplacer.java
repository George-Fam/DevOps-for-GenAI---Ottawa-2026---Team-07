package com.yami.verifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates a top-level {@code resource "type" "name" { ... }} block by text scan (brace
 * counting, string-aware) and splices in a replacement. Deliberately does not round-trip
 * through an HCL AST: hcl4j's public API evaluates blocks into a value Map and does not
 * expose the source Symbol tree needed for lossless re-serialization, so an AST round-trip
 * would silently drop comments/formatting elsewhere in the file. A surgical text splice
 * keeps everything outside the target block byte-for-byte untouched, which is what a
 * minimal PR diff needs.
 */
public class HclBlockReplacer {

    public void replace(Path terraformFile, String resourceAddress, String replacementBlock) {
        int dot = resourceAddress.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("resourceAddress must be \"<type>.<name>\": " + resourceAddress);
        }
        String type = resourceAddress.substring(0, dot);
        String name = resourceAddress.substring(dot + 1);

        List<String> lines;
        try {
            lines = Files.readAllLines(terraformFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Pattern blockStart = Pattern.compile(
            "^\\s*resource\\s+\"" + Pattern.quote(type) + "\"\\s+\"" + Pattern.quote(name) + "\"\\s*\\{");

        int startLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (blockStart.matcher(lines.get(i)).find()) {
                startLine = i;
                break;
            }
        }
        if (startLine < 0) {
            throw new BlockNotFoundException(resourceAddress, terraformFile);
        }

        int endLine = findBlockEnd(lines, startLine);

        List<String> replacementLines = List.of(replacementBlock.strip().split("\n", -1));

        List<String> result = new java.util.ArrayList<>(lines.subList(0, startLine));
        result.addAll(replacementLines);
        result.addAll(lines.subList(endLine + 1, lines.size()));

        try {
            Files.writeString(terraformFile, String.join("\n", result) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int findBlockEnd(List<String> lines, int startLine) {
        int depth = 0;
        boolean inString = false;
        boolean sawFirstBrace = false;

        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (inString) {
                    if (ch == '"' && (c == 0 || line.charAt(c - 1) != '\\')) {
                        inString = false;
                    }
                    continue;
                }
                if (ch == '"') {
                    inString = true;
                } else if (ch == '{') {
                    depth++;
                    sawFirstBrace = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (sawFirstBrace && depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced braces: block starting at line " + (startLine + 1) + " never closes");
    }

    public static class BlockNotFoundException extends RuntimeException {
        public BlockNotFoundException(String resourceAddress, Path file) {
            super("no resource block for \"" + resourceAddress + "\" found in " + file);
        }
    }
}
