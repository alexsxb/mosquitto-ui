# Installing with the wizard (Docker)

`deploy/install.sh` sets up mosquitto-ui with its broker on a Linux host with Docker, guided by a wizard in the console
(whiptail, else dialog). It speaks English, German and French. Without Docker, see [standalone.md](standalone.md).

## Requirements

- Linux with systemd on amd64 or arm64: Debian, Ubuntu, Raspberry Pi OS, Fedora, CentOS/RHEL and their kin
- root (`sudo`), `curl` and access to github.com and ghcr.io
- Docker is not a requirement: when it is missing, the wizard offers to install it

## Start

```shell
curl -fsSL https://raw.githubusercontent.com/alexsxb/mosquitto-ui/main/deploy/install.sh -o install.sh
sudo bash install.sh
```

Or in one go: `curl -fsSL …/deploy/install.sh | sudo bash`. The wizard then reads its input from the terminal.

| Option | Meaning |
|---|---|
| `--lang en\|de\|fr` | language, without asking; otherwise the wizard asks and proposes the one of the system (`LANG`) |
| `--dir <directory>` | the directory it proposes, by default `/opt/mosquitto-ui` |
| `--repo <owner/name>` | GitHub repository of `compose.yaml` and the releases, by default `alexsxb/mosquitto-ui` |

Use the arrow keys, Tab and Enter. Esc or *Cancel* asks whether to stop the installation.

## Steps

1. **Language.**
2. **Docker.** When Docker is missing, the wizard asks and then installs it with the official script
   <https://get.docker.com>. That script adds the package repository of Docker and installs Docker Engine and the Compose
   plugin. Afterwards the wizard enables and starts the service `docker`. When only the Compose plugin is missing, it comes
   from the packages of the system (`docker-compose-plugin` or `docker-compose-v2`).
3. **Directory** for `compose.yaml` and `.env`. The data does not live there but in the Docker volumes of the project
   `mosquitto-ui`.
4. **Version:** the latest release (recommended), `latest` (follows every release at the next `docker compose pull`) or a
   version you type.
5. **Ports** for the web UI (8080), MQTT (1883) and MQTT over TLS (8883). The wizard warns about a port that is taken.
6. **Broker password** of the broker user `admin`, which the UI logs in to the broker with. It is generated or typed twice,
   with at least 12 characters and without quotes or spaces. It takes effect at the first start of the broker; later changes
   in `.env` do not reach the broker.
7. **UI password** of the UI user `admin` for the local login, with the same rules. More users and identity providers are
   added later on the page *Login*.
8. **Secret key** (`MOSQUITTO_UI_SECRET_KEY`): a new one, or the key of an earlier installation whose backup you want to
   restore.
9. **Reverse proxy:** the addresses and networks whose `X-Forwarded-*` headers the UI trusts. The default
   `127.0.0.1,172.16.0.0/12` covers a proxy on the same host and Docker networks.
10. **Summary.** The wizard changes nothing in the chosen directory before *Yes*.

Then the wizard downloads `deploy/compose.yaml` of the chosen version from GitHub and writes `.env` with mode 600. It pulls
the images, starts both containers and waits until the UI answers on `/q/health/ready`; a progress bar shows the last line
of the output meanwhile. Afterwards it takes the UI password out of `.env` again, because it is only read at the first start.
At the end it shows the address, both logins and the key, in the window and once more in the terminal.

**Write down the passwords and keep the key apart from the host:** it decrypts the backups and cannot change later.

## After the installation

- Put a reverse proxy with HTTPS in front of the UI. The UI speaks plain HTTP.
- Get a certificate for MQTT over TLS on the page *Certificate*. Port 8883 answers only after that.
- Docker publishes the ports on all addresses of the host and bypasses ufw and firewalld rules on the way. Block ports
  that must not be reachable in a firewall in front of the host.
- The settings are in `<directory>/.env`, see `deploy/env.example`. After a change: `cd <directory> && docker compose up -d`.

## Update

The same command on a host with an installation offers *update*. The wizard asks for the version and downloads its
`compose.yaml`; the old one stays as `compose.yaml.bak` if it changes. Then it sets `MOSQUITTO_UI_VERSION` in `.env`, pulls the
images and restarts the containers. The other settings stay. A new broker image restarts the broker, which disconnects its
clients for a moment.

By hand it is the same: set `MOSQUITTO_UI_VERSION` in `.env`, then `docker compose pull && docker compose up -d`.

## When something goes wrong

- A step that fails shows its output in a window. Everything is in `/var/log/mosquitto-ui-install.log`.
- When the UI does not answer after three minutes, the wizard adds the state and logs of the containers to that log. To
  look yourself: `cd <directory> && docker compose ps && docker compose logs`.
- A port that is taken (another Mosquitto on 1883, say) keeps the container from starting.

## Removing

```shell
cd /opt/mosquitto-ui
docker compose down        # removes the containers, the data stays in the volumes
docker compose down -v     # also all data: users, certificate, settings, local backups
```

Docker itself stays installed.
