#!/usr/bin/env sh
set -eu

APP_DIR="/app"
RUN_DIR="$APP_DIR/run/velocity"
PLUGINS_DIR="$RUN_DIR/plugins"

mkdir -p "$PLUGINS_DIR"
cp "$APP_DIR/docker/velocity/velocity.toml" "$RUN_DIR/velocity.toml"
cp "$APP_DIR/docker/velocity/velocity.toml" "$APP_DIR/run/velocity.toml"

./gradlew shadowJar --no-daemon --stacktrace

PLUGIN_JAR=$(ls -t /tmp/dockbridge-build/libs/*.jar | grep -v -E '(plain|sources)\.jar$' | head -n 1 || true)
if [ -z "$PLUGIN_JAR" ]; then
  echo "No shaded plugin jar found in /tmp/dockbridge-build/libs"
  exit 1
fi

cp "$PLUGIN_JAR" "$PLUGINS_DIR/$(basename "$PLUGIN_JAR")"

VELOCITY_VERSION="${VELOCITY_VERSION:-3.4.0-SNAPSHOT}"

if [ ! -f "$RUN_DIR/velocity.jar" ]; then
  BUILDS_JSON=$(curl -fsSL "https://api.papermc.io/v2/projects/velocity/versions/$VELOCITY_VERSION")
  BUILD=$(printf '%s' "$BUILDS_JSON" | sed -n 's/.*"builds":\[\(.*\)\].*/\1/p' | tr ',' '\n' | tail -n 1 | tr -d '[:space:]')
  if [ -z "$BUILD" ]; then
    echo "Failed to resolve Velocity build for $VELOCITY_VERSION"
    exit 1
  fi
  curl -fsSL -o "$RUN_DIR/velocity.jar" \
    "https://api.papermc.io/v2/projects/velocity/versions/$VELOCITY_VERSION/builds/$BUILD/downloads/velocity-$VELOCITY_VERSION-$BUILD.jar"
fi

exec java -jar "$RUN_DIR/velocity.jar"
