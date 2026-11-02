#!/bin/sh
# Prepares a Java build or runtime stage of the Spring Boot version; not part of the image.
#   trust_company_certs  adds docker/certs/*.crt to the Java trust store
#   maven_settings       writes ~/.m2/settings.xml for BUILD_PROXY, BUILD_NO_PROXY and MAVEN_MIRROR
#   system_packages      installs Ubuntu packages (apt) through BUILD_PROXY and APT_MIRROR
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
        echo "WITH_SYSTEM_PACKAGES=false: no Ubuntu packages (Git projects cannot be scanned)"
        return 0
    fi
    if [ -n "${BUILD_PROXY:-}" ]; then
        export http_proxy="$BUILD_PROXY" https_proxy="$BUILD_PROXY" no_proxy="${BUILD_NO_PROXY:-}"
    fi
    if [ -n "${APT_MIRROR:-}" ]; then
        for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list; do
            [ -f "$f" ] || continue
            sed -i -e "s|http://archive.ubuntu.com/ubuntu|$APT_MIRROR|g" \
                   -e "s|http://security.ubuntu.com/ubuntu|$APT_MIRROR|g" "$f"
        done
        echo "Ubuntu packages from: $APT_MIRROR"
    fi
    if ! apt-get update --error-on=any -o Acquire::Retries=3 > /tmp/apt.log 2>&1; then
        cat /tmp/apt.log
        fail "could not reach the Ubuntu package servers (apt-get update)." \
            "What apt said: $(grep -E '^(Err|E:|W:)' /tmp/apt.log | tail -n 4 | tr '\n' ' ')" \
            "Behind a company proxy: set BUILD_PROXY=http://your-proxy:port in .env" \
            "Proxy that inspects HTTPS: put the company root certificate (.crt) in docker/certs/" \
            "Company Ubuntu mirror: set APT_MIRROR in .env" \
            "To get running now: set WITH_SYSTEM_PACKAGES=false in .env (no Git projects)"
    fi
    if ! apt-get install -y --no-install-recommends -o Acquire::Retries=3 "$@" > /tmp/apt.log 2>&1; then
        tail -n 40 /tmp/apt.log
        fail "could not install the Ubuntu packages: $*" \
            "What apt said: $(grep -E '^(Err|E:)' /tmp/apt.log | tail -n 4 | tr '\n' ' ')" \
            "No Ubuntu packages at all: WITH_SYSTEM_PACKAGES=false in .env"
    fi
    rm -rf /var/lib/apt/lists/* /tmp/apt.log
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
