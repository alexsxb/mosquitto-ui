#!/usr/bin/env bash
# Puts the release for a host without Docker together: target/mosquitto-ui-<version>.tar.gz with
#   app/         the UI (target/quarkus-app, built before: ./mvnw package)
#   broker/      agent.sh and the sources of the plugins, which install.sh builds on the host
#   standalone/  install.sh, the systemd units, mosquitto.conf
#   install.sh   the same, at the top
#   VERSION
# Used by the release workflow:  standalone/package.sh <version>
set -euo pipefail

VERSION=${1:?usage: standalone/package.sh <version>}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$ROOT/target/quarkus-app/quarkus-run.jar" ] || { echo "No target/quarkus-app, run ./mvnw package first" >&2; exit 1; }

name=mosquitto-ui-$VERSION
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dir=$work/$name
mkdir -p "$dir/broker" "$dir/standalone"
cp -r "$ROOT/target/quarkus-app" "$dir/app"
cp "$ROOT/docker/mosquitto/agent.sh" "$dir/broker/"
for plugin in inspect token-auth; do
    mkdir -p "$dir/broker/$plugin"
    cp "$ROOT/docker/mosquitto/$plugin"/*.c "$ROOT/docker/mosquitto/$plugin/Makefile" "$dir/broker/$plugin/"
    cp "$ROOT/docker/mosquitto/$plugin"/*.h "$dir/broker/$plugin/" 2>/dev/null || true
done
cp "$ROOT/standalone/install.sh" "$ROOT/standalone/mosquitto.conf" "$ROOT/standalone"/*.service "$dir/standalone/"
ln -s standalone/install.sh "$dir/install.sh"
printf '%s\n' "$VERSION" > "$dir/VERSION"
# the same bytes for the same content: owner, order and times fixed
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct)}" \
    -C "$work" -czf "$ROOT/target/$name.tar.gz" "$name"
(cd "$ROOT/target" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256")
echo "$ROOT/target/$name.tar.gz"
