#!/bin/sh
# Prepares a Java build or runtime stage of the Spring Boot version; not part of the image.
#   trust_company_certs  adds docker/certs/*.crt to the Java trust store
#   maven_settings       writes ~/.m2/settings.xml for BUILD_PROXY, BUILD_NO_PROXY and MAVEN_MIRROR
#   system_packages      installs Debian packages (apt) through BUILD_PROXY and APT_MIRROR
#   osv_scanner          downloads OSV-Scanner from github.com (a warning when it cannot)
set -eu

trust_company_certs() {
    for crt in /tmp/certs/*.crt; do
        [ -f "$crt" ] || continue
        echo "Trusting company certificate $(basename "$crt")"
        keytool -importcert -noprompt -cacerts -storepass changeit \
            -alias "company-$(basename "$crt" .crt)" -file "$crt" >/dev/null
    done
}

maven_settings() {
    mkdir -p "$HOME/.m2"
    proxy=""
    if [ -n "${BUILD_PROXY:-}" ]; then
        # http://user:password@host:port or http://host:port
        rest="${BUILD_PROXY#*://}"
        rest="${rest%/}"
        user=""
        pass=""
        case "$rest" in
            *@*)
                creds="${rest%@*}"
                rest="${rest##*@}"
                user="${creds%%:*}"
                pass="${creds#*:}"
                ;;
        esac
        host="${rest%:*}"
        port="${rest##*:}"
        [ "$port" = "$rest" ] && port=80
        non_proxy=$(printf '%s' "${BUILD_NO_PROXY:-localhost}" | tr ',' '|')
        for scheme in http https; do
            proxy="$proxy<proxy><id>company-$scheme</id><active>true</active><protocol>$scheme</protocol>"
            proxy="$proxy<host>$host</host><port>$port</port>"
            [ -n "$user" ] && proxy="$proxy<username>$user</username><password>$pass</password>"
            proxy="$proxy<nonProxyHosts>$non_proxy</nonProxyHosts></proxy>"
        done
    fi
    mirror=""
    if [ -n "${MAVEN_MIRROR:-}" ]; then
        mirror="<mirror><id>company</id><mirrorOf>*</mirrorOf><url>$MAVEN_MIRROR</url></mirror>"
    fi
    cat > "$HOME/.m2/settings.xml" <<XML
<settings><proxies>$proxy</proxies><mirrors>$mirror</mirrors></settings>
XML
}

system_packages() {
    if [ "${WITH_SYSTEM_PACKAGES:-true}" != "true" ]; then
        echo "WITH_SYSTEM_PACKAGES=false: no Debian packages (no Git projects, no PDF copies)"
        return 0
    fi
    if [ -n "${BUILD_PROXY:-}" ]; then
        export http_proxy="$BUILD_PROXY" https_proxy="$BUILD_PROXY" no_proxy="${BUILD_NO_PROXY:-}"
    fi
    if [ -n "${APT_MIRROR:-}" ]; then
        security="${APT_SECURITY_MIRROR:-$APT_MIRROR-security}"
        for f in /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list; do
            [ -f "$f" ] || continue
            sed -i -e "s|http://deb.debian.org/debian-security|$security|g" \
                   -e "s|http://deb.debian.org/debian\([ /]\)|$APT_MIRROR\1|g" \
                   -e "s|http://deb.debian.org/debian$|$APT_MIRROR|g" "$f"
        done
        echo "Debian packages from: $APT_MIRROR and $security"
    fi
    if ! apt-get update --error-on=any -o Acquire::Retries=3 > /tmp/apt.log 2>&1; then
        cat /tmp/apt.log
        fail "could not reach the Debian package servers (apt-get update)." \
            "What apt said: $(grep -E '^(Err|E:|W:)' /tmp/apt.log | tail -n 4 | tr '\n' ' ')" \
            "Behind a company proxy: set BUILD_PROXY=http://your-proxy:port in .env" \
            "Proxy that inspects HTTPS: put the company root certificate (.crt) in docker/certs/" \
            "Company Debian mirror: set APT_MIRROR in .env" \
            "To get running now: set WITH_SYSTEM_PACKAGES=false in .env (no Git projects, no PDFs)"
    fi
    if ! apt-get install -y --no-install-recommends -o Acquire::Retries=3 "$@" > /tmp/apt.log 2>&1; then
        tail -n 40 /tmp/apt.log
        fail "could not install the Debian packages: $*" \
            "What apt said: $(grep -E '^(Err|E:)' /tmp/apt.log | tail -n 4 | tr '\n' ' ')" \
            "Smaller set: WITH_CHROMIUM=false in .env (no PDF copies)" \
            "No Debian packages at all: WITH_SYSTEM_PACKAGES=false in .env"
    fi
    rm -rf /var/lib/apt/lists/* /tmp/apt.log
}

osv_scanner() {
    case "$(uname -m)" in aarch64|arm64) arch=arm64 ;; *) arch=amd64 ;; esac
    if [ "$1" = "latest" ]; then base="latest/download"; else base="download/$1"; fi
    url="https://github.com/google/osv-scanner/releases/$base/osv-scanner_linux_${arch}"
    if command -v curl > /dev/null && curl -fsSL --retry 3 -o /usr/local/bin/osv-scanner "$url"; then
        chmod 755 /usr/local/bin/osv-scanner
    else
        rm -f /usr/local/bin/osv-scanner
        echo "WARNING: could not download OSV-Scanner from github.com; dependency vulnerabilities" \
             "are not checked (set BUILD_PROXY in .env, or osv_scanner in a project's audit.yaml)."
    fi
}

fail() {
    echo "" >&2
    echo "==================================================================" >&2
    echo "legacy-audit build stopped: $1" >&2
    shift
    for line in "$@"; do echo "  - $line" >&2; done
    echo "Step by step: docs/spring/docker.md, section 'Building at the office'." >&2
    echo "==================================================================" >&2
    exit 1
}
