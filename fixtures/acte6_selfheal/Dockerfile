# yami-fixture: acte6 — self-healing demo
# This Dockerfile intentionally uses an outdated tool version
# Yami should detect this and propose a bump PR

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-pip unzip curl git \
    && pip3 install --no-cache-dir checkov==3.2.0 \
    && curl -fsSL https://releases.hashicorp.com/terraform/1.15.8/terraform_1.15.8_linux_amd64.zip -o /tmp/tf.zip \
    && unzip /tmp/tf.zip -d /usr/local/bin \
    && rm /tmp/tf.zip

# actionlint — outdated version (v1.7.10 instead of v1.7.12)
RUN curl -fsSL https://github.com/rhysd/actionlint/releases/download/v1.7.10/actionlint_1.7.10_linux_amd64.tar.gz -o /tmp/actionlint.tar.gz \
    && tar -xzf /tmp/actionlint.tar.gz -C /usr/local/bin actionlint \
    && rm /tmp/actionlint.tar.gz

RUN apt-get purge -y unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY target/yami.jar /yami.jar
COPY policies /policies

ENTRYPOINT ["java", "-jar", "/yami.jar"]
