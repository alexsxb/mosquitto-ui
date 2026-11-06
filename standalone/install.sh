#!/usr/bin/env bash
# Installs or updates mosquitto-ui without Docker, as two systemd services:
#   mosquitto-ui-broker   Mosquitto 2.1 with the agent of docker/mosquitto/agent.sh and the plugins of mosquitto-ui
#   mosquitto-ui          the web UI (Java 21)
# Run as root from the unpacked release (mosquitto-ui-<version>.tar.gz):
#   tar xzf mosquitto-ui-<version>.tar.gz && cd mosquitto-ui-<version> && sudo ./install.sh
# The same command with a newer release updates: the UI restarts, the broker only when Mosquitto, the plugins or the agent
# changed. Settings and data stay. Debian 12/13, Ubuntu 22.04/24.04, Fedora, RHEL/Rocky/Alma 9 and their kin.
#
# Options:
#   --port <port>          port of the UI (8080), only on the first install
#   --no-start             install, but do not start the services
#   --skip-packages        do not install packages: build tools, libraries and Java 21 are there already
#
# Where things go:
#   /opt/mosquitto-ui/releases/<version>, /opt/mosquitto-ui/current   the UI and the agent
#   /usr/local/sbin/mosquitto, /usr/local/lib/...                     Mosquitto 2.1, built from source
#   /etc/mosquitto-ui/                                                mosquitto.conf, mosquitto-ui.env, broker.env
#   /var/lib/mosquitto                                                data of the broker: dynamic security, sessions, certificates
#   /var/lib/mosquitto-ui/{state,shared,backups}                      data of the UI, the directory it shares with the agent
set -euo pipefail

MOSQUITTO_VERSION=2.1.2
MOSQUITTO_SHA256=fd905380691ac65ea5a93779e8214941829e3d6e038d5edff9eac5fd74cbed02

HERE=$(cd "$(dirname "$0")" && pwd)
# also as standalone/install.sh of the release
[ -f "$HERE/VERSION" ] || HERE=$(dirname "$HERE")
ETC=/etc/mosquitto-ui
OPT=/opt/mosquitto-ui
DATA=/var/lib/mosquitto
UI_DATA=/var/lib/mosquitto-ui
PLUGINS=/usr/local/lib/mosquitto-ui
UNITS=/etc/systemd/system

port=8080
start=yes
packages=yes
while [ $# -gt 0 ]; do
    case "$1" in
        --port) port=$2; shift 2 ;;
        --oidc) echo "--oidc is not needed any longer: identity providers are set up on the page Login" >&2; shift ;;
        --no-start) start=no; shift ;;
        --skip-packages) packages=no; shift ;;
        -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option $1, see --help" >&2; exit 2 ;;
    esac
done

# Whether two files have the same content; cmp is not on every minimal system
same() { [ -f "$1" ] && [ -f "$2" ] && [ "$(sha256sum < "$1")" = "$(sha256sum < "$2")" ]; }
say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || fail "run as root (sudo $0)"
[ -d /run/systemd/system ] || fail "systemd is not running on this host"
[ -f "$HERE/VERSION" ] && [ -d "$HERE/app" ] && [ -d "$HERE/broker" ] || fail "run from the unpacked release, $HERE has no app/ and broker/"
VERSION=$(cat "$HERE/VERSION")
case "$port" in ''|*[!0-9]*) fail "--port needs a number" ;; esac

# --- packages ---------------------------------------------------------------------------------------------------------
install_packages() {
    if command -v apt-get >/dev/null; then
        say "Packages (apt)"
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends build-essential cmake pkg-config curl ca-certificates gnupg \
            libssl-dev libcjson-dev libsqlite3-dev libargon2-dev libedit-dev >/dev/null
        if apt-cache policy openjdk-21-jre-headless 2>/dev/null | grep -q 'Candidate: [0-9]'; then
            apt-get install -y -qq --no-install-recommends openjdk-21-jre-headless >/dev/null
        elif ! command -v java >/dev/null || ! java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
            # Debian 12 has no Java 21: Temurin of Eclipse Adoptium
            say "Java 21 from Eclipse Temurin (packages.adoptium.net)"
            install -d -m 755 /etc/apt/keyrings
            curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
            echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
                > /etc/apt/sources.list.d/adoptium.list
            apt-get update -qq
            apt-get install -y -qq temurin-21-jre >/dev/null
        fi
    elif command -v dnf >/dev/null; then
        say "Packages (dnf)"
        # cJSON and libargon2 are in EPEL on RHEL and its kin
        if [ -f /etc/redhat-release ] && ! grep -qi fedora /etc/redhat-release; then
            dnf install -y -q epel-release >/dev/null || true
            dnf config-manager --set-enabled crb >/dev/null 2>&1 || true
        fi
        # curl is there already, as curl or curl-minimal, which conflict
        dnf install -y -q gcc gcc-c++ make cmake pkgconf ca-certificates openssl-devel cjson-devel sqlite-devel \
            libargon2-devel libedit-devel java-21-openjdk-headless >/dev/null
    else
        fail "neither apt-get nor dnf: install a C compiler, make, OpenSSL, cJSON, SQLite, libargon2, libedit and Java 21, then run with --skip-packages"
    fi
}
[ "$packages" = yes ] && install_packages

