# legacy-audit frontend (Angular), served by nginx. Built by docker-compose.spring.yml from the repository root.
ARG BUILD_IMAGE=node:22-alpine
ARG BASE_IMAGE=nginx:1.29-alpine

FROM ${BUILD_IMAGE} AS build
ARG BUILD_PROXY=
ARG BUILD_NO_PROXY=
ARG NPM_REGISTRY=
COPY docker/certs/ /tmp/certs/
RUN cat /tmp/certs/*.crt > /tmp/company-certs.pem 2>/dev/null || true
ENV NODE_EXTRA_CA_CERTS=/tmp/company-certs.pem
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN set -e; \
    if [ -n "$BUILD_PROXY" ]; then npm config set proxy "$BUILD_PROXY"; npm config set https-proxy "$BUILD_PROXY"; fi; \
    if [ -n "$BUILD_NO_PROXY" ]; then npm config set noproxy "$BUILD_NO_PROXY"; fi; \
    if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi; \
    npm ci --no-audit --no-fund || { \
        echo "legacy-audit build stopped: npm could not download the packages." >&2; \
        echo "  - At the office: set BUILD_PROXY in .env, or NPM_REGISTRY to the company npm mirror" >&2; \
        echo "  - Proxy that inspects HTTPS: put the company root certificate (.crt) in docker/certs/" >&2; \
        exit 1; }
COPY frontend/ ./
RUN npx ng build

FROM ${BASE_IMAGE}
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/frontend/browser/ /usr/share/nginx/html/
EXPOSE 80
