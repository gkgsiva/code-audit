# legacy-audit hosted site: one container, the audits folder on a volume, settings from
# environment variables (see docker-compose.yml and docs/hosting.md).
#
#   docker compose up --build -d    # then open http://localhost:8765
#
# Build options: set them in .env (docker compose reads it) or with --build-arg NAME=value.
#   BUILD_PROXY          company proxy for the build, e.g. http://proxy.example.com:8080
#   BUILD_NO_PROXY       hosts reached without the proxy, comma separated
#   APT_MIRROR           company Debian mirror instead of http://deb.debian.org/debian
#   APT_SECURITY_MIRROR  company mirror instead of http://deb.debian.org/debian-security
#                        (default: APT_MIRROR followed by -security)
#   PIP_INDEX_URL        company Python package mirror, if pypi.org is blocked
#   NPM_REGISTRY         company npm registry, if registry.npmjs.org is blocked
#   BASE_IMAGE           python:3.12-slim, or the same image from a company registry
#   WITH_SYSTEM_PACKAGES false = no Debian packages at all (no Git, Chromium, Node.js);
#                        builds where deb.debian.org cannot be reached     (default true)
#   WITH_CHROMIUM        Chromium for the PDF copies and Mermaid diagrams  (default true)
#   WITH_MERMAID         Node.js + Mermaid CLI for diagram pictures        (default true)
#   WITH_OSV             OSV-Scanner for dependency vulnerabilities        (default true)
#   OSV_VERSION          OSV-Scanner release, e.g. v2.2.4, or "latest"     (default latest)
# Company root certificates (a proxy that inspects HTTPS): put the .crt files in docker/certs/.
ARG BASE_IMAGE=python:3.12-slim
FROM ${BASE_IMAGE}

ARG BUILD_PROXY=
ARG BUILD_NO_PROXY=
ARG APT_MIRROR=
ARG APT_SECURITY_MIRROR=
ARG PIP_INDEX_URL=
ARG NPM_REGISTRY=
ARG WITH_SYSTEM_PACKAGES=true
ARG WITH_CHROMIUM=true
ARG WITH_MERMAID=true
ARG WITH_OSV=true
ARG OSV_VERSION=latest

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PUPPETEER_SKIP_DOWNLOAD=1

COPY docker/build-helper.sh /tmp/build-helper.sh

# Certificates first, so the Debian, Python and npm downloads below trust the company proxy.
COPY docker/certs/ /usr/local/share/ca-certificates/company/
RUN set -eu; \
    find /usr/local/share/ca-certificates/company -type f ! -name '*.crt' -delete; \
    update-ca-certificates
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    PIP_CERT=/etc/ssl/certs/ca-certificates.crt \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt \
    GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt

