# Sourced by the RUN steps of the Dockerfile; not part of the image.
#   use_proxy  passes BUILD_PROXY / BUILD_NO_PROXY on to apt, pip, npm and the downloads
#   fail       prints what went wrong and what to try, then stops the build

use_proxy() {
    if [ -n "${BUILD_PROXY:-}" ]; then
        export http_proxy="$BUILD_PROXY" https_proxy="$BUILD_PROXY"
        export HTTP_PROXY="$BUILD_PROXY" HTTPS_PROXY="$BUILD_PROXY"
    fi
    if [ -n "${BUILD_NO_PROXY:-}" ]; then
        export no_proxy="$BUILD_NO_PROXY" NO_PROXY="$BUILD_NO_PROXY"
    fi
}

fail() {
    set +x
    echo "" >&2
    echo "==================================================================" >&2
    echo "legacy-audit build stopped: $1" >&2
    shift
    for line in "$@"; do echo "  - $line" >&2; done
    echo "Step by step: docs/hosting.md, section 'Building at the office'." >&2
    echo "==================================================================" >&2
    exit 1
}
