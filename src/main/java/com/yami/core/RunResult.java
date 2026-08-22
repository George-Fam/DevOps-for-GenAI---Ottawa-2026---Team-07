package com.yami.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Résultat d'un run complet du pipeline Yami.
 *
 * <p>Contient les données nécessaires pour alimenter les outputs
 * GitHub Actions ({@code $GITHUB_OUTPUT}) : chemin de l'audit JSON,
 * nombre de findings détectés, et URLs des PRs de remédiation ouvertes.
 *
 * <p>{@code success} is false whenever the run must fail the gate closed —
 * kill switch triggered, governance integrity failure, or any finding that
 * failed processing. Callers must fail the process (non-zero exit) on
 * false — a systemic failure must never look like a passing check.
 */
public record RunResult(Path auditJson, int findingsCount, List<String> prUrls, boolean success) {}
