package com.yami.core;

import java.util.List;

/**
 * Contrat Surgeon → harness. Le Surgeon rapporte ce qu'il a modifié.
 * {@code deviationsFromIntent} est un signal, jamais un contrôle —
 * la détection de déviation est faite par Java (diff déterministe).
 */
public record PatchReport(
    List<String> filesModified,
    String summary,
    List<String> owaspReferences,
    List<String> deviationsFromIntent
) {}