RUN set -eu; . /tmp/build-helper.sh; use_proxy; \
    if [ "$WITH_SYSTEM_PACKAGES" != "true" ]; then \
        echo "WITH_SYSTEM_PACKAGES=false: no Debian packages (no Git, Chromium, Node.js)"; \
        exit 0; \
    fi; \
    if [ -n "$APT_MIRROR" ]; then \
        security="${APT_SECURITY_MIRROR:-$APT_MIRROR-security}"; \
        for f in /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list; do \
            [ -f "$f" ] || continue; \
            sed -i -e "s|http://deb.debian.org/debian-security|$security|g" \
                   -e "s|http://deb.debian.org/debian\([ /]\)|$APT_MIRROR\1|g" \
                   -e "s|http://deb.debian.org/debian$|$APT_MIRROR|g" "$f"; \
        done; \
        echo "Debian packages from: $APT_MIRROR and $security"; \
    fi; \
    if ! apt-get update --error-on=any -o Acquire::Retries=3 > /tmp/apt.log 2>&1; then \
        cat /tmp/apt.log; \
        fail "could not reach the Debian package servers (apt-get update)." \
            "What apt said: $(grep -E '^(Err|E:|W:)' /tmp/apt.log | tail -n 4 | tr '\n' ' ')" \
            "Behind a company proxy: set BUILD_PROXY=http://your-proxy:port in .env" \
            "Proxy that inspects HTTPS: put the company root certificate (.crt) in docker/certs/" \
            "Company Debian mirror: set APT_MIRROR in .env" \
            "To get running now: set WITH_SYSTEM_PACKAGES=false in .env (no Git, PDFs or diagrams)"; \
    fi; \
    pkgs="git curl fonts-dejavu-core fonts-liberation"; \
    if [ "$WITH_CHROMIUM" = "true" ]; then pkgs="$pkgs chromium"; fi; \
    if [ "$WITH_MERMAID" = "true" ]; then pkgs="$pkgs nodejs npm"; fi; \
    if ! apt-get install -y --no-install-recommends -o Acquire::Retries=3 $pkgs > /tmp/apt.log 2>&1; then \
        tail -n 40 /tmp/apt.log; \
        fail "could not install the Debian packages: $pkgs" \
            "What apt said: $(grep -E '^(Err|E:)' /tmp/apt.log | tail -n 4 | tr '\n' ' ')" \
            "Smaller set: WITH_CHROMIUM=false and/or WITH_MERMAID=false in .env" \
            "No Debian packages at all: WITH_SYSTEM_PACKAGES=false in .env"; \
    fi; \
    rm -rf /var/lib/apt/lists/* /tmp/apt.log

# Optional extras: when one cannot be downloaded the build goes on without it and says so;
# Admin > Health on the site shows what is missing.
RUN set -eu; . /tmp/build-helper.sh; use_proxy; \
    if [ "$WITH_MERMAID" = "true" ]; then \
        if ! command -v npm > /dev/null; then \
            echo "WARNING: no Node.js in this image; diagram pictures are left out."; \
        else \
            if [ -n "$NPM_REGISTRY" ]; then npm config set --global registry "$NPM_REGISTRY"; fi; \
            if npm install -g --omit=dev @mermaid-js/mermaid-cli@11.4.2; then \
                npm cache clean --force; \
                mkdir -p /etc/legacy-audit; \
                printf '{"executablePath": "/usr/bin/chromium", "args": ["--no-sandbox"]}\n' \
                    > /etc/legacy-audit/puppeteer.json; \
                printf '#!/bin/sh\nexec mmdc -p /etc/legacy-audit/puppeteer.json "$@"\n' \
                    > /usr/local/bin/mmdc-container; \
                chmod 755 /usr/local/bin/mmdc-container; \
            else \
                echo "WARNING: could not install the Mermaid CLI from npm (set NPM_REGISTRY or" \
                     "BUILD_PROXY in .env); diagram pictures are left out."; \
            fi; \
        fi; \
    fi; \
    if [ "$WITH_OSV" = "true" ]; then \
        case "$(uname -m)" in aarch64|arm64) arch=arm64 ;; *) arch=amd64 ;; esac; \
        if [ "$OSV_VERSION" = "latest" ]; then base="latest/download"; else base="download/$OSV_VERSION"; fi; \
        url="https://github.com/google/osv-scanner/releases/$base/osv-scanner_linux_${arch}"; \
        if python -c "import sys, urllib.request; urllib.request.urlretrieve(sys.argv[1], sys.argv[2])" \
                "$url" /usr/local/bin/osv-scanner; then \
            chmod 755 /usr/local/bin/osv-scanner; \
        else \
            rm -f /usr/local/bin/osv-scanner; \
            echo "WARNING: could not download OSV-Scanner from github.com; dependency" \
                 "vulnerabilities are not checked (or set it on Admin > Paths)."; \
        fi; \
    fi

WORKDIR /opt/legacy-audit
COPY pyproject.toml README.md ./
COPY legacy_audit ./legacy_audit
COPY rules ./rules
COPY templates ./templates
RUN set -eu; . /tmp/build-helper.sh; use_proxy; \
    if [ -n "$PIP_INDEX_URL" ]; then export PIP_INDEX_URL; fi; \
    if ! pip install . ; then \
        fail "could not download the Python packages (pip install)." \
            "Behind a company proxy: set BUILD_PROXY=http://your-proxy:port in .env" \
            "Proxy that inspects HTTPS: put the company root certificate (.crt) in docker/certs/" \
            "Company Python mirror: set PIP_INDEX_URL in .env"; \
    fi; \
    legacy-audit version; \
    rm -f /tmp/build-helper.sh

# Runs as a normal user (uid 10001). docker-compose.yml can run it as another user id
# (AUDIT_UID/AUDIT_GID in .env) so it can write a folder of yours; HOME suits any user id.
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin audit \
    && mkdir -p /audits /sources \
    && chown audit:audit /audits \
    && chmod 1777 /home/audit
USER audit
ENV HOME=/home/audit

# Every setting can be changed in docker-compose.yml or with docker run -e NAME=value.
ENV LEGACY_AUDIT_AUDITS_ROOT=/audits \
    LEGACY_AUDIT_SOURCES_ROOT=/sources \
    LEGACY_AUDIT_HOST=0.0.0.0 \
    LEGACY_AUDIT_PORT=8765 \
    LEGACY_AUDIT_ADMINS=admin \
    LEGACY_AUDIT_MMDC=/usr/local/bin/mmdc-container

VOLUME ["/audits"]
EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/healthz' % os.environ.get('LEGACY_AUDIT_PORT', '8765'), timeout=4)"
# docker-compose.yml sets init: true (a small init process that stops child processes cleanly);
# with docker run, add --init.
CMD ["legacy-audit", "host"]
