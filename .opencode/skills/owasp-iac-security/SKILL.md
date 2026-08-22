# owasp-iac-security

Skill OWASP Top 10 Infrastructure Security Risks 2024 + IaC Security Cheat Sheet pour Yami. Guide le Judge et le Surgeon dans la décision et la remédiation des vulnérabilités Infrastructure-as-Code.

## Base

- **Projet OWASP ISR 2024** : https://owasp.org/www-project-top-10-infrastructure-security-risks/
- **IaC Security Cheat Sheet** : https://cheatsheetseries.owasp.org/cheatsheets/Infrastructure_as_Code_Security_Cheat_Sheet.html

## Risques couverts (ISR01-10)

| ID | Titre | Patterns de remédiation |
|---|---|---|
| ISR01 | Outdated Software | Mise à jour des versions pinnées, veille CVE |
| ISR02 | Insufficient Threat Detection | Logging, monitoring, alerting |
| ISR03 | Insecure Configurations | Hardening, CIS benchmarks, least privilege |
| ISR04 | Insecure Resource and User Management | IAM minimal, RBAC, rotation |
| ISR05 | Insecure Use of Cryptography | TLS 1.3, AES-256-GCM, pas de secrets en clair |
| ISR06 | Insecure Network Access Management | VPC, SG restrictives, pas de 0.0.0.0/0 |
| ISR07 | Insecure Authentication Methods | MFA, pas de credentials par défaut |
| ISR08 | Information Leakage | Pas de données sensibles dans le code, logging contrôlé |
| ISR09 | Insecure Access to Resources | Scoping des rôles, pas d'admin généralisé |
| ISR10 | Insufficient Asset Management | Inventaire, tagging, documentation |

## Directives pour le Judge

- Bucket S3 public (`acl = public-read`) sans CloudFront → **SAFE_FIX** (restreindre ACL)
- Bucket S3 public + CloudFront (site web) → **HUMAN_REVIEW** (peut être intentionnel)
- Security Group avec `0.0.0.0/0` sur port sensible (22, 3389) → **SAFE_FIX**
- Pas de versioning/encryption sur bucket S3 → **SAFE_FIX**
- IAM policy avec `*` sur `Resource` + `Action` → **HUMAN_REVIEW** (peut être over-permissive intentionnel)

## Directives pour le Surgeon

- Ajouter `aws_s3_bucket_versioning` + `aws_s3_bucket_server_side_encryption_configuration` pour ISR03/ISR05
- Restreindre les Security Groups à des CIDR spécifiques pour ISR06
- Scoper les IAM policies au minimum nécessaire pour ISR04
- Ne jamais supprimer une ressource sans vérifier les dépendances (Investigator graph)

## Références détaillées

Voir `references/ISR01.md` à `references/ISR10.md`.
