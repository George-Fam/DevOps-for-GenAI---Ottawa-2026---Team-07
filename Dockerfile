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
# SHA256 verified against GitHub release v0.54.1
RUN curl -fsSL https://github.com/aquasecurity/trivy/releases/download/v0.54.1/trivy_0.54.1_Linux-64bit.tar.gz -o /tmp/trivy.tar.gz \
    && echo 'bbaaf8278b2a9bb49aa848fe23c8bfe19f7db4f5dc7b55a9793357cd78cb5ec5  /tmp/trivy.tar.gz' | sha256sum -c \
    && tar -xzf /tmp/trivy.tar.gz -C /usr/local/bin trivy \
    && rm /tmp/trivy.tar.gz \
    && trivy --version

# opencode — pinned v1.18.21 (spike H+0-2 passed)
# SHA256 verified against local install: c9485f62576606dbde6404647405df2401fada964b7f669f799dc125dbbeff99
RUN curl -fsSL https://github.com/opencode-ai/opencode/releases/download/v1.18.21/opencode-linux-amd64 -o /usr/local/bin/opencode \
    && echo 'c9485f62576606dbde6404647405df2401fada964b7f669f799dc125dbbeff99  /usr/local/bin/opencode' | sha256sum -c \
    && chmod +x /usr/local/bin/opencode \
    && opencode --version

RUN apt-get purge -y unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY target/yami.jar /yami.jar
COPY policies /policies
COPY .opencode /opt/yami/.opencode

ENV OPENCODE_CONFIG_DIR=/opt/yami/.opencode

ENTRYPOINT ["java", "-jar", "/yami.jar"]
