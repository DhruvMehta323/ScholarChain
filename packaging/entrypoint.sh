#!/usr/bin/env bash
# Container entrypoint shared by all ScholarChain service images.
# SERVICE_MAIN (set in the image) selects the main class. exec makes the JVM PID 1, so SIGTERM
# from the container runtime reaches the JVM shutdown hooks directly.
set -euo pipefail
: "${SERVICE_MAIN:?SERVICE_MAIN is not set in this image}"
# Defaults for a 512 MiB task: ~358 MiB heap ceiling, small footprint collector, fail fast on OOM.
# JAVA_OPTS (optional, supplied by the task definition) is appended and can override these.
# shellcheck disable=SC2086
exec java \
  -XX:MaxRAMPercentage=70.0 \
  -XX:InitialRAMPercentage=25.0 \
  -XX:+UseSerialGC \
  -XX:MaxMetaspaceSize=96m \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/urandom \
  ${JAVA_OPTS:-} \
  -cp "/opt/scholarchain/lib/*" \
  "${SERVICE_MAIN}"
