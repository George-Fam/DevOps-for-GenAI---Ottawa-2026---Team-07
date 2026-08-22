package com.yami.governance;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Modèle Jackson du manifest de gouvernance.
 * Chaîné : chaque manifest inclut le hash SHA-256 du manifest précédent.
 *
 * <p>Format JSON :
 * <pre>
 * {
 *   "version": "1",
 *   "generatedAt": "2026-08-22T14:30:00Z",
 *   "gitCommit": "abc123...",
 *   "previousManifestHash": "sha256-of-previous-manifest",
 *   "files": {
 *     ".opencode/agents/judge.md": "sha256...",
 *     ".opencode/skills/owasp-iac-security/SKILL.md": "sha256..."
 *   }
 * }
 * </pre>
 */
public record GovManifest(
    @JsonProperty("version") String version,
    @JsonProperty("generatedAt") String generatedAt,
    @JsonProperty("gitCommit") String gitCommit,
    @JsonProperty("previousManifestHash") String previousManifestHash,
    @JsonProperty("files") Map<String, String> files
) {}
