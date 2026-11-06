#!/bin/sh
# Runs Mosquitto in its container and carries out what mosquitto-ui asks for, so that the UI needs neither the Docker
# socket nor access to the process of the broker. It takes the place of the command of the container:
#   command: ["sh", "/mosquitto/agent.sh"]
# The listeners of the main file go to control/main-listeners: the UI does not see that file, and --test-config lets a port
# that is taken twice pass, so that only the start of the broker would fail. Only these options, the file may hold passwords.
# Exchange through the directory shared with the UI: the UI puts "<id> <operation> [<argument>]" into control/request,
# the answer in control/response is a line "<id> <exit code>" followed by the output.
#   apply reload|restart [file]   take over conf.d/<file>.new after --test-config, back to the old file on failure;
#                                 mosquitto-ui.conf or mosquitto-ui-bridges.conf, the first by default
#   dynsec-begin           stop the broker and put a copy of the dynamic security file into control/
#   dynsec-commit          take over the changed copy and start the broker, back to the old file on failure
#   dynsec-abort           start the broker without changes; happens by itself after HOLD_SECONDS
#   cert-install reload|restart|store <set>   move control/fullchain.pem and control/privkey.pem into the set; store leaves
#                                 the broker alone, the others also put the set in use
#   cert-activate reload|restart <set> [<previous>]   put a stored set in use, back to the previous one if the broker
#                                 does not start with it
#   cert-delete <set>      delete a set that is not in use
#   cert-remove            delete all certificates from the broker, unless the configuration still needs them
#   backup-export access|- certs|-   copy the dynamic security file and the certificates with their keys into control/export,
#                                 a directory of the UI that it removes again, as only the UI can write a backup
# The shared directory itself belongs to root, so that its subdirectories stay where they are. They belong to the UI (UI_UID,
# the user of its image): conf.d, bridge-ca, token-auth and rules readable for the group mosquitto, as the broker reads them
# again as its own user on a reload (token-auth and rules whenever their file changes); control and backups for the UI alone. The broker cannot write to any of them. This script runs as root
# in directories the UI can write to, so it never follows a link there: it refuses links where it reads, and creates files
# anew where it writes (new_file), instead of writing through whatever is in their place.
set -u

CONF=${MOSQUITTO_CONF:-/mosquitto/config/mosquitto.conf}
DYNSEC=${DYNSEC_FILE:-/mosquitto/data/dynamic-security.json}
CERTS=${CERTS_DIR:-/mosquitto/data/certs}
UI_DIR=${UI_DIR:-/mosquitto/ui}
CONTROL=$UI_DIR/control
MANAGED=$UI_DIR/conf.d/mosquitto-ui.conf
BRIDGES=$UI_DIR/conf.d/mosquitto-ui-bridges.conf
DYNSEC_COPY=$CONTROL/dynamic-security.json
HOLD_SECONDS=120
UI_UID=${UI_UID:-1001}

broker_pid=
held_since=

# Creates a file with what comes on stdin; a link or file in its place is removed first, never written through
new_file() {
    rm -f "$1" && (set -C; cat > "$1")
}

# Whether a path in the shared directory is a link, which this script does not follow
linked() {
    [ -L "$1" ]
}

chown -h root:root "$UI_DIR"
chmod 755 "$UI_DIR"
for dir in conf.d bridge-ca token-auth rules control backups; do
    # a link the UI put here earlier, or anything else in the place of a directory
    linked "$UI_DIR/$dir" && rm -f "$UI_DIR/$dir"
    mkdir -p "$UI_DIR/$dir"
done
# What the broker reads: the group mosquitto, which new files inherit (setgid)
chown -h "$UI_UID:mosquitto" "$UI_DIR/conf.d" "$UI_DIR/bridge-ca" "$UI_DIR/token-auth" "$UI_DIR/rules"
chmod 2750 "$UI_DIR/conf.d" "$UI_DIR/bridge-ca" "$UI_DIR/token-auth" "$UI_DIR/rules"
for file in "$MANAGED" "$BRIDGES"; do
    [ -e "$file" ] || [ -L "$file" ] || : | new_file "$file"
