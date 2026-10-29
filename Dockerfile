# legacy-audit hosted site: one container, the audits folder on a volume, settings from
# environment variables (see docker-compose.yml and docs/hosting.md).
#
#   docker build -t legacy-audit .
#   docker compose up -d            # then open http://localhost:8765
#
# Build options (docker build --build-arg NAME=false ...):
#   WITH_CHROMIUM  Chromium for the PDF copies and Mermaid diagrams        (default true)
#   WITH_MERMAID   Node.js + Mermaid CLI for diagram pictures in PowerPoint (default true)
#   WITH_OSV       OSV-Scanner for dependency vulnerabilities               (default true)
#   OSV_VERSION    OSV-Scanner release, e.g. v2.2.4, or "latest"           (default latest)
#   PIP_INDEX_URL  a company Python package mirror, if pypi.org is blocked
# Behind a company proxy: docker build --build-arg HTTPS_PROXY=http://proxy:8080 ... and put
# the proxy's root certificate (.crt) in docker/certs/.
FROM python:3.12-slim

ARG WITH_CHROMIUM=true
ARG WITH_MERMAID=true
ARG WITH_OSV=true
ARG OSV_VERSION=latest
ARG PIP_INDEX_URL=

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PUPPETEER_SKIP_DOWNLOAD=1

RUN set -eux; \
    apt-get update; \
    pkgs="git ca-certificates curl tini fonts-dejavu-core fonts-liberation"; \
    if [ "$WITH_CHROMIUM" = "true" ]; then pkgs="$pkgs chromium"; fi; \
    if [ "$WITH_MERMAID" = "true" ]; then pkgs="$pkgs nodejs npm"; fi; \
    apt-get install -y --no-install-recommends $pkgs; \
    rm -rf /var/lib/apt/lists/*

# Company root certificates (a proxy that inspects HTTPS): put the .crt files in docker/certs/.
COPY docker/certs/ /usr/local/share/ca-certificates/company/
RUN update-ca-certificates
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    PIP_CERT=/etc/ssl/certs/ca-certificates.crt \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt \
    GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt

RUN set -eux; \
    if [ "$WITH_MERMAID" = "true" ]; then \
        npm install -g --omit=dev @mermaid-js/mermaid-cli@11.4.2; \
        npm cache clean --force; \
        mkdir -p /etc/legacy-audit; \
        printf '{"executablePath": "/usr/bin/chromium", "args": ["--no-sandbox"]}\n' \
            > /etc/legacy-audit/puppeteer.json; \
        printf '#!/bin/sh\nexec mmdc -p /etc/legacy-audit/puppeteer.json "$@"\n' \
            > /usr/local/bin/mmdc-container; \
        chmod 755 /usr/local/bin/mmdc-container; \
    fi; \
    if [ "$WITH_OSV" = "true" ]; then \
        arch="$(dpkg --print-architecture)"; \
        if [ "$OSV_VERSION" = "latest" ]; then base="latest/download"; else base="download/$OSV_VERSION"; fi; \
        curl -fsSL -o /usr/local/bin/osv-scanner \
            "https://github.com/google/osv-scanner/releases/$base/osv-scanner_linux_${arch}"; \
        chmod 755 /usr/local/bin/osv-scanner; \
    fi

WORKDIR /opt/legacy-audit
COPY pyproject.toml README.md ./
COPY legacy_audit ./legacy_audit
COPY rules ./rules
COPY templates ./templates
RUN set -eux; \
    if [ -n "$PIP_INDEX_URL" ]; then export PIP_INDEX_URL; fi; \
    pip install . ; \
    legacy-audit version

# Runs as a normal user; /audits must be writable by uid 10001 (compose's named volume is).
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin audit \
    && mkdir -p /audits /sources \
    && chown audit:audit /audits
USER audit

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
ENTRYPOINT ["tini", "--"]
CMD ["legacy-audit", "host"]
