# legacy-audit backend (Spring Boot). Built by docker-compose.yml from the repository root.
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-25
# The runtime is Debian (its Chromium works in a container; Ubuntu's is a snap) with the Java
# runtime copied from the Temurin image.
ARG JRE_IMAGE=eclipse-temurin:25-jre
ARG BASE_IMAGE=debian:trixie-slim

FROM ${JRE_IMAGE} AS jre

FROM ${BUILD_IMAGE} AS build
ARG BUILD_PROXY=
ARG BUILD_NO_PROXY=
ARG MAVEN_MIRROR=
COPY docker/spring/java-build.sh /tmp/java-build.sh
COPY docker/certs/ /tmp/certs/
RUN . /tmp/java-build.sh && trust_company_certs && maven_settings
WORKDIR /src
# Dependencies first, so a code change does not download them again.
COPY backend/pom.xml backend/pom.xml
COPY backend/audit-model/pom.xml backend/audit-model/pom.xml
COPY backend/audit-engine/pom.xml backend/audit-engine/pom.xml
COPY backend/audit-reports/pom.xml backend/audit-reports/pom.xml
COPY backend/audit-ai/pom.xml backend/audit-ai/pom.xml
COPY backend/audit-cli/pom.xml backend/audit-cli/pom.xml
COPY backend/audit-server/pom.xml backend/audit-server/pom.xml
RUN . /tmp/java-build.sh; cd backend && mvn -B -ntp -q dependency:go-offline -pl audit-server -am \
    || fail "Maven could not download the libraries" \
        "At the office: set BUILD_PROXY (and BUILD_NO_PROXY) in .env" \
        "Or set MAVEN_MIRROR to the company Maven repository (Nexus, Artifactory)" \
        "Proxy that inspects HTTPS: put the company root certificate (.crt) in docker/certs/"
COPY rules/ rules/
COPY schemas/ schemas/
COPY backend/ backend/
RUN cd backend && mvn -B -ntp -q -DskipTests package -pl audit-server -am

FROM ${BASE_IMAGE}
ARG BUILD_PROXY=
ARG BUILD_NO_PROXY=
ARG APT_MIRROR=
ARG APT_SECURITY_MIRROR=
ARG WITH_SYSTEM_PACKAGES=true
ARG WITH_CHROMIUM=true
ARG WITH_OSV=true
ARG OSV_VERSION=latest
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    LANG=C.UTF-8 \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY docker/spring/java-build.sh /tmp/java-build.sh
COPY docker/certs/ /tmp/certs/
# Git (and ssh) for Git projects, Chromium for the PDF copies, fonts for the reports, and
# OSV-Scanner for dependency vulnerabilities. The company certificates go into Java's trust store
# and the system store, so Java, git and Chromium trust a proxy that inspects HTTPS. A missing
# optional extra is reported on Admin > Health; the build goes on without it.
RUN set -eu; . /tmp/java-build.sh; trust_company_certs; \
    pkgs="ca-certificates git openssh-client curl fontconfig fonts-dejavu-core fonts-liberation"; \
    if [ "$WITH_CHROMIUM" = "true" ]; then pkgs="$pkgs chromium"; fi; \
    system_packages $pkgs; \
    for crt in /tmp/certs/*.crt; do [ -f "$crt" ] && cp "$crt" /usr/local/share/ca-certificates/ || true; done; \
    if command -v update-ca-certificates > /dev/null; then update-ca-certificates > /dev/null; fi; \
    if [ "$WITH_OSV" = "true" ]; then osv_scanner "$OSV_VERSION"; fi; \
    rm -rf /tmp/java-build.sh /tmp/certs
COPY --from=build /src/backend/audit-server/target/legacy-audit-server.jar /app/legacy-audit-server.jar
# Runs as a normal user (uid 10001). docker-compose.yml can run it as another user id
# (AUDIT_UID/AUDIT_GID in .env) so it can write a folder of yours; HOME suits any user id
# (git and Chromium write there).
RUN groupadd --gid 10001 audit \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin audit \
    && mkdir -p /audits /sources \
    && chown audit:audit /audits \
    && chmod 1777 /home/audit
USER audit
ENV HOME=/home/audit
WORKDIR /app
VOLUME ["/audits"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD sh -c 'command -v curl > /dev/null || exit 0; curl -sf http://127.0.0.1:8080/api/health > /dev/null'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseStringDeduplication", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/legacy-audit-server.jar"]