done
for file in "$UI_DIR"/conf.d/* "$UI_DIR"/bridge-ca/* "$UI_DIR"/token-auth/* "$UI_DIR"/rules/*; do
    if [ -f "$file" ] && ! linked "$file"; then
        chown -h "$UI_UID:mosquitto" "$file"
        chmod 640 "$file"
    fi
done
# Requests, answers, keys on their way, old configurations with the passwords of bridges: the UI alone
chown -h "$UI_UID" "$CONTROL" "$UI_DIR/backups"
chmod 700 "$CONTROL" "$UI_DIR/backups"

grep -E '^[[:space:]]*(listener|port|protocol|certfile|keyfile|cafile|max_connections)[[:space:]]' "$CONF" | new_file "$CONTROL/main-listeners"

start_broker() {
    mosquitto -c "$CONF" &
    broker_pid=$!
}

stop_broker() {
    if [ -n "$broker_pid" ]; then
        kill -TERM "$broker_pid" 2>/dev/null
        wait "$broker_pid" 2>/dev/null
        broker_pid=
    fi
}

broker_running() {
    [ -n "$broker_pid" ] && [ -r "/proc/$broker_pid/stat" ] && [ "$(cut -d' ' -f3 "/proc/$broker_pid/stat")" != Z ]
}

# A broker that cannot start, e.g. with a port in use, ends within this time
start_and_check() {
    start_broker
    sleep 2
    broker_running
}

respond() {
    { echo "$1 $2"; printf '%s\n' "$3"; } | new_file "$CONTROL/response.tmp"
    mv -f "$CONTROL/response.tmp" "$CONTROL/response"
}

restore_config() {
    mv -f "$target.bak" "$target"
}

apply_config() {
    id=$1
    mode=$2
    case "${3:-mosquitto-ui.conf}" in
        mosquitto-ui.conf) target=$MANAGED ;;
        mosquitto-ui-bridges.conf) target=$BRIDGES ;;
        *) respond "$id" 1 "Unknown file $3"; return ;;
    esac
    if linked "$target" || linked "$target.new" || [ ! -f "$target.new" ]; then
        respond "$id" 1 "No file to take over"
        return
    fi
    new_file "$target.bak" < "$target"
    mv -f "$target.new" "$target"
    if ! output=$(mosquitto --test-config -c "$CONF" 2>&1); then
        restore_config
        respond "$id" 1 "$output"
        return
    fi
    if [ "$mode" = reload ]; then
        kill -HUP "$broker_pid"
        respond "$id" 0 "$output"
        return
    fi
    stop_broker
    if start_and_check; then
        respond "$id" 0 "$output"
    else
        restore_config
        start_broker
        respond "$id" 1 "The broker did not start with this configuration, see its log"
    fi
}

dynsec_begin() {
    if [ ! -e "$DYNSEC" ]; then
        respond "$1" 1 "$DYNSEC does not exist"
        return
    fi
    stop_broker
    new_file "$DYNSEC_COPY" < "$DYNSEC"
    # the UI changes the copy
    chown -h "$UI_UID" "$DYNSEC_COPY"
    held_since=$(date +%s)
    respond "$1" 0 ""
}

dynsec_finish() {
    held_since=
    rm -f "$DYNSEC_COPY"
    [ -n "$broker_pid" ] || start_broker
}

dynsec_commit() {
    if linked "$DYNSEC_COPY" || [ ! -f "$DYNSEC_COPY" ]; then
        dynsec_finish
        respond "$1" 1 "No copy to take over"
        return
    fi
    cp -p "$DYNSEC" "$DYNSEC.pre-import"
    cp "$DYNSEC_COPY" "$DYNSEC"
    chown --reference="$DYNSEC.pre-import" "$DYNSEC" 2>/dev/null || chown mosquitto:mosquitto "$DYNSEC"
    chmod 600 "$DYNSEC"
    held_since=
    rm -f "$DYNSEC_COPY"
    if start_and_check; then
        respond "$1" 0 ""
    else
        cp -p "$DYNSEC.pre-import" "$DYNSEC"
        start_broker
        respond "$1" 1 "The broker did not start with the imported data, it runs with the previous data again"
    fi
}

# As the owner of the directory, whoever that is, and for nobody else
backup_export() {
    export_dir=$CONTROL/export
    if linked "$export_dir" || [ ! -d "$export_dir" ]; then
        respond "$1" 1 "control/export does not exist"
        return
    fi
    owner=$(stat -c %u:%g "$export_dir")
    if [ "$2" = access ]; then
        new_file "$export_dir/dynamic-security.json" < "$DYNSEC"
        chown -h "$owner" "$export_dir/dynamic-security.json"
    fi
    if [ "$3" = certs ] && [ -d "$CERTS" ]; then
        rm -rf "$export_dir/certs"
        cp -R "$CERTS" "$export_dir/certs"
        chown -hR "$owner" "$export_dir/certs"
    fi
    respond "$1" 0 ""
}

valid_set() {
    case "$1" in
        ""|*[!a-z0-9]*) return 1 ;;
    esac
}

# The key ends up readable only by the broker, which reads it again as its own user on a reload
own_certificate_file() {
    chown mosquitto:mosquitto "$1"
    chmod 600 "$1"
}

# The configuration only knows these two paths, whichever set is in use. Certificate and key have to match, there is only a
# moment between the two
publish_set() {
    for file in fullchain.pem privkey.pem; do
        cp "$CERTS/sets/$1/$file" "$CERTS/$file.new"
        own_certificate_file "$CERTS/$file.new"
    done
    mv "$CERTS/fullchain.pem.new" "$CERTS/fullchain.pem"
    mv "$CERTS/privkey.pem.new" "$CERTS/privkey.pem"
}

cert_install() {
    id=$1
    mode=$2
    set=$3
    if ! valid_set "$set"; then
        respond "$id" 1 "Invalid set $set"
        return
    fi
    mkdir -p "$CERTS/sets/$set"
    chown mosquitto:mosquitto "$CERTS" "$CERTS/sets" "$CERTS/sets/$set"
    for file in fullchain.pem privkey.pem; do
        if linked "$CONTROL/$file" || [ ! -f "$CONTROL/$file" ]; then
            respond "$id" 1 "No $file to install"
            return
        fi
    done
    for file in fullchain.pem privkey.pem; do
        new_file "$CERTS/sets/$set/$file.new" < "$CONTROL/$file"
        own_certificate_file "$CERTS/sets/$set/$file.new"
        rm -f "$CONTROL/$file"
    done
    mv "$CERTS/sets/$set/fullchain.pem.new" "$CERTS/sets/$set/fullchain.pem"
    mv "$CERTS/sets/$set/privkey.pem.new" "$CERTS/sets/$set/privkey.pem"
    if [ "$mode" = store ]; then
        respond "$id" 0 ""
        return
    fi
    publish_set "$set"
    if [ "$mode" = reload ]; then
        kill -HUP "$broker_pid"
        respond "$id" 0 ""
        return
    fi
    stop_broker
    if start_and_check; then
        respond "$id" 0 ""
    else
        start_broker
        respond "$id" 1 "The broker did not start with the new certificate, see its log"
    fi
}

cert_activate() {
    id=$1
    mode=$2
    set=$3
    previous=$4
    if ! valid_set "$set" || { [ -n "$previous" ] && ! valid_set "$previous"; }; then
        respond "$id" 1 "Invalid set"
        return
    fi
    if [ ! -e "$CERTS/sets/$set/privkey.pem" ]; then
        respond "$id" 1 "The broker has no key for this set"
        return
    fi
    # A certificate from before there were sets exists only here, and the key cannot be fetched again
    if [ -n "$previous" ] && [ ! -e "$CERTS/sets/$previous/privkey.pem" ] && [ -e "$CERTS/privkey.pem" ]; then
        mkdir -p "$CERTS/sets/$previous"
        chown mosquitto:mosquitto "$CERTS/sets" "$CERTS/sets/$previous"
        for file in fullchain.pem privkey.pem; do
            cp -p "$CERTS/$file" "$CERTS/sets/$previous/$file"
        done
    fi
    publish_set "$set"
    if [ "$mode" = reload ]; then
        kill -HUP "$broker_pid"
        respond "$id" 0 ""
        return
    fi
    stop_broker
    if start_and_check; then
        respond "$id" 0 ""
        return
    fi
    if [ -n "$previous" ] && [ -e "$CERTS/sets/$previous/privkey.pem" ]; then
        publish_set "$previous"
    fi
    start_broker
    respond "$id" 1 "The broker did not start with this certificate, it uses the previous one again"
}

cert_delete() {
    if ! valid_set "$2"; then
        respond "$1" 1 "Invalid set $2"
        return
    fi
    rm -rf "$CERTS/sets/$2"
    respond "$1" 0 ""
}

# --test-config does not notice a missing file, only the next start of the broker would
cert_remove() {
    if grep -qF "$CERTS/" "$CONF" "$MANAGED" "$BRIDGES"; then
        respond "$1" 1 "The configuration still uses the certificate, remove it there first"
        return
    fi
    rm -rf "$CERTS/sets"
    rm -f "$CERTS/fullchain.pem" "$CERTS/privkey.pem"
    respond "$1" 0 ""
}

handle_request() {
    if linked "$CONTROL/request"; then
        rm -f "$CONTROL/request"
        return
    fi
    read -r id operation argument file extra < "$CONTROL/request" || true
    rm -f "$CONTROL/request"
    # the id goes back in the answer, nothing but the UUID of the UI
    case "$id" in
        ""|*[!0-9a-f-]*) return ;;
    esac
    case "$operation" in
        apply) apply_config "$id" "$argument" "$file" ;;
        cert-install) cert_install "$id" "$argument" "$file" ;;
        cert-activate) cert_activate "$id" "$argument" "$file" "$extra" ;;
        cert-delete) cert_delete "$id" "$argument" ;;
        cert-remove) cert_remove "$id" ;;
        backup-export) backup_export "$id" "$argument" "$file" ;;
        dynsec-begin) dynsec_begin "$id" ;;
        dynsec-commit) dynsec_commit "$id" ;;
        dynsec-abort) dynsec_finish; respond "$id" 0 "" ;;
        *) respond "$id" 1 "Unknown operation $operation" ;;
    esac
}

trap 'stop_broker; exit 0' TERM INT

start_broker
while :; do
    linked "$CONTROL/alive" && rm -f "$CONTROL/alive"
    touch "$CONTROL/alive"
    if [ -z "$held_since" ] && ! broker_running; then
        # let Docker restart the container, as without this script
        wait "$broker_pid"
        exit $?
    fi
    if [ -n "$held_since" ] && [ $(($(date +%s) - held_since)) -gt "$HOLD_SECONDS" ]; then
        dynsec_finish
    fi
    if [ -e "$CONTROL/request" ]; then
        handle_request
    fi
    # in the background, so that the trap runs right away
    sleep 0.3 &
    wait $!
done
