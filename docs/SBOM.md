# Yami Software Bill of Materials (SBOM)

> **Project:** Yami - AI-Powered Security Gate for IaC, CI/CD, and Supply Chain
> **Theme:** Track 1 - Autonomous DevOps (AI-Powered CI/CD)
> **Version:** 1.0
> **Date:** 2026-08-22
> **Status:** Active

---

## 1. Java Dependencies

### 1.1 Runtime Dependencies

| Group ID | Artifact ID | Version | Purpose | Pin Status |
|---|---|---|---|---|
| `com.bertramlabs.plugins` | `hcl4j` | `0.9.8` | HCL/Terraform parsing | Version pin |
| `com.fasterxml.jackson.core` | `jackson-databind` | `2.22.2` | JSON serialization | Version pin |
| `com.fasterxml.jackson.core` | `jackson-annotations` | `2.22` | JSON annotations | Version pin |
| `com.fasterxml.jackson.core` | `jackson-core` | `2.22.2` | JSON core | Version pin |
| `com.fasterxml.jackson.dataformat` | `jackson-dataformat-yaml` | `2.22.2` | YAML parsing | Version pin |
| `software.amazon.awssdk` | `bedrockruntime` | `2.54.1` | AWS Bedrock client | Version pin |
| `org.xerial` | `sqlite-jdbc` | `3.53.2.1` | SQLite database | Version pin |

### 1.2 Test Dependencies

| Group ID | Artifact ID | Version | Scope | Pin Status |
|---|---|---|---|---|
| `org.junit.jupiter` | `junit-jupiter` | `6.1.3` | test | Version pin |

### 1.3 Build Plugins

| Group ID | Artifact ID | Version | Purpose |
|---|---|---|---|
| `org.apache.maven.plugins` | `maven-surefire-plugin` | `3.5.6` | Test execution |
| `org.apache.maven.plugins` | `maven-shade-plugin` | `3.6.2` | Fat JAR creation |

---

## 2. System Tools (Docker Image)

### 2.1 Base Image

| Component | Version | Pin Type | Pin Value |
|---|---|---|---|
| Eclipse Temurin JRE | `21-jre-jammy` | SHA256 digest | `sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf` |

### 2.2 Security and IaC Tools

| Tool | Version | Pin Type | Pin Value | Verification |
|---|---|---|---|---|
| Checkov | `3.3.13` | pip version | `checkov==3.3.13` | `checkov --version` |
| Terraform | `1.15.8` | Binary URL | `terraform_1.15.8_linux_amd64.zip` | `terraform version` |
| Actionlint | `1.7.12` | Binary URL | `actionlint_1.7.12_linux_amd64.tar.gz` | `actionlint --version` |
| Trivy | `0.74.0` | Binary + SHA256 | `sha256:2ae6fe3ee734b7fdf11335663e18c75ea12dccc76062f09f164a3b0f8be4371a` | `trivy --version` |
| OpenCode | `1.18.21` | Binary + SHA256 | `sha256:d910c3ed7613bb5791a328904615d41cc25b7d3a6b470e3199ab0426a995b38a` | `opencode --version` |
| gh CLI | `2.98.0` | Binary + SHA256 | `sha256:3b8ac6b30336802fc1a858d7c084e11cdf24ac1a761ca90b68022d7d729208de` | `gh --version` |

### 2.3 Known Gaps

| Tool | Gap | Severity | Plan |
|---|---|---|---|
| Actionlint | SHA256 not pinned | Medium | TODO: add SHA256 verification in Dockerfile |
| Terraform | SHA256 not pinned | Low | Version URL is deterministic; acceptable risk |

---

## 3. CI/CD Dependencies

### 3.1 GitHub Actions

All third-party actions are pinned by **full commit SHA** (not tags):

| Action | Version (SHA) | Purpose |
|---|---|---|
| `step-security/harden-runner` | `bf7454d06d71f1098171f2acdf0cd4708d7b5920` | Runner egress auditing |
| `actions/checkout` | `3d3c42e5aac5ba805825da76410c181273ba90b1` | Repository checkout |
| `actions/setup-java` | `b36c23c0d998641eff861008f374ee103c25ac73` | Java setup |
| `dorny/paths-filter` | `ceb8a2b8f2d89434be7ff52d3de7ec3738c5cc9d` | Path change detection |
| `aquasecurity/trivy-action` | `ed142fd0673e97e23eac54620cfb913e5ce36c25` | Trivy scanning |
| `github/codeql-action/upload-sarif` | `e4fba868fa4b1b91e1fdab776edc8cfbe6e9fb81` | SARIF upload |
| `actions/cache` | `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` | Semgrep cache |
| `actions/upload-artifact` | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | Artifact upload |

### 3.2 Python Tools (CI)

| Tool | Version | Installation | Purpose |
|---|---|---|---|
| Semgrep | `1.172.0` | pip in venv | SAST scanning |

---

## 4. SBOM Generation

### 4.1 Command

```bash
make sbom
```

This runs:

```bash
trivy fs --format spdx-json -o sbom.json .
```

### 4.2 Output

- **Format:** SPDX JSON
- **File:** `sbom.json`
- **Scope:** Full repository filesystem
- **Includes:** Java dependencies (pom.xml), system tools (Dockerfile), CI dependencies

### 4.3 CI Integration

The SBOM is generated automatically in `.github/workflows/security-scan.yml`:

```yaml
- name: Generate SBOM with Trivy
  uses: aquasecurity/trivy-action@...
  with:
    scan-type: fs
    scan-ref: .
    format: spdx-json
    output: sbom.json
```

The SBOM is uploaded as a GitHub artifact named `sbom`.

---

## 5. Supply Chain Security Controls

### 5.1 Pinning Policy

| Component Type | Pin Method | Rationale |
|---|---|---|
| Base image | SHA256 digest | Prevents tag squatting and image tampering |
| Downloaded binaries | SHA256 checksum | Verifies integrity after download |
| pip packages | Version pin | Prevents dependency confusion |
| Maven dependencies | Version pin | Reproducible builds |
| GitHub Actions | Commit SHA | Prevents tag hijacking |

### 5.2 Verification

- **Trivy SCA:** Scans `pom.xml` for vulnerabilities (HIGH/CRITICAL gate)
- **Semgrep SAST:** Scans source code for security issues (audit-only)
- **Governance manifest:** `.opencode/MANIFEST.json` verifies agent/skill integrity

### 5.3 Update Process

1. Update version in `Dockerfile` or `pom.xml`
2. Update SHA256 if applicable
3. Run `make sbom` to verify new dependencies
4. Run `make test-unit` to verify build
5. Commit with clear message: `chore: bump <tool> to <version>`
6. Regenerate manifest: `make manifest`

---

## 6. References

- [OWASP Top 10:2025 A03 Software Supply Chain Failures](https://owasp.org/Top10/2025/A03_2025-Software_Supply_Chain_Failures/)
- [SLSA (Supply-chain Levels for Software Artifacts)](https://slsa.dev)
- Yami Threat Model (`docs/THREAT_MODEL.md`)
- Yami System Card (`docs/SYSTEM_CARD.md`)
