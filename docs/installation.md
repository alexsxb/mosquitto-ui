# Installation mit dem Assistenten (Docker)

`deploy/install.sh` richtet mosquitto-ui samt Broker auf einem Linux-Host mit Docker ein, geführt von einem Assistenten in
der Konsole (whiptail, sonst dialog). Er spricht Deutsch, Englisch und Französisch. Ohne Docker siehe
[standalone.md](standalone.md).

## Voraussetzungen

- Linux mit systemd auf amd64 oder arm64: Debian, Ubuntu, Raspberry Pi OS, Fedora, CentOS/RHEL und Verwandte
- root-Rechte (`sudo`), `curl` und Zugang zu github.com und ghcr.io
- Docker ist keine Voraussetzung: Fehlt es, bietet der Assistent an, es zu installieren

## Start

```shell
curl -fsSL https://raw.githubusercontent.com/alexsxb/mosquitto-ui/main/deploy/install.sh -o install.sh
sudo bash install.sh
```

Oder in einem Zug: `curl -fsSL …/deploy/install.sh | sudo bash`. Der Assistent liest seine Eingaben dann vom Terminal.

| Option | Bedeutung |
|---|---|
| `--lang de\|en\|fr` | Sprache, ohne Rückfrage; sonst fragt der Assistent und schlägt die Sprache des Systems vor (`LANG`) |
| `--dir <Verzeichnis>` | Vorschlag für das Verzeichnis, Standard `/opt/mosquitto-ui` |
| `--repo <Besitzer/Name>` | GitHub-Repository für `compose.yaml` und Releases, Standard `alexsxb/mosquitto-ui` |

Bedienung mit Pfeiltasten, Tab und Enter. Esc oder *Abbrechen* fragt, ob die Installation abgebrochen werden soll.

## Ablauf

1. **Sprache.**
2. **Docker.** Fehlt Docker, installiert der Assistent es nach Rückfrage mit dem offiziellen Skript
   <https://get.docker.com>. Das Skript richtet die Paketquelle von Docker ein und installiert Docker Engine und das
   Compose-Plugin. Danach startet der Assistent den Dienst `docker` und aktiviert ihn. Fehlt nur das Compose-Plugin, kommt es
   aus den Paketen des Systems (`docker-compose-plugin` bzw. `docker-compose-v2`).
3. **Verzeichnis** für `compose.yaml` und `.env`. Die Daten liegen nicht dort, sondern in den Docker-Volumes des Projekts
   `mosquitto-ui`.
4. **Version:** das neueste Release (empfohlen), `latest` (folgt jedem Release beim nächsten `docker compose pull`) oder
   eine eingegebene Version.
5. **Ports** für Weboberfläche (8080), MQTT (1883) und MQTT über TLS (8883). Ein belegter Port wird gemeldet.
6. **Broker-Passwort** des Broker-Benutzers `admin`, mit dem sich die UI am Broker anmeldet. Es wird erzeugt oder zweimal
   eingegeben, mit mindestens 12 Zeichen, ohne Hochkomma und Leerzeichen. Es gilt ab dem ersten Start des Brokers; spätere
   Änderungen in `.env` erreichen den Broker nicht.
7. **UI-Passwort** des UI-Benutzers `admin` für den lokalen Login, mit denselben Regeln. Weitere Benutzer und Identity Provider
   kommen später auf der Seite *Anmeldung* dazu.
8. **Geheimer Schlüssel** (`MOSQUITTO_UI_SECRET_KEY`): neu erzeugt, oder der einer früheren Installation, deren Backup
   wiederhergestellt werden soll.
9. **Reverse Proxy:** die Adressen und Netze, deren `X-Forwarded-*`-Header die UI vertraut. Die Vorgabe
   `127.0.0.1,172.16.0.0/12` deckt einen Proxy auf demselben Host und Docker-Netze ab.
10. **Zusammenfassung.** Erst nach *Ja* ändert der Assistent etwas im gewählten Verzeichnis.

Danach lädt der Assistent `deploy/compose.yaml` der gewählten Version von GitHub und schreibt `.env` mit den Rechten 600.
Er lädt die Images, startet beide Container und wartet, bis die UI unter `/q/health/ready` antwortet; ein Fortschrittsbalken
zeigt dabei die letzte Zeile der Ausgabe. Das UI-Passwort nimmt er danach wieder aus `.env`, denn es wird nur beim ersten
Start gelesen. Zum Schluss zeigt er Adresse, beide Logins und den Schlüssel, im Fenster und noch einmal im Terminal.

**Passwörter notieren und den Schlüssel getrennt vom Host aufbewahren:** Er entschlüsselt die Backups und lässt sich später
nicht ändern.

## Nach der Installation

- Einen Reverse Proxy mit HTTPS vor die UI stellen. Die UI spricht nur HTTP.
- Ein Zertifikat für MQTT über TLS auf der Seite *Zertifikat* holen. Port 8883 antwortet erst danach.
- Docker veröffentlicht die Ports auf allen Adressen des Hosts und umgeht dabei Regeln von ufw und firewalld. Ports, die
  nicht erreichbar sein sollen, sperrt eine Firewall vor dem Host.
- Einstellungen stehen in `<Verzeichnis>/.env`, siehe `deploy/env.example`. Nach einer Änderung:
  `cd <Verzeichnis> && docker compose up -d`.

## Update

Derselbe Aufruf auf einem Host mit Installation bietet *aktualisieren* an. Der Assistent fragt die Version ab und lädt deren
`compose.yaml`; die alte bleibt als `compose.yaml.bak`, falls sie sich ändert. Dann setzt er `MOSQUITTO_UI_VERSION` in
`.env`, lädt die Images und startet die Container neu. Die übrigen Einstellungen bleiben. Ein neues Broker-Image startet
den Broker neu, der seine Clients dabei kurz trennt.

Von Hand geht dasselbe so: `MOSQUITTO_UI_VERSION` in `.env` setzen, dann `docker compose pull && docker compose up -d`.

## Wenn etwas schiefgeht

- Ein gescheiterter Schritt zeigt seine Ausgabe im Fenster. Alles steht in `/var/log/mosquitto-ui-install.log`.
- Antwortet die UI nach drei Minuten nicht, hängt der Assistent Status und Logs der Container an dieses Log an. Selbst
  nachsehen: `cd <Verzeichnis> && docker compose ps && docker compose logs`.
- Ein belegter Port (etwa ein anderer Mosquitto auf 1883) verhindert den Start des Containers.

## Entfernen

```shell
cd /opt/mosquitto-ui
docker compose down        # Container weg, Daten bleiben in den Volumes
docker compose down -v     # auch alle Daten: Benutzer, Zertifikat, Einstellungen, lokale Backups
```

Docker selbst bleibt installiert.
