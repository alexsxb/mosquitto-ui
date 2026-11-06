# Ohne Docker: mosquitto-ui als systemd-Dienste

Gilt ab mosquitto-ui 0.25.0. Getestet auf Debian 12 und 13, Rocky Linux 9; Ubuntu 22.04/24.04 und Fedora gehen denselben Weg
(apt bzw. dnf). Nur Linux mit systemd, amd64 oder arm64.

## Was installiert wird

| Dienst | Was | Läuft als |
|---|---|---|
| `mosquitto-ui-broker` | Mosquitto 2.1.2 mit dem Agenten (`agent.sh`), der Konfigurationen prüft und übernimmt, Zertifikate einspielt und den Broker neu startet | Agent als root, Mosquitto als `mosquitto` |
| `mosquitto-ui` | die Web-UI (Java 21) | `mosquitto-ui` |

Die Distributionen bringen meist Mosquitto 2.0 mit; die UI braucht 2.1 (Plugins, `persist_sqlite`). Das Skript baut deshalb
Mosquitto 2.1.2 aus dem Quelltext (Prüfsumme wie im Docker-Image) nach `/usr/local`, daneben die beiden Plugins von
mosquitto-ui. Ein Mosquitto-Paket der Distribution bleibt unberührt, darf aber nicht auf Port 1883 laufen
(`systemctl disable --now mosquitto`).

| Pfad | Inhalt |
|---|---|
| `/opt/mosquitto-ui/releases/<Version>`, `/opt/mosquitto-ui/current` | UI und Agent; die vorige Version bleibt liegen |
| `/usr/local/sbin/mosquitto`, `/usr/local/lib/mosquitto-ui/` | Mosquitto, die Plugins |
| `/etc/mosquitto-ui/mosquitto.conf` | Konfiguration des Brokers, wie `docker/mosquitto/mosquitto.conf` |
| `/etc/mosquitto-ui/mosquitto-ui.env` | Einstellungen der UI (Umgebungsvariablen wie im README) |
| `/etc/mosquitto-ui/broker.env` | Passwort des Broker-Benutzers `admin` für den ersten Start |
| `/var/lib/mosquitto` | Daten des Brokers: Dynamic Security, Sitzungen, Retained, Zertifikate |
| `/var/lib/mosquitto-ui/state` | Daten der UI, wie das Volume `state` |
| `/var/lib/mosquitto-ui/shared` | das Verzeichnis, das UI und Agent teilen, wie das Volume `shared` |
| `/var/lib/mosquitto-ui/backups` | lokale Backup-Ziele |

## Installieren

Den Tarball gibt es an jedem Release auf GitHub (`mosquitto-ui-<Version>.tar.gz` mit `.sha256`), unter https://github.com/alexsxb/mosquitto-ui/releases. Die erste Zeile holt die Nummer des neuesten:

```
V=$(curl -fsS https://api.github.com/repos/alexsxb/mosquitto-ui/releases/latest | sed -n 's/.*"tag_name": *"v\([^"]*\)".*/\1/p')
curl -fLO https://github.com/alexsxb/mosquitto-ui/releases/download/v$V/mosquitto-ui-$V.tar.gz
curl -fLO https://github.com/alexsxb/mosquitto-ui/releases/download/v$V/mosquitto-ui-$V.tar.gz.sha256
sha256sum -c mosquitto-ui-$V.tar.gz.sha256
tar xzf mosquitto-ui-$V.tar.gz
cd mosquitto-ui-$V
sudo ./install.sh
```

Das Skript installiert Compiler, Bibliotheken und Java 21 (auf Debian 12 Temurin von Eclipse Adoptium), legt die Benutzer
`mosquitto` und `mosquitto-ui` an, baut Mosquitto und die Plugins (beim ersten Mal ein bis zwei Minuten), schreibt die
Einstellungen mit zufälligem Broker-Passwort und Secret Key und startet beide Dienste. Am Ende steht, wo die UI läuft.

Optionen: `--port <Port>` (UI, Standard 8080), `--no-start`, `--skip-packages` (alles Nötige ist schon da).

### Erste Anmeldung

Zuerst mit dem [lokalen Login](anmeldung.md): Benutzer `admin`, Passwort in

```
sudo cat /var/lib/mosquitto-ui/state/initial-password
```

Die Datei verschwindet nach der ersten Anmeldung. Identity Provider (Keycloak, authentik, Google, GitHub …) richtet danach
die Seite **Anmeldung** ein.

**Wichtig:** `MOSQUITTO_UI_SECRET_KEY` aus `mosquitto-ui.env` getrennt vom Server aufheben. Ohne ihn lassen sich die
verschlüsselten Einstellungen und Backups nicht mehr lesen.

## HTTPS davor

Die UI spricht nur HTTP. Davor gehört ein Reverse Proxy mit TLS, z. B. Caddy:

```
mqtt-ui.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

oder nginx (Server-Sent Events für Topics und Logs brauchen `proxy_buffering off`):

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

Dazu in `mosquitto-ui.env` die drei Zeilen `QUARKUS_HTTP_PROXY_*` einkommentieren und die UI neu starten. Damit die UI nur über
den Proxy erreichbar ist: `QUARKUS_HTTP_HOST=127.0.0.1`.

## Betrieb

```
systemctl status mosquitto-ui-broker mosquitto-ui
journalctl -u mosquitto-ui-broker -f        # Log des Brokers (auch auf der Seite Logs)
journalctl -u mosquitto-ui -f               # Log der UI
sudo systemctl restart mosquitto-ui         # nach einer Änderung in mosquitto-ui.env
```

Ports für MQTT (1883, 8883 sobald ein Zertifikat da ist, weitere Listener aus der UI) öffnen sich direkt auf dem Host, eine
Firewall muss sie freigeben. Die Listener legst du wie bisher auf der Seite **Konfiguration** an.

## Update

Neuen Tarball auspacken und dasselbe noch einmal:

```
tar xzf mosquitto-ui-0.25.1.tar.gz && cd mosquitto-ui-0.25.1 && sudo ./install.sh
```

Die UI startet neu. Der Broker nur, wenn sich Mosquitto, die Plugins, der Agent oder seine Unit geändert haben (sonst bleiben
alle Clients verbunden). Einstellungen in `/etc/mosquitto-ui` überschreibt das Skript nie. Zurück auf die vorige Version:
deren Tarball genauso installieren.

## Backup

Die Seite **Backups** sichert wie mit Docker. Zusätzlich gehören `/etc/mosquitto-ui` (Secret Key!) und bei Bedarf
`/var/lib/mosquitto` in die Sicherung des Servers.

## Entfernen

```
sudo systemctl disable --now mosquitto-ui mosquitto-ui-broker
sudo rm /etc/systemd/system/mosquitto-ui.service /etc/systemd/system/mosquitto-ui-broker.service
sudo systemctl daemon-reload
```

Danach bei Bedarf `/opt/mosquitto-ui`, `/etc/mosquitto-ui`, `/var/lib/mosquitto-ui`, `/var/lib/mosquitto` und
`/usr/local/lib/mosquitto-ui` löschen. Das löscht alle Daten.

## Vom Docker-Setup umziehen

Am einfachsten über ein Backup: im Docker-Setup ein Backup aller Abschnitte erstellen, auf dem neuen Server installieren,
dort mit demselben `MOSQUITTO_UI_SECRET_KEY` (in `mosquitto-ui.env` eintragen, bevor die UI zum ersten Mal startet, also mit
`--no-start` installieren) das Backup auf der Seite **Backups** wiederherstellen.
