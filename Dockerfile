FROM eclipse-temurin:21-jre-jammy

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
# TODO: pin SHA256 after download verification
RUN curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin v0.54.1 \
    && trivy --version

# opencode — TODO: spike H+0-2 must pin version + SHA256
# Never unpinned 'curl | bash'. The spike will determine the exact version
# and binary URL to download with SHA256 verification.
# RUN curl -fsSL <pinned-opencode-url> -o /usr/local/bin/opencode \
#     && echo '<sha256>  /usr/local/bin/opencode' | sha256sum -c \
#     && chmod +x /usr/local/bin/opencode

RUN apt-get purge -y unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY target/yami.jar /yami.jar
COPY policies /policies
COPY .opencode /opt/yami/.opencode

ENV OPENCODE_CONFIG_DIR=/opt/yami/.opencode

ENTRYPOINT ["java", "-jar", "/yami.jar"]
