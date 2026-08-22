---
name: owasp-cicd-top10
description: >-
  Skill OWASP Top 10 CI/CD Security Risks pour Yami. Guide le Judge et le Surgeon
  dans la décision et la remédiation des vulnérabilités CI/CD (CICD-SEC-1..10).
  Utilise ce skill quand le finding provient de CICD_RULES ou concerne les
  workflows GitHub Actions, les pipelines CI/CD, les actions non épinglées,
  les secrets exposés, ou le Poisoned Pipeline Execution (PPE).
compatibility: opencode
---

# owasp-cicd-top10

Skill OWASP Top 10 CI/CD Security Risks pour Yami. Guide le Judge et le Surgeon dans la décision et la remédiation des vulnérabilités CI/CD.

## Base

- **Projet OWASP** : https://owasp.org/www-project-top-10-ci-cd-security-risks/
- **Repo source** : https://github.com/OWASP/www-project-top-10-ci-cd-security-risks

## Risques couverts (CICD-SEC-1..10)

| ID | Titre | Patterns de remédiation |
|---|---|---|
| CICD-SEC-1 | Insufficient Flow Control Mechanisms | Branch protection, required reviews, CODEOWNERS |
| CICD-SEC-2 | Inadequate Identity and Access Management | OIDC, least-privilege tokens, short-lived credentials |
| CICD-SEC-3 | Dependency Chain Abuse | Pinned dependencies, lockfiles, private registries |
| CICD-SEC-4 | Poisoned Pipeline Execution (PPE) | **BLOCK constitutionnel** — jamais SAFE_FIX sans review humaine |
| CICD-SEC-5 | Insufficient PBAC | Pipeline-based access controls, scoped tokens |
| CICD-SEC-6 | Insufficient Credential Hygiene | Secret scanning, no hardcoded secrets, rotation |
| CICD-SEC-7 | Insecure System Configuration | Hardened runners, ephemeral environments |
| CICD-SEC-8 | Ungoverned Usage of 3rd Party Services | Pin actions by SHA, audit 3rd party access |
| CICD-SEC-9 | Improper Artifact Integrity Validation | Signed artifacts, SBOM, provenance (SLSA) |
| CICD-SEC-10 | Insufficient Logging and Visibility | Audit logs, immutable logs, alerting |

## Directives pour le Judge

- `pull_request_target` + untrusted checkout → **BLOCK** (PPE)
- Secrets exposés dans un contexte non sécurisé → **BLOCK**
- Actions non épinglées par SHA → **SAFE_FIX** (pin par commit SHA)
- Permissions trop larges dans le workflow (`permissions: write-all`) → **SAFE_FIX** (scoper)

## Directives pour le Surgeon

- Remplacer `uses: actions/checkout@v4` par `uses: actions/checkout@<full-sha> # v4`
- Scoper `permissions:` au minimum nécessaire
- Ajouter `CODEOWNERS` si manquant
- Ne jamais modifier `.github/workflows/` sans validation actionlint

## Références détaillées

Voir `references/CICD-SEC-01.md` à `references/CICD-SEC-10.md`.
