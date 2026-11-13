#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)

DEFAULT_JAVA_HOME=/usr/lib/jvm/java-11-openjdk-arm64
IMAGE_NAME=${IMAGE_NAME:-ocervinka/plccoms-mqtt-bridge:latest}
CONTAINER_NAME=${CONTAINER_NAME:-plccoms-mqtt-bridge}
CONFIG_FILE=${CONFIG_FILE:-$PROJECT_DIR/deploy/docker/config.yaml}
CONFIG_TARGET=/etc/plccoms-mqtt-bridge/config.yaml

is_jdk11() {
    [ -x "$1/bin/java" ] && [ -x "$1/bin/javac" ] || return 1
    java_version=$("$1/bin/java" -version 2>&1) || return 1
    case "$java_version" in
        *\"11.*|*\"11\"*) return 0 ;;
        *) return 1 ;;
    esac
}

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$DEFAULT_JAVA_HOME
elif ! is_jdk11 "$JAVA_HOME"; then
    echo "Ignoring JAVA_HOME without a working JDK 11: $JAVA_HOME" >&2
    JAVA_HOME=$DEFAULT_JAVA_HOME
fi

if ! is_jdk11 "$JAVA_HOME"; then
    echo "JDK 11 was not found at $JAVA_HOME. Set JAVA_HOME to an installed JDK 11." >&2
    exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker was not found in PATH." >&2
    exit 1
fi

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Configuration file was not found: $CONFIG_FILE" >&2
    exit 1
fi

cd "$PROJECT_DIR"

echo "Using Java: $JAVA_HOME/bin/java"
"$JAVA_HOME/bin/java" -version

echo "Building Docker image $IMAGE_NAME"
./gradlew -Djib.to.image="$IMAGE_NAME" clean jibDockerBuild

echo "Removing previous plccoms-mqtt-bridge container, if present"
for container_id in $(docker ps -aq); do
    mounted_config=$(docker inspect --format "{{range .Mounts}}{{if eq .Destination \"$CONFIG_TARGET\"}}{{.Destination}}{{end}}{{end}}" "$container_id")
    if [ "$mounted_config" = "$CONFIG_TARGET" ]; then
        docker rm -f "$container_id"
    fi
done

if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    docker rm -f "$CONTAINER_NAME"
fi

echo "Starting $CONTAINER_NAME"
docker run -d \
    --name "$CONTAINER_NAME" \
    --network host \
    --log-driver json-file \
    --log-opt max-size=10m \
    --log-opt max-file=5 \
    --restart unless-stopped \
    -v "$CONFIG_FILE:$CONFIG_TARGET:ro" \
    "$IMAGE_NAME"

echo "Container status:"
docker ps --filter "name=^/${CONTAINER_NAME}$"
echo "Follow logs with: docker logs -f --tail 50 $CONTAINER_NAME"
