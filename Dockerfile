FROM eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-pip unzip curl git \
    && pip3 install --no-cache-dir checkov==3.2.50 \
    && curl -fsSL https://releases.hashicorp.com/terraform/1.15.8/terraform_1.15.8_linux_amd64.zip -o /tmp/tf.zip \
    && unzip /tmp/tf.zip -d /usr/local/bin \
    && rm /tmp/tf.zip

# actionlint — pinned binary for workflow validation (oracle indépendant)
# TODO: pin SHA256 after download verification
RUN curl -fsSL https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz -o /tmp/actionlint.tar.gz \
    && tar -xzf /tmp/actionlint.tar.gz -C /usr/local/bin actionlint \
    && rm /tmp/actionlint.tar.gz \
    && actionlint --version

# trivy — pinned binary for supply chain scanning
# SHA256 verified against GitHub release v0.74.0
RUN curl -fsSL https://github.com/aquasecurity/trivy/releases/download/v0.74.0/trivy_0.74.0_Linux-64bit.tar.gz -o /tmp/trivy.tar.gz \
    && echo '2ae6fe3ee734b7fdf11335663e18c75ea12dccc76062f09f164a3b0f8be4371a  /tmp/trivy.tar.gz' | sha256sum -c \
    && tar -xzf /tmp/trivy.tar.gz -C /usr/local/bin trivy \
    && rm /tmp/trivy.tar.gz \
    && trivy --version

# opencode — pinned v1.18.21 (spike H+0-2 passed)
# SHA256 verified against GitHub release
RUN curl -fsSL https://github.com/anomalyco/opencode/releases/download/v1.18.21/opencode-linux-x64.tar.gz -o /tmp/opencode.tar.gz \
    && echo 'd910c3ed7613bb5791a328904615d41cc25b7d3a6b470e3199ab0426a995b38a  /tmp/opencode.tar.gz' | sha256sum -c \
    && tar -xzf /tmp/opencode.tar.gz -C /usr/local/bin opencode \
    && rm /tmp/opencode.tar.gz \
    && opencode --version

# gh CLI — pinned binary; GithubAdapter (postComment/openPr/createBranch/
# commitChanges) shells out to `gh`, which this image never installed.
# SHA256 verified against GitHub release v2.98.0
RUN curl -fsSL https://github.com/cli/cli/releases/download/v2.98.0/gh_2.98.0_linux_amd64.tar.gz -o /tmp/gh.tar.gz \
    && echo '3b8ac6b30336802fc1a858d7c084e11cdf24ac1a761ca90b68022d7d729208de  /tmp/gh.tar.gz' | sha256sum -c \
    && tar -xzf /tmp/gh.tar.gz -C /tmp \
    && mv /tmp/gh_2.98.0_linux_amd64/bin/gh /usr/local/bin/gh \
    && rm -rf /tmp/gh.tar.gz /tmp/gh_2.98.0_linux_amd64 \
    && gh --version

# GitHub Actions mounts GITHUB_WORKSPACE from the runner host, owned by the
# runner's user, while this container always runs as root (see note below) -
# git's dubious-ownership check then refuses every git operation in that
# directory (both GithubAdapter's own git calls and gh's internal ones).
# --system (not --global) so it survives runtime HOME being passed through
# from the host and overriding whatever --global would have written.
RUN git config --system --add safe.directory /github/workspace

RUN apt-get purge -y unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY target/yami.jar /yami.jar
COPY policies /policies
COPY .opencode /root/.config/opencode
RUN ln -s /root/.config/opencode /.opencode

# No USER directive here on purpose: this image runs as a GitHub Actions Docker
# container action (action.yml), and GitHub's own guidance is that such actions
# must run as the default root user or GITHUB_WORKSPACE becomes unreadable/
# unwritable (UID of the mounted workspace won't match an arbitrary container
# user) - https://docs.github.com/en/actions/reference/workflows-and-actions/dockerfile-support
# Yami needs write access there for the Surgeon's edits and Publisher's git
# operations, so this is a deliberate, GitHub-mandated exception, not an
# oversight.
# nosemgrep: dockerfile.security.missing-user-entrypoint.missing-user-entrypoint
ENTRYPOINT ["java", "-jar", "/yami.jar"]
