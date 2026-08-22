package com.yami.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Résultat d'un run complet du pipeline Yami.
 *
 * <p>Contient les données nécessaires pour alimenter les outputs
 * GitHub Actions ({@code $GITHUB_OUTPUT}) : chemin de l'audit JSON,
 * nombre de findings détectés, et URLs des PRs de remédiation ouvertes.
 */
public record RunResult(Path auditJson, int findingsCount, List<String> prUrls) {}
