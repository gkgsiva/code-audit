# legacy-audit backend (Spring Boot). Built by docker-compose.yml from the repository root.
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-25
ARG BASE_IMAGE=eclipse-temurin:25-jre

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
ARG AUDIT_UID=10001
ARG AUDIT_GID=10001
ARG BUILD_PROXY=
ARG BUILD_NO_PROXY=
ARG APT_MIRROR=
ARG WITH_SYSTEM_PACKAGES=true
COPY docker/spring/java-build.sh /tmp/java-build.sh
COPY docker/certs/ /tmp/certs/
# Git (and ssh for git@ addresses) to clone the Git projects; the company certificates are added
# to the system store as well, so git trusts a proxy that inspects HTTPS.
RUN . /tmp/java-build.sh && trust_company_certs \
    && for crt in /tmp/certs/*.crt; do [ -f "$crt" ] && cp "$crt" /usr/local/share/ca-certificates/ || true; done \
    && system_packages git openssh-client ca-certificates \
    && { command -v update-ca-certificates > /dev/null && update-ca-certificates > /dev/null || true; } \
    && rm -rf /tmp/java-build.sh /tmp/certs \
    && mkdir -p /app /audits /sources
COPY --from=build /src/backend/audit-server/target/legacy-audit-server.jar /app/legacy-audit-server.jar
WORKDIR /app
EXPOSE 8080
USER ${AUDIT_UID}:${AUDIT_GID}
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/legacy-audit-server.jar"]
