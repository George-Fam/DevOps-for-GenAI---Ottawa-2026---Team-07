package com.yami.verifier;

import com.yami.core.Finding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcule le diff déterministe entre deux versions d'un fichier et vérifie
 * que les régions changées intersectent la localisation du finding.
 *
 * <p>La détection de déviation est faite par Java (ce module), pas par le
 * Surgeon. {@code deviationsFromIntent} dans PatchReport est un signal,
 * jamais un contrôle.
 */
public class DeviationDiff {

    public record DiffResult(
        boolean intersectsFinding,
        List<String> changedLines,
        String preHash,
        String postHash
    ) {}

    public DiffResult compute(Path fileBefore, Path fileAfter, Finding finding) {
        String preHash = sha256(fileBefore);
        String postHash = sha256(fileAfter);

        List<String> beforeLines;
        List<String> afterLines;
        try {
            beforeLines = Files.readAllLines(fileBefore);
            afterLines = Files.readAllLines(fileAfter);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<String> changedLines = new ArrayList<>();
        int maxLines = Math.max(beforeLines.size(), afterLines.size());
        for (int i = 0; i < maxLines; i++) {
            String before = i < beforeLines.size() ? beforeLines.get(i) : null;
            String after = i < afterLines.size() ? afterLines.get(i) : null;
            if (!java.util.Objects.equals(before, after)) {
                changedLines.add("Line " + (i + 1) + ": " + after);
            }
        }

        boolean intersects = changedLines.stream().anyMatch(cl -> {
            int lineNum = extractLineNumber(cl);
            return lineNum >= finding.line() - 3 && lineNum <= finding.line() + 3;
        });

        return new DiffResult(intersects, changedLines, preHash, postHash);
    }

    private static int extractLineNumber(String changedLine) {
        String prefix = "Line ";
        int start = changedLine.indexOf(prefix);
        if (start < 0) return -1;
        int end = changedLine.indexOf(":", start + prefix.length());
        if (end < 0) return -1;
        try {
            return Integer.parseInt(changedLine.substring(start + prefix.length(), end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String sha256(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
