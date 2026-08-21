FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-pip unzip curl \
    && pip3 install --no-cache-dir checkov \
    && curl -fsSL https://releases.hashicorp.com/terraform/1.15.8/terraform_1.15.8_linux_amd64.zip -o /tmp/tf.zip \
    && unzip /tmp/tf.zip -d /usr/local/bin \
    && rm /tmp/tf.zip \
    && apt-get purge -y unzip curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY target/yami.jar /yami.jar
COPY policies /policies

ENTRYPOINT ["java", "-jar", "/yami.jar"]