java_bin=$(command -v java || true)
if [ -d /usr/lib/jvm ]; then
    # several Javas: the 21
    for candidate in /usr/lib/jvm/*-21*/bin/java /usr/lib/jvm/*21*/bin/java; do
        [ -x "$candidate" ] && java_bin=$candidate && break
    done
fi
[ -n "$java_bin" ] || fail "no Java found, mosquitto-ui needs Java 21 or newer"
java_major=$("$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
[ "${java_major:-0}" -ge 21 ] 2>/dev/null || fail "$java_bin is Java $java_major, mosquitto-ui needs 21 or newer"

# --- users ------------------------------------------------------------------------------------------------------------
nologin=$(command -v nologin || echo /bin/false)
getent group mosquitto >/dev/null || groupadd --system mosquitto
getent passwd mosquitto >/dev/null || useradd --system --gid mosquitto --home-dir "$DATA" --no-create-home --shell "$nologin" mosquitto
getent group mosquitto-ui >/dev/null || groupadd --system mosquitto-ui
getent passwd mosquitto-ui >/dev/null || useradd --system --gid mosquitto-ui --home-dir "$UI_DATA" --no-create-home --shell "$nologin" mosquitto-ui
UI_UID=$(id -u mosquitto-ui)

# --- Mosquitto --------------------------------------------------------------------------------------------------------
# Built like the official image (docker/2.1-ubuntu of its source), installed below /usr/local, so that it does not get in the
# way of a Mosquitto package of the distribution. The headers stay for the plugins.
SRC=/usr/local/src/mosquitto-$MOSQUITTO_VERSION
broker_changed=no
installed=$(/usr/local/sbin/mosquitto -h 2>/dev/null | sed -n 's/^mosquitto version \([0-9.]*\).*/\1/p' | head -1 || true)
if [ "$installed" != "$MOSQUITTO_VERSION" ] || [ ! -d "$SRC/include" ]; then
    say "Mosquitto $MOSQUITTO_VERSION from source (a few minutes)"
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 -o "$work/mosquitto.tar.gz" "https://mosquitto.org/files/source/mosquitto-$MOSQUITTO_VERSION.tar.gz"
    echo "$MOSQUITTO_SHA256  $work/mosquitto.tar.gz" | sha256sum -c --quiet - || fail "checksum of the Mosquitto source does not match"
    rm -rf "$SRC"
    mkdir -p "$SRC"
    tar xzf "$work/mosquitto.tar.gz" -C "$SRC" --strip-components=1
    make -C "$SRC" -j "$(nproc)" WITH_ADNS=no WITH_DOCS=no WITH_SHARED_LIBRARIES=yes WITH_SRV=no WITH_STRIP=yes WITH_WEBSOCKETS=yes \
        WITH_HTTP_API=no >"$work/build.log" 2>&1 || { tail -30 "$work/build.log"; fail "building Mosquitto failed"; }
    install -d /usr/local/sbin /usr/local/bin /usr/local/lib
    install -m755 "$SRC/src/mosquitto" /usr/local/sbin/mosquitto
    for tool in client/mosquitto_pub client/mosquitto_sub client/mosquitto_rr apps/mosquitto_ctrl/mosquitto_ctrl apps/mosquitto_passwd/mosquitto_passwd; do
        [ -f "$SRC/$tool" ] && install -m755 "$SRC/$tool" "/usr/local/bin/$(basename "$tool")"
    done
    install -m644 "$SRC/lib/libmosquitto.so.1" /usr/local/lib/libmosquitto.so.1
    ln -sf libmosquitto.so.1 /usr/local/lib/libmosquitto.so
    for plugin in dynamic-security/mosquitto_dynamic_security persist-sqlite/mosquitto_persist_sqlite; do
        install -m755 "$SRC/plugins/$plugin.so" "/usr/local/lib/$(basename "$plugin").so"
    done
    # libmosquitto for the clients; some distributions leave /usr/local/lib out
    [ -f /etc/ld.so.conf.d/mosquitto-ui.conf ] || echo /usr/local/lib > /etc/ld.so.conf.d/mosquitto-ui.conf
    ldconfig
    broker_changed=yes
fi

# --- plugins and agent of mosquitto-ui ----------------------------------------------------------------------------------
say "Plugins of mosquitto-ui"
build=$(mktemp -d)
cp -r "$HERE/broker/inspect" "$HERE/broker/token-auth" "$build/"
# without -Werror of the Makefiles: another compiler may warn about other things
plugin_cflags="-O2 -std=gnu11 -fPIC -Wall -Wno-unused-parameter -I$SRC/include"
make -s -C "$build/token-auth" CFLAGS="$plugin_cflags" >/dev/null || fail "building the token login plugin failed"
make -s -C "$build/inspect" CFLAGS="$plugin_cflags" >/dev/null || fail "building the inspect plugin failed"
install -d "$PLUGINS"
for plugin in token-auth/mosquitto_token_auth.so inspect/mosquitto_inspect.so; do
    target="$PLUGINS/$(basename "$plugin")"
    if ! same "$build/$plugin" "$target"; then
        install -m755 "$build/$plugin" "$target"
        broker_changed=yes
    fi
done
rm -rf "$build"

# --- the release --------------------------------------------------------------------------------------------------------
say "mosquitto-ui $VERSION"
release=$OPT/releases/$VERSION
previous_agent=$(readlink -f "$OPT/current" 2>/dev/null || true)/broker/agent.sh
rm -rf "$release.new"
install -d "$OPT/releases"
cp -r "$HERE" "$release.new"
rm -rf "$release"
mv "$release.new" "$release"
chown -R root:root "$release"
chmod -R go-w "$release"
same "$previous_agent" "$release/broker/agent.sh" || broker_changed=yes
ln -sfn "$release" "$OPT/current.new"
mv -T "$OPT/current.new" "$OPT/current"
# keep the current and the one before
ls -1dt "$OPT"/releases/*/ 2>/dev/null | tail -n +3 | xargs -r rm -rf

# --- directories ---------------------------------------------------------------------------------------------------------
install -d -m 755 -o root -g root "$UI_DATA"
install -d -m 750 -o mosquitto -g mosquitto "$DATA"
install -d -m 700 -o mosquitto-ui -g mosquitto-ui "$UI_DATA/state" "$UI_DATA/backups"
# the agent gives the subdirectories to the UI and the broker
install -d -m 755 -o root -g root "$UI_DATA/shared"

# --- settings, only the first time ----------------------------------------------------------------------------------------
install -d -m 755 "$ETC"
first=no
if [ ! -f "$ETC/mosquitto-ui.env" ]; then
    first=yes
    say "Settings in $ETC"
    broker_password=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
    secret_key=$(head -c 32 /dev/urandom | base64)
    umask 077
    cat > "$ETC/broker.env" <<EOF
# Password of the broker user admin, only read on the first start of the broker (dynamic security)
MOSQUITTO_DYNSEC_PASSWORD=$broker_password
# The user of the UI: the agent gives it the shared directory
UI_UID=$UI_UID
EOF
    cat > "$ETC/mosquitto-ui.env" <<EOF
# Settings of mosquitto-ui, see the README (Configuration). systemctl restart mosquitto-ui after a change.
QUARKUS_HTTP_PORT=$port
MOSQUITTO_HOST=127.0.0.1
MOSQUITTO_PORT=1883
MOSQUITTO_USERNAME=admin
MOSQUITTO_PASSWORD=$broker_password
# Encrypts the secrets in the state directory and signs backups. Keep a copy apart from this host: it cannot change later
MOSQUITTO_UI_SECRET_KEY=$secret_key
MOSQUITTO_UI_STATE_DIR=$UI_DATA/state
MOSQUITTO_UI_SHARED_DIR=$UI_DATA/shared
MOSQUITTO_UI_BROKER_SHARED_DIR=$UI_DATA/shared
MOSQUITTO_UI_CERT_DIR=$DATA/certs
MOSQUITTO_UI_BACKUP_LOCAL_ROOT=$UI_DATA/backups

# Login: UI users and identity providers are set up on the page Login. Until a provider is on, the local login is, the
# first UI user (admin) gets its password in $UI_DATA/state/initial-password. local keeps the local login on whatever the
# page says: the way back in when nobody can log in at the provider any longer
#MOSQUITTO_UI_AUTH=local

# Behind a reverse proxy on this host (redirects and addresses from X-Forwarded-*)
#QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING=true
#QUARKUS_HTTP_PROXY_ALLOW_X_FORWARDED=true
#QUARKUS_HTTP_PROXY_TRUSTED_PROXIES=127.0.0.1

JAVA_OPTS=-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError
EOF
    umask 022
    chown root:mosquitto-ui "$ETC/mosquitto-ui.env"
    chmod 640 "$ETC/mosquitto-ui.env"
    chmod 600 "$ETC/broker.env"
fi
if [ ! -f "$ETC/mosquitto.conf" ]; then
    install -m 644 "$HERE/standalone/mosquitto.conf" "$ETC/mosquitto.conf"
fi

# --- services ------------------------------------------------------------------------------------------------------------
say "systemd units"
units_changed=no
for unit in mosquitto-ui-broker.service mosquitto-ui.service; do
    source_unit="$HERE/standalone/$unit"
    if [ "$unit" = mosquitto-ui.service ]; then
        # the Java of this host
        sed "s#^ExecStart=/usr/bin/java #ExecStart=$java_bin #" "$source_unit" > "$UNITS/$unit.new"
    else
        cp "$source_unit" "$UNITS/$unit.new"
    fi
    if same "$UNITS/$unit.new" "$UNITS/$unit"; then
        rm -f "$UNITS/$unit.new"
    else
        mv "$UNITS/$unit.new" "$UNITS/$unit"
        units_changed=yes
        [ "$unit" = mosquitto-ui-broker.service ] && broker_changed=yes
    fi
done
[ "$units_changed" = yes ] && systemctl daemon-reload
systemctl enable mosquitto-ui-broker.service mosquitto-ui.service >/dev/null 2>&1

if [ "$start" = no ]; then
    say "Installed, not started: systemctl start mosquitto-ui-broker mosquitto-ui"
    exit 0
fi

if ! systemctl is-active --quiet mosquitto-ui-broker; then
    if command -v ss >/dev/null && ss -ltnH 'sport = :1883' | grep -q .; then
        fail "port 1883 is taken, another Mosquitto? (systemctl disable --now mosquitto)"
    fi
    say "Starting the broker"
    systemctl start mosquitto-ui-broker
elif [ "$broker_changed" = yes ]; then
    say "Restarting the broker (new Mosquitto, plugins or agent): clients reconnect"
    systemctl restart mosquitto-ui-broker
fi
say "Starting the UI"
systemctl restart mosquitto-ui

ui_port=$(sed -n 's/^QUARKUS_HTTP_PORT=//p' "$ETC/mosquitto-ui.env" | head -1)
ui_port=${ui_port:-8080}
for _ in $(seq 1 60); do
    curl -fsS -o /dev/null "http://127.0.0.1:$ui_port/q/health/ready" 2>/dev/null && break
    sleep 2
done
if ! curl -fsS -o /dev/null "http://127.0.0.1:$ui_port/q/health/ready" 2>/dev/null; then
    echo "The UI does not answer yet, see: journalctl -u mosquitto-ui -u mosquitto-ui-broker" >&2
    exit 1
fi

say "mosquitto-ui $VERSION runs on http://$(hostname -f 2>/dev/null || hostname):$ui_port"
if [ "$first" = yes ]; then
    echo "  MQTT: port 1883, broker user admin, password in $ETC/broker.env (MOSQUITTO_DYNSEC_PASSWORD)"
    for _ in $(seq 1 10); do [ -f "$UI_DATA/state/initial-password" ] && break; sleep 1; done
    echo "  Login: see $UI_DATA/state/initial-password (goes away after the first login), identity providers on the page Login"
    echo "  Put a reverse proxy with HTTPS in front of the UI, see the handbook."
fi
