#!/bin/sh
# Prepares a Java build or runtime stage of the Spring Boot version; not part of the image.
#   trust_company_certs  adds docker/certs/*.crt to the Java trust store
#   maven_settings       writes ~/.m2/settings.xml for BUILD_PROXY, BUILD_NO_PROXY and MAVEN_MIRROR
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
