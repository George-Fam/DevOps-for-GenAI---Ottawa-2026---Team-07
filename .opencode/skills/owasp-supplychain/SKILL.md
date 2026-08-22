---
name: owasp-supplychain
description: >-
  Skill OWASP Top 10:2025 A03 Software Supply Chain Failures + SLSA pour Yami.
  Guide le Judge et le Surgeon dans la décision et la remédiation des
  vulnérabilités supply chain. Utilise ce skill quand le finding provient de
  TRIVY ou concerne les dépendances non pinnées, les images Docker :latest,
  les téléchargements curl|bash non vérifiés, les packages obsolètes avec CVE,
  ou l'absence de SBOM/provenance.
compatibility: opencode
---

# owasp-supplychain

Skill OWASP Top 10:2025 A03 Software Supply Chain Failures + SLSA pour Yami. Guide le Judge et le Surgeon dans la décision et la remédiation des vulnérabilités supply chain.

## Base

- **OWASP A03:2025** : https://owasp.org/Top10/2025/A03_2025-Software_Supply_Chain_Failures/
- **SLSA** : https://slsa.dev

## Risques couverts

| Catégorie | Exemples | Patterns de remédiation |
|---|---|---|
| Dépendances non pinnées | `checkov` sans version fixe dans Dockerfile | Pinner par version + SHA256 |
| Images `:latest` | `FROM node:latest` | Pinner par digest SHA256 |
| `curl \| bash` | Téléchargement non vérifié | Remplacer par package manager + vérification checksum |
| Packages obsolètes | CVE connues dans dépendances | Mise à jour + scan Trivy |
| Pas de SBOM | Aucune traçabilité des composants | Générer et attacher SBOM |

## Directives pour le Judge

- `curl \| bash` ou téléchargement non vérifié → **BLOCK** (exfiltration/supply chain)
- Image Docker `:latest` → **SAFE_FIX** (pinner par digest)
- Outil pinné mais version obsolète avec CVE connue → **SAFE_FIX** (bump + re-pin)
- Pas de SBOM dans le repo → **HUMAN_REVIEW** (pas de fix automatique simple)

## Directives pour le Surgeon

- Remplacer `FROM node:latest` par `FROM node:20.11.0@sha256:...`
- Remplacer `pip3 install checkov` par `pip3 install checkov==3.2.50` (version pinnée)
- Ajouter vérification SHA256 après téléchargement de binaires
- Mettre à jour `pom.xml` dépendances avec versions patchées

## Références détaillées

Voir `references/A03.md` et `references/SLSA.md`.
