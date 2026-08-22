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

RUN apt-get purge -y unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY target/yami.jar /yami.jar
COPY policies /policies
COPY .opencode /root/.config/opencode
RUN ln -s /root/.config/opencode /.opencode

ENTRYPOINT ["java", "-jar", "/yami.jar"]
