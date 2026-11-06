# Without Docker: mosquitto-ui as systemd services

Applies from mosquitto-ui 0.25.0. Tested on Debian 12 and 13, Rocky Linux 9; Ubuntu 22.04/24.04 and Fedora work the same way
(apt or dnf). Linux with systemd only, amd64 or arm64.

## What gets installed

| Service | What | Runs as |
|---|---|---|
| `mosquitto-ui-broker` | Mosquitto 2.1.2 with the agent (`agent.sh`), which checks and applies configurations, installs certificates and restarts the broker | agent as root, Mosquitto as `mosquitto` |
| `mosquitto-ui` | the web UI (Java 21) | `mosquitto-ui` |

Most distributions ship Mosquitto 2.0; the UI needs 2.1 (plugins, `persist_sqlite`). So the script builds
Mosquitto 2.1.2 from source (same checksum as in the Docker image) into `/usr/local`, together with the two plugins of
mosquitto-ui. A Mosquitto package of the distribution stays untouched, but must not run on port 1883
(`systemctl disable --now mosquitto`).

| Path | Content |
|---|---|
| `/opt/mosquitto-ui/releases/<Version>`, `/opt/mosquitto-ui/current` | UI and agent; the previous version stays in place |
| `/usr/local/sbin/mosquitto`, `/usr/local/lib/mosquitto-ui/` | Mosquitto, the plugins |
| `/etc/mosquitto-ui/mosquitto.conf` | configuration of the broker, like `docker/mosquitto/mosquitto.conf` |
| `/etc/mosquitto-ui/mosquitto-ui.env` | settings of the UI (environment variables as in the README) |
| `/etc/mosquitto-ui/broker.env` | password of the broker user `admin` for the first start |
| `/var/lib/mosquitto` | data of the broker: dynamic security, sessions, retained, certificates |
| `/var/lib/mosquitto-ui/state` | data of the UI, like the volume `state` |
| `/var/lib/mosquitto-ui/shared` | the directory that UI and agent share, like the volume `shared` |
| `/var/lib/mosquitto-ui/backups` | local backup targets |

## Installing

Every release on GitHub has the tarball (`mosquitto-ui-<Version>.tar.gz` with `.sha256`), see https://github.com/alexsxb/mosquitto-ui/releases. The first line gets the number of the newest one:

```
V=$(curl -fsS https://api.github.com/repos/alexsxb/mosquitto-ui/releases/latest | sed -n 's/.*"tag_name": *"v\([^"]*\)".*/\1/p')
curl -fLO https://github.com/alexsxb/mosquitto-ui/releases/download/v$V/mosquitto-ui-$V.tar.gz
curl -fLO https://github.com/alexsxb/mosquitto-ui/releases/download/v$V/mosquitto-ui-$V.tar.gz.sha256
sha256sum -c mosquitto-ui-$V.tar.gz.sha256
tar xzf mosquitto-ui-$V.tar.gz
cd mosquitto-ui-$V
sudo ./install.sh
```

The script installs the compiler, libraries and Java 21 (on Debian 12 Temurin from Eclipse Adoptium), creates the users
`mosquitto` and `mosquitto-ui`, builds Mosquitto and the plugins (one to two minutes the first time), writes the
settings with a random broker password and secret key, and starts both services. At the end it says where the UI runs.

Options: `--port <Port>` (UI, default 8080), `--no-start`, `--skip-packages` (everything needed is already there).

### First login

First with the [local login](anmeldung.md): user `admin`, password in

```
sudo cat /var/lib/mosquitto-ui/state/initial-password
```

The file disappears after the first login. After that, you set up identity providers (Keycloak, authentik, Google, GitHub …)
on the page **Login**.

**Important:** keep `MOSQUITTO_UI_SECRET_KEY` from `mosquitto-ui.env` separate from the server. Without it, the
encrypted settings and backups can no longer be read.

## HTTPS in front

The UI only speaks HTTP. Put a reverse proxy with TLS in front of it, for example Caddy:

```
mqtt-ui.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

or nginx (server-sent events for topics and logs need `proxy_buffering off`):

```
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_read_timeout 1h;
}
```

Then uncomment the three lines `QUARKUS_HTTP_PROXY_*` in `mosquitto-ui.env` and restart the UI. To make the UI reachable only
through the proxy: `QUARKUS_HTTP_HOST=127.0.0.1`.

## Operation

```
systemctl status mosquitto-ui-broker mosquitto-ui
journalctl -u mosquitto-ui-broker -f        # log of the broker (also on the page Logs)
journalctl -u mosquitto-ui -f               # log of the UI
sudo systemctl restart mosquitto-ui         # after a change in mosquitto-ui.env
```

Ports for MQTT (1883, 8883 once there is a certificate, further listeners from the UI) open directly on the host, a
firewall has to allow them. You create the listeners on the page **Configuration** as before.

## Update

Unpack the new tarball and do the same again:

```
tar xzf mosquitto-ui-0.25.1.tar.gz && cd mosquitto-ui-0.25.1 && sudo ./install.sh
```

The UI restarts. The broker only restarts if Mosquitto, the plugins, the agent or its unit have changed (otherwise all
clients stay connected). The script never overwrites settings in `/etc/mosquitto-ui`. To go back to the previous version:
install its tarball the same way.

## Backup

The page **Backups** backs up as with Docker. In addition, `/etc/mosquitto-ui` (secret key!) and, if needed,
`/var/lib/mosquitto` belong in the backup of the server.

## Removing

```
sudo systemctl disable --now mosquitto-ui mosquitto-ui-broker
sudo rm /etc/systemd/system/mosquitto-ui.service /etc/systemd/system/mosquitto-ui-broker.service
sudo systemctl daemon-reload
```

After that, delete `/opt/mosquitto-ui`, `/etc/mosquitto-ui`, `/var/lib/mosquitto-ui`, `/var/lib/mosquitto` and
`/usr/local/lib/mosquitto-ui` if you want. That deletes all data.

## Moving from the Docker setup

The simplest way is a backup: in the Docker setup, create a backup of all parts, install on the new server,
and there, with the same `MOSQUITTO_UI_SECRET_KEY` (enter it in `mosquitto-ui.env` before the UI starts for the first time, so install with
`--no-start`), restore the backup on the page **Backups**.
