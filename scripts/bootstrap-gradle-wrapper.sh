#!/usr/bin/env bash
set -euo pipefail

# Regenerate a working Gradle wrapper for this repo.
# This repo may contain a corrupted/empty `gradle/wrapper/gradle-wrapper.jar`,
# which will break `./gradlew` with `GradleWrapperMain` not found.
#
# Proxy:
# - curl respects: http_proxy / https_proxy / ALL_PROXY
# - For Gradle dependency downloads, configure JVM proxy via ~/.gradle/gradle.properties:
#   systemProp.http.proxyHost, systemProp.http.proxyPort, systemProp.https.proxyHost, systemProp.https.proxyPort

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

GRADLE_VERSION="${GRADLE_VERSION:-8.10}"
GRADLE_DIST_URL_DEFAULT="https://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_DIST_URL="${GRADLE_DIST_URL:-${GRADLE_DIST_URL_DEFAULT}}"

if ! command -v curl >/dev/null 2>&1; then
  echo "Missing dependency: curl" >&2
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "Missing dependency: unzip" >&2
  exit 1
fi

BOOTSTRAP_DIR="${REPO_ROOT}/.gradle-bootstrap"
ZIP_PATH="${BOOTSTRAP_DIR}/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_HOME="${BOOTSTRAP_DIR}/gradle-${GRADLE_VERSION}"

mkdir -p "${BOOTSTRAP_DIR}"

echo "Downloading Gradle ${GRADLE_VERSION} from: ${GRADLE_DIST_URL}"
curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 600 \
  -o "${ZIP_PATH}" \
  "${GRADLE_DIST_URL}"

rm -rf "${GRADLE_HOME}"
unzip -q -o "${ZIP_PATH}" -d "${BOOTSTRAP_DIR}"
if [[ ! -d "${GRADLE_HOME}" ]]; then
  # The official zip extracts to gradle-$version
  GRADLE_HOME="${BOOTSTRAP_DIR}/gradle-${GRADLE_VERSION}"
fi

GRADLE_BIN="${GRADLE_HOME}/bin/gradle"
if [[ ! -x "${GRADLE_BIN}" ]]; then
  echo "Gradle bootstrap failed: ${GRADLE_BIN} not found" >&2
  exit 1
fi

echo "Regenerating wrapper..."
"${GRADLE_BIN}" wrapper \
  --gradle-version "${GRADLE_VERSION}" \
  --distribution-type bin \
  --no-daemon

WRAPPER_PROPERTIES="${REPO_ROOT}/gradle/wrapper/gradle-wrapper.properties"
if [[ -f "${WRAPPER_PROPERTIES}" ]]; then
  # Use a plain URL here to avoid shell/sed escaping pitfalls.
  # Gradle wrapper supports unescaped `https://...` values in .properties.
  TMP_FILE="${WRAPPER_PROPERTIES}.tmp"
  awk -v url="${GRADLE_DIST_URL}" '
    BEGIN { foundUrl=0; foundTimeout=0 }
    /^distributionUrl=/ { print "distributionUrl=" url; foundUrl=1; next }
    /^networkTimeout=/ { print "networkTimeout=600000"; foundTimeout=1; next }
    { print }
    END {
      if (!foundUrl) print "distributionUrl=" url
      if (!foundTimeout) print "networkTimeout=600000"
    }
  ' "${WRAPPER_PROPERTIES}" > "${TMP_FILE}"
  mv "${TMP_FILE}" "${WRAPPER_PROPERTIES}"
  echo "Wrapper distributionUrl set to: ${GRADLE_DIST_URL}"
fi

echo "Wrapper regenerated. Verifying..."
bash "./gradlew" -version
