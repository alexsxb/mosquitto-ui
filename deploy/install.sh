#!/usr/bin/env bash
# Installs mosquitto-ui with its broker on a Linux host with Docker, guided by a wizard in the console (English, German or
# French).
#
#   curl -fsSL https://raw.githubusercontent.com/alexsxb/mosquitto-ui/main/deploy/install.sh -o install.sh
#   sudo bash install.sh
#
# or in one go: curl -fsSL .../deploy/install.sh | sudo bash
#
# Without Docker, it offers to install it with the script of Docker (https://get.docker.com). Then it asks for the directory,
# the version, the ports and the passwords, writes compose.yaml and .env there and starts both containers. Run again on a
# host that has an installation, it updates to another version and keeps the settings.
#
# Options:
#   --dir <directory>    where compose.yaml and .env go (/opt/mosquitto-ui), the wizard proposes it
#   --lang <en|de|fr>    language of the wizard; without it, the wizard asks and proposes the one of the system
#   --repo <owner/name>  the GitHub repository of compose.yaml and the release (alexsxb/mosquitto-ui)
#   -h, --help           this text
#
# Debian, Ubuntu, Raspberry Pi OS, Fedora, CentOS/RHEL and their kin on amd64 or arm64, with systemd.

# Everything in functions: piped into bash, the script is read up to the call of main before main takes the terminal as input
set -euo pipefail

REPO=alexsxb/mosquitto-ui
DIR=/opt/mosquitto-ui
LOG=/var/log/mosquitto-ui-install.log
UI_LANG=
LANG_ASKED=no
TUI=
WORK=
# the directory of this script, empty when piped into bash
HERE=
case "$0" in */install.sh|install.sh) HERE=$(cd "$(dirname "$0")" && pwd) ;; esac

usage() { sed -n '2,21p' "${HERE:-.}/install.sh" | sed 's/^# \{0,1\}//'; }

# --- texts -------------------------------------------------------------------------------------------------------------
# t <key> [values for %s]: the text in the language of the wizard; printf also turns \n into line breaks
declare -A MSG
m() { MSG[$1]=$2; }
t() {
    local format=${MSG[$1]:-$1}
    shift
    # shellcheck disable=SC2059 # the texts are the formats
    printf "$format" "$@"
}

lang_en() {
    m TITLE "mosquitto-ui installer"
    m ERROR "Error: %s"
    m BTN_OK "Ok"; m BTN_CANCEL "Cancel"; m BTN_YES "Yes"; m BTN_NO "No"
    m ERR_ROOT "run as root: sudo bash %s"
    m ERR_ARCH "the images exist for amd64 and arm64, this host is %s"
    m ERR_CURL "curl is missing"
    m ERR_TTY "no terminal for the wizard; download the script and run it with sudo bash"
    m TUI_INSTALL "Installing whiptail for the wizard ..."
    m ERR_TUI "neither whiptail nor dialog, install one of them"
    m CANCEL_T "Cancel"
    m CANCEL_Q "Stop the installation? mosquitto-ui has not been set up yet."
    m CANCELLED "Installation cancelled."
    m FAILED_T "Failed: %s"
    m FAILED "%s failed, see %s"

    m WELCOME_T "Welcome"
    m WELCOME "This wizard installs mosquitto-ui, a web UI for Mosquitto, with its broker on this host:\n
  1. Docker and its Compose plugin, if they are missing
  2. compose.yaml and .env in a directory of your choice
  3. the containers mosquitto (the broker) and mosquitto-ui (the UI)

Use the arrow keys, Tab and Enter; Esc cancels."

    m DOCKER_T "Docker"
    m DOCKER_Q "Docker is not installed on this host.\n\nInstall it now with the official script of Docker (https://get.docker.com)? It adds the package repository of Docker and installs Docker Engine and the Compose plugin."
    m ERR_NEED_DOCKER "mosquitto-ui needs Docker with the Compose plugin"
    m ERR_DOWNLOAD "could not download %s"
    m STEP_DOCKER "Installing Docker (a few minutes)"
    m STEP_DOCKER_START "Starting Docker"
    m COMPOSE_T "Docker Compose"
    m COMPOSE_Q "Docker is there, but not its Compose plugin (docker compose).\n\nInstall it from the packages of this system?"
    m ERR_NEED_COMPOSE "mosquitto-ui needs the Compose plugin of Docker"
    m STEP_COMPOSE "Installing the Compose plugin"
    m ERR_COMPOSE_SELF "install the Compose plugin of Docker yourself, then run this again"
    m ERR_COMPOSE_STILL "docker compose still does not work"
    m ERR_DOCKER_INFO "Docker does not answer (docker info), is the daemon running?"
    m ERR_COMPOSE_DL "could not download compose.yaml of %s from github.com/%s"
    m ERR_COMPOSE_WRONG "the downloaded compose.yaml is not the one of mosquitto-ui"

    m DIR_T "Directory"
    m DIR_Q "Where should compose.yaml and .env go? The data of the broker and the UI live in Docker volumes of the project mosquitto-ui, not in this directory."
    m DIR_ABS "Please give an absolute path, like /opt/mosquitto-ui."

    m VER_T "Version"
    m VER_LOOKUP "Looking up the latest release on github.com/%s ..."
    m VER_Q "Which version should run? A fixed version is updated only when you say so; latest follows every release at the next pull."
    m VER_Q_NONE "The latest release could not be looked up. Which version should run?"
    m VER_RELEASE "the latest release (recommended)"
    m VER_LATEST "always the newest image"
    m VER_OTHER "enter a version"
    m VER_ENTER "Version, like 1.6.2 (see %s):"
    m VER_BAD "\"%s\" is not a version like 1.6.2."

    m PORT_T "Ports"
    m PORT_Q "Port on this host for %s:"
    m PORT_UI "the web UI (HTTP)"
    m PORT_MQTT "MQTT (1883 is the standard)"
    m PORT_MQTTS "MQTT over TLS (8883 is the standard; it answers once the UI has a certificate)"
    m PORT_NAN "\"%s\" is not a port number."
    m PORT_RANGE "A port is between 1 and 65535."
    m PORT_TAKEN "Port %s is taken on this host already. The container cannot start while it is.\n\nUse it anyway?"

    m PW_GENERATE "a random password, shown at the end (recommended)"
    m PW_OWN "type a password of your own"
    m PW_FIRST "Password (at least 12 characters):"
    m PW_SHORT "The password needs at least 12 characters."
    m PW_CHARS "Please no quotes (') and no spaces."
    m PW_AGAIN "The same password again:"
    m PW_DIFFER "The two passwords differ."
    m BROKER_PW_T "Broker password"
    m BROKER_PW_Q "Password of the broker user admin. The UI logs in to the broker with it; it is set on the first start of the broker, later changes in .env do not reach the broker."
    m UI_PW_T "UI password"
    m UI_PW_Q "Password of the UI user admin, for the local login. More users and identity providers (Keycloak, authentik, Google, GitHub ...) can be set up later on the page Login."

    m KEY_T "Secret key"
    m KEY_Q "mosquitto-ui encrypts its secrets (certificate accounts, mail and backup passwords) with a key and signs backups with it. It cannot change later; keep a copy apart from this host.\n\nRestoring a backup of another installation is simplest with the key of that installation."
    m KEY_NEW "a new key (recommended for a new installation)"
    m KEY_OLD "the key of an earlier installation"
    m KEY_ENTER "Key (MOSQUITTO_UI_SECRET_KEY of the earlier installation):"
    m KEY_BAD "That is not a key."

    m PROXY_T "Reverse proxy"
    m PROXY_Q "The UI speaks plain HTTP; put a reverse proxy with HTTPS in front of it (Traefik, Caddy, nginx ...).\n\nAddresses or networks of reverse proxies whose X-Forwarded-* headers the UI trusts, comma-separated. The default covers a proxy on this host and Docker networks:"
    m PROXY_BAD "Only addresses and networks, like 127.0.0.1,192.168.1.10,10.0.0.0/8."

    m SUMMARY_T "Summary"
    m SUMMARY "Install mosquitto-ui:\n
  Directory      %s
  Version        %s
  Web UI         port %s
  MQTT           port %s
  MQTT over TLS  port %s
  Proxies        %s

Docker publishes these ports on all addresses of this host, past ufw and firewalld rules; close them in a firewall in front of the host if they must not be reachable.

Go on?"
    m STEP_PULL "Downloading the images (mosquitto-ui and mosquitto-ui-broker)"
    m STEP_PULL_VERSION "Downloading the images of %s"
    m STEP_UP "Starting the containers"
    m START_T "Starting"
    m START_WAIT "Waiting for the UI on port %s ..."
    m ERR_NOT_READY "the UI does not answer after three minutes; see %s and: cd %s && docker compose logs"

    m DONE_T "Done"
    m DONE "mosquitto-ui %s runs.

  Web UI          http://%s
  UI login        admin / %s
  MQTT            %s
  Broker login    admin / %s
  Secret key      %s

Write down the passwords and keep the secret key apart from this host: it decrypts the backups.

  Settings        %s/.env
  Logs            cd %s && docker compose logs -f
  Update          sudo bash install.sh (again)

Next: a reverse proxy with HTTPS in front of the UI, a certificate for MQTT over TLS on the page Certificate."

    m EXIST_T "Existing installation"
    m EXIST_Q "%s has an installation of mosquitto-ui already."
    m EXIST_UPDATE "update it to another version, keep the settings"
    m EXIST_QUIT "leave it as it is"
    m UPDATE_T "Update"
    m UPDATE_Q "Update mosquitto-ui in %s\n\n  from version  %s\n  to version    %s\n\nThe settings in .env stay. A new broker image restarts the broker, which disconnects its clients for a moment; the old compose.yaml stays as compose.yaml.bak if it changes."
    m UPDATE_DONE "mosquitto-ui in %s runs version %s."
    m NOTHING "Nothing changed."
}

# shellcheck disable=SC1111 # German quotation marks
lang_de() {
    m TITLE "mosquitto-ui Installation"
    m ERROR "Fehler: %s"
    m BTN_OK "OK"; m BTN_CANCEL "Abbrechen"; m BTN_YES "Ja"; m BTN_NO "Nein"
    m ERR_ROOT "als root starten: sudo bash %s"
    m ERR_ARCH "die Images gibt es für amd64 und arm64, dieser Host ist %s"
    m ERR_CURL "curl fehlt"
    m ERR_TTY "kein Terminal für den Assistenten; das Skript herunterladen und mit sudo bash starten"
    m TUI_INSTALL "Installiere whiptail für den Assistenten ..."
    m ERR_TUI "weder whiptail noch dialog vorhanden, bitte eines davon installieren"
    m CANCEL_T "Abbrechen"
    m CANCEL_Q "Installation abbrechen? mosquitto-ui ist noch nicht eingerichtet."
    m CANCELLED "Installation abgebrochen."
    m FAILED_T "Fehlgeschlagen: %s"
    m FAILED "%s fehlgeschlagen, siehe %s"

    m WELCOME_T "Willkommen"
    m WELCOME "Dieser Assistent installiert mosquitto-ui, eine Weboberfläche für Mosquitto, samt Broker auf diesem Host:\n
  1. Docker und das Compose-Plugin, falls sie fehlen
  2. compose.yaml und .env in einem Verzeichnis Ihrer Wahl
  3. die Container mosquitto (der Broker) und mosquitto-ui (die UI)

Bedienung mit Pfeiltasten, Tab und Enter; Esc bricht ab."

    m DOCKER_T "Docker"
    m DOCKER_Q "Docker ist auf diesem Host nicht installiert.\n\nJetzt mit dem offiziellen Skript von Docker (https://get.docker.com) installieren? Es richtet die Paketquelle von Docker ein und installiert Docker Engine und das Compose-Plugin."
    m ERR_NEED_DOCKER "mosquitto-ui braucht Docker mit dem Compose-Plugin"
    m ERR_DOWNLOAD "%s ließ sich nicht herunterladen"
    m STEP_DOCKER "Docker wird installiert (einige Minuten)"
    m STEP_DOCKER_START "Docker wird gestartet"
    m COMPOSE_T "Docker Compose"
    m COMPOSE_Q "Docker ist vorhanden, aber nicht das Compose-Plugin (docker compose).\n\nAus den Paketen dieses Systems installieren?"
    m ERR_NEED_COMPOSE "mosquitto-ui braucht das Compose-Plugin von Docker"
    m STEP_COMPOSE "Das Compose-Plugin wird installiert"
    m ERR_COMPOSE_SELF "bitte das Compose-Plugin von Docker selbst installieren und das Skript erneut starten"
    m ERR_COMPOSE_STILL "docker compose funktioniert immer noch nicht"
    m ERR_DOCKER_INFO "Docker antwortet nicht (docker info), läuft der Dienst?"
    m ERR_COMPOSE_DL "compose.yaml von %s ließ sich nicht von github.com/%s laden"
    m ERR_COMPOSE_WRONG "die geladene compose.yaml ist nicht die von mosquitto-ui"

    m DIR_T "Verzeichnis"
    m DIR_Q "Wohin sollen compose.yaml und .env? Die Daten von Broker und UI liegen in Docker-Volumes des Projekts mosquitto-ui, nicht in diesem Verzeichnis."
    m DIR_ABS "Bitte einen absoluten Pfad angeben, etwa /opt/mosquitto-ui."

    m VER_T "Version"
    m VER_LOOKUP "Suche das neueste Release auf github.com/%s ..."
    m VER_Q "Welche Version soll laufen? Eine feste Version ändert sich nur, wenn Sie es wollen; latest folgt jedem Release beim nächsten Pull."
    m VER_Q_NONE "Das neueste Release ließ sich nicht ermitteln. Welche Version soll laufen?"
    m VER_RELEASE "das neueste Release (empfohlen)"
    m VER_LATEST "immer das neueste Image"
    m VER_OTHER "Version eingeben"
    m VER_ENTER "Version, etwa 1.6.2 (siehe %s):"
    m VER_BAD "„%s“ ist keine Version wie 1.6.2."

    m PORT_T "Ports"
    m PORT_Q "Port auf diesem Host für %s:"
    m PORT_UI "die Weboberfläche (HTTP)"
    m PORT_MQTT "MQTT (Standard ist 1883)"
    m PORT_MQTTS "MQTT über TLS (Standard ist 8883; antwortet, sobald die UI ein Zertifikat hat)"
    m PORT_NAN "„%s“ ist keine Portnummer."
    m PORT_RANGE "Ein Port liegt zwischen 1 und 65535."
    m PORT_TAKEN "Port %s ist auf diesem Host schon belegt. Solange er belegt ist, startet der Container nicht.\n\nTrotzdem verwenden?"

    m PW_GENERATE "ein zufälliges Passwort, wird am Ende angezeigt (empfohlen)"
    m PW_OWN "ein eigenes Passwort eingeben"
    m PW_FIRST "Passwort (mindestens 12 Zeichen):"
    m PW_SHORT "Das Passwort braucht mindestens 12 Zeichen."
    m PW_CHARS "Bitte keine Hochkommas (') und keine Leerzeichen."
    m PW_AGAIN "Dasselbe Passwort noch einmal:"
    m PW_DIFFER "Die beiden Passwörter sind verschieden."
    m BROKER_PW_T "Broker-Passwort"
    m BROKER_PW_Q "Passwort des Broker-Benutzers admin. Damit meldet sich die UI am Broker an. Es wird beim ersten Start des Brokers gesetzt, spätere Änderungen in .env erreichen den Broker nicht."
    m UI_PW_T "UI-Passwort"
    m UI_PW_Q "Passwort des UI-Benutzers admin für den lokalen Login. Weitere Benutzer und Identity Provider (Keycloak, authentik, Google, GitHub ...) lassen sich später auf der Seite Anmeldung einrichten."

    m KEY_T "Geheimer Schlüssel"
    m KEY_Q "mosquitto-ui verschlüsselt seine Geheimnisse (Zertifikatskonten, Mail- und Backup-Passwörter) mit einem Schlüssel und signiert damit Backups. Er lässt sich später nicht ändern; bewahren Sie eine Kopie getrennt von diesem Host auf.\n\nEin Backup einer anderen Installation lässt sich am einfachsten mit deren Schlüssel wiederherstellen."
    m KEY_NEW "ein neuer Schlüssel (empfohlen für eine neue Installation)"
    m KEY_OLD "der Schlüssel einer früheren Installation"
    m KEY_ENTER "Schlüssel (MOSQUITTO_UI_SECRET_KEY der früheren Installation):"
    m KEY_BAD "Das ist kein Schlüssel."

    m PROXY_T "Reverse Proxy"
    m PROXY_Q "Die UI spricht nur HTTP; davor gehört ein Reverse Proxy mit HTTPS (Traefik, Caddy, nginx ...).\n\nAdressen oder Netze von Reverse Proxies, deren X-Forwarded-*-Header die UI vertraut, durch Kommas getrennt. Die Vorgabe deckt einen Proxy auf diesem Host und Docker-Netze ab:"
    m PROXY_BAD "Nur Adressen und Netze, etwa 127.0.0.1,192.168.1.10,10.0.0.0/8."

    m SUMMARY_T "Zusammenfassung"
    m SUMMARY "mosquitto-ui installieren:\n
  Verzeichnis    %s
  Version        %s
  Weboberfläche  Port %s
  MQTT           Port %s
  MQTT über TLS  Port %s
  Proxies        %s

Docker veröffentlicht diese Ports auf allen Adressen dieses Hosts, an Regeln von ufw und firewalld vorbei; sollen sie nicht erreichbar sein, sperren Sie sie in einer Firewall vor dem Host.

Weiter?"
    m STEP_PULL "Die Images werden geladen (mosquitto-ui und mosquitto-ui-broker)"
    m STEP_PULL_VERSION "Die Images von %s werden geladen"
    m STEP_UP "Die Container werden gestartet"
    m START_T "Start"
    m START_WAIT "Warte auf die UI an Port %s ..."
    m ERR_NOT_READY "die UI antwortet nach drei Minuten nicht; siehe %s und: cd %s && docker compose logs"

    m DONE_T "Fertig"
    m DONE "mosquitto-ui %s läuft.

  Weboberfläche   http://%s
  UI-Login        admin / %s
  MQTT            %s
  Broker-Login    admin / %s
  Schlüssel       %s

Notieren Sie die Passwörter und bewahren Sie den Schlüssel getrennt von diesem Host auf: Er entschlüsselt die Backups.

  Einstellungen   %s/.env
  Logs            cd %s && docker compose logs -f
  Update          sudo bash install.sh (erneut)

Als Nächstes: ein Reverse Proxy mit HTTPS vor der UI, ein Zertifikat für MQTT über TLS auf der Seite Zertifikat."

    m EXIST_T "Vorhandene Installation"
    m EXIST_Q "In %s ist mosquitto-ui bereits installiert."
    m EXIST_UPDATE "auf eine andere Version aktualisieren, Einstellungen bleiben"
    m EXIST_QUIT "so lassen, wie es ist"
    m UPDATE_T "Update"
    m UPDATE_Q "mosquitto-ui in %s aktualisieren\n\n  von Version   %s\n  auf Version   %s\n\nDie Einstellungen in .env bleiben. Ein neues Broker-Image startet den Broker neu, der seine Clients dabei kurz trennt; die alte compose.yaml bleibt als compose.yaml.bak, falls sie sich ändert."
    m UPDATE_DONE "mosquitto-ui in %s läuft mit Version %s."
    m NOTHING "Nichts geändert."
}

lang_fr() {
    m TITLE "Installation de mosquitto-ui"
    m ERROR "Erreur : %s"
    m BTN_OK "OK"; m BTN_CANCEL "Annuler"; m BTN_YES "Oui"; m BTN_NO "Non"
    m ERR_ROOT "à lancer en root : sudo bash %s"
    m ERR_ARCH "les images existent pour amd64 et arm64, cet hôte est %s"
    m ERR_CURL "curl est absent"
    m ERR_TTY "pas de terminal pour l'assistant ; téléchargez le script et lancez-le avec sudo bash"
    m TUI_INSTALL "Installation de whiptail pour l'assistant ..."
    m ERR_TUI "ni whiptail ni dialog, installez l'un des deux"
    m CANCEL_T "Annuler"
    m CANCEL_Q "Arrêter l'installation ? mosquitto-ui n'est pas encore installé."
    m CANCELLED "Installation annulée."
    m FAILED_T "Échec : %s"
    m FAILED "%s : échec, voir %s"

    m WELCOME_T "Bienvenue"
    m WELCOME "Cet assistant installe mosquitto-ui, une interface web pour Mosquitto, avec son broker sur cet hôte :\n
  1. Docker et son plugin Compose, s'ils manquent
  2. compose.yaml et .env dans le répertoire de votre choix
  3. les conteneurs mosquitto (le broker) et mosquitto-ui (l'interface)

Flèches, Tab et Entrée pour naviguer ; Échap pour annuler."

    m DOCKER_T "Docker"
    m DOCKER_Q "Docker n'est pas installé sur cet hôte.\n\nL'installer maintenant avec le script officiel de Docker (https://get.docker.com) ? Il ajoute le dépôt de paquets de Docker et installe Docker Engine et le plugin Compose."
    m ERR_NEED_DOCKER "mosquitto-ui a besoin de Docker avec le plugin Compose"
    m ERR_DOWNLOAD "impossible de télécharger %s"
    m STEP_DOCKER "Installation de Docker (quelques minutes)"
    m STEP_DOCKER_START "Démarrage de Docker"
    m COMPOSE_T "Docker Compose"
    m COMPOSE_Q "Docker est là, mais pas son plugin Compose (docker compose).\n\nL'installer depuis les paquets de ce système ?"
    m ERR_NEED_COMPOSE "mosquitto-ui a besoin du plugin Compose de Docker"
    m STEP_COMPOSE "Installation du plugin Compose"
    m ERR_COMPOSE_SELF "installez vous-même le plugin Compose de Docker, puis relancez ce script"
    m ERR_COMPOSE_STILL "docker compose ne fonctionne toujours pas"
    m ERR_DOCKER_INFO "Docker ne répond pas (docker info), le service tourne-t-il ?"
    m ERR_COMPOSE_DL "impossible de télécharger compose.yaml de %s depuis github.com/%s"
    m ERR_COMPOSE_WRONG "le compose.yaml téléchargé n'est pas celui de mosquitto-ui"

    m DIR_T "Répertoire"
    m DIR_Q "Où placer compose.yaml et .env ? Les données du broker et de l'interface sont dans des volumes Docker du projet mosquitto-ui, pas dans ce répertoire."
    m DIR_ABS "Indiquez un chemin absolu, par exemple /opt/mosquitto-ui."

    m VER_T "Version"
    m VER_LOOKUP "Recherche de la dernière version sur github.com/%s ..."
    m VER_Q "Quelle version installer ? Une version fixe ne change que si vous le décidez ; latest suit chaque version au prochain pull."
    m VER_Q_NONE "La dernière version n'a pas pu être déterminée. Quelle version installer ?"
    m VER_RELEASE "la dernière version (recommandé)"
    m VER_LATEST "toujours l'image la plus récente"
    m VER_OTHER "saisir une version"
    m VER_ENTER "Version, par exemple 1.6.2 (voir %s) :"
    m VER_BAD "« %s » n'est pas une version comme 1.6.2."

    m PORT_T "Ports"
    m PORT_Q "Port sur cet hôte pour %s :"
    m PORT_UI "l'interface web (HTTP)"
    m PORT_MQTT "MQTT (1883 par défaut)"
    m PORT_MQTTS "MQTT sur TLS (8883 par défaut ; répond dès que l'interface a un certificat)"
    m PORT_NAN "« %s » n'est pas un numéro de port."
    m PORT_RANGE "Un port est compris entre 1 et 65535."
    m PORT_TAKEN "Le port %s est déjà occupé sur cet hôte. Tant qu'il l'est, le conteneur ne démarre pas.\n\nL'utiliser quand même ?"

    m PW_GENERATE "un mot de passe aléatoire, affiché à la fin (recommandé)"
    m PW_OWN "saisir votre propre mot de passe"
    m PW_FIRST "Mot de passe (au moins 12 caractères) :"
    m PW_SHORT "Le mot de passe doit avoir au moins 12 caractères."
    m PW_CHARS "Pas d'apostrophe (') ni d'espace, s'il vous plaît."
    m PW_AGAIN "Le même mot de passe une seconde fois :"
    m PW_DIFFER "Les deux mots de passe diffèrent."
    m BROKER_PW_T "Mot de passe du broker"
    m BROKER_PW_Q "Mot de passe de l'utilisateur admin du broker. L'interface s'en sert pour se connecter au broker ; il est fixé au premier démarrage du broker, les modifications ultérieures dans .env ne l'atteignent pas."
    m UI_PW_T "Mot de passe de l'interface"
    m UI_PW_Q "Mot de passe de l'utilisateur admin de l'interface, pour la connexion locale. D'autres utilisateurs et fournisseurs d'identité (Keycloak, authentik, Google, GitHub ...) se configurent plus tard sur la page Connexion."

    m KEY_T "Clé secrète"
    m KEY_Q "mosquitto-ui chiffre ses secrets (comptes de certificats, mots de passe de mail et de sauvegarde) avec une clé et signe les sauvegardes avec elle. Elle ne peut plus changer ensuite ; gardez-en une copie hors de cet hôte.\n\nUne sauvegarde d'une autre installation se restaure le plus simplement avec la clé de celle-ci."
    m KEY_NEW "une nouvelle clé (recommandé pour une nouvelle installation)"
    m KEY_OLD "la clé d'une installation précédente"
    m KEY_ENTER "Clé (MOSQUITTO_UI_SECRET_KEY de l'installation précédente) :"
    m KEY_BAD "Ce n'est pas une clé."

    m PROXY_T "Reverse proxy"
    m PROXY_Q "L'interface ne parle que HTTP ; placez devant elle un reverse proxy avec HTTPS (Traefik, Caddy, nginx ...).\n\nAdresses ou réseaux des reverse proxies dont l'interface accepte les en-têtes X-Forwarded-*, séparés par des virgules. La valeur par défaut couvre un proxy sur cet hôte et les réseaux Docker :"
    m PROXY_BAD "Uniquement des adresses et des réseaux, par exemple 127.0.0.1,192.168.1.10,10.0.0.0/8."

    m SUMMARY_T "Récapitulatif"
    m SUMMARY "Installer mosquitto-ui :\n
  Répertoire      %s
  Version         %s
  Interface web   port %s
  MQTT            port %s
  MQTT sur TLS    port %s
  Proxies         %s

Docker publie ces ports sur toutes les adresses de cet hôte, sans passer par les règles de ufw ou firewalld ; s'ils ne doivent pas être joignables, bloquez-les dans un pare-feu devant l'hôte.

Continuer ?"
    m STEP_PULL "Téléchargement des images (mosquitto-ui et mosquitto-ui-broker)"
    m STEP_PULL_VERSION "Téléchargement des images de %s"
    m STEP_UP "Démarrage des conteneurs"
    m START_T "Démarrage"
    m START_WAIT "Attente de l'interface sur le port %s ..."
    m ERR_NOT_READY "l'interface ne répond pas après trois minutes ; voir %s et : cd %s && docker compose logs"

    m DONE_T "Terminé"
    m DONE "mosquitto-ui %s fonctionne.

  Interface web    http://%s
  Connexion UI     admin / %s
  MQTT             %s
  Connexion MQTT   admin / %s
  Clé secrète      %s

Notez les mots de passe et gardez la clé secrète hors de cet hôte : elle déchiffre les sauvegardes.

  Paramètres       %s/.env
  Logs             cd %s && docker compose logs -f
  Mise à jour      sudo bash install.sh (à nouveau)

Ensuite : un reverse proxy avec HTTPS devant l'interface, un certificat pour MQTT sur TLS sur la page Certificat."

    m EXIST_T "Installation existante"
    m EXIST_Q "%s contient déjà une installation de mosquitto-ui."
    m EXIST_UPDATE "passer à une autre version, garder les paramètres"
    m EXIST_QUIT "la laisser telle quelle"
    m UPDATE_T "Mise à jour"
    m UPDATE_Q "Mettre à jour mosquitto-ui dans %s\n\n  de la version   %s\n  à la version    %s\n\nLes paramètres de .env restent. Une nouvelle image du broker redémarre le broker, qui déconnecte brièvement ses clients ; l'ancien compose.yaml reste en compose.yaml.bak s'il change."
    m UPDATE_DONE "mosquitto-ui dans %s fonctionne en version %s."
    m NOTHING "Rien n'a été modifié."
}

# The language of the system: LC_ALL, LC_MESSAGES or LANG (sudo keeps LANG)
system_lang() {
    case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in
        de*) echo de ;;
        fr*) echo fr ;;
        *) echo en ;;
    esac
}

use_lang() {
    UI_LANG=$1
    lang_en
    case "$UI_LANG" in
        de) lang_de ;;
        fr) lang_fr ;;
    esac
}

# Umlauts and accents need a UTF-8 locale for whiptail; a minimal system or sudo may have none
utf8_locale() {
    case "$(locale charmap 2>/dev/null)" in UTF-8|utf8) return ;; esac
    if locale -a 2>/dev/null | grep -qiE '^c\.utf-?8$'; then
        export LC_ALL=C.UTF-8
    fi
}

fail() { [ -n "$TUI" ] && clear; printf '%s\n' "$(t ERROR "$*")" >&2; exit 1; }

# --- dialogs (whiptail or dialog, mostly the same arguments) ---------------------------------------------------------
size() {
    local lines cols
    lines=$(tput lines 2>/dev/null || echo 24)
    cols=$(tput cols 2>/dev/null || echo 80)
    H=$(( lines > 30 ? 24 : lines - 4 ))
    W=$(( cols > 84 ? 78 : cols - 4 ))
}
# height for a text in a box of width W, at most H
fit() {
    local text n=0 line
    text=$(printf '%b' "$1")
    while IFS= read -r line; do n=$(( n + ${#line} / (W - 4) + 1 )); done <<< "$text"
    n=$(( n + $2 ))
    echo $(( n > H ? H : n ))
}
box() {
    if [ "$TUI" = dialog ]; then
        dialog --backtitle "$(t TITLE)" --ok-label "$(t BTN_OK)" --cancel-label "$(t BTN_CANCEL)" \
            --yes-label "$(t BTN_YES)" --no-label "$(t BTN_NO)" "$@"
    else
        whiptail --backtitle "$(t TITLE)" --ok-button "$(t BTN_OK)" --cancel-button "$(t BTN_CANCEL)" \
            --yes-button "$(t BTN_YES)" --no-button "$(t BTN_NO)" "$@"
    fi
}
# Esc on a note is the same as Ok
msg() { size; box --title "$1" --msgbox "$2" "$(fit "$2" 7)" "$W" || true; }
info() { size; box --title "$1" --infobox "$2" 10 "$W" || true; }
yesno() { size; box --title "$1" --yesno "$2" "$(fit "$2" 7)" "$W"; }
# input <title> <text> <default>: the answer on stdout; non-zero on cancel
input() { size; box --title "$1" --inputbox "$2" "$(fit "$2" 9)" "$W" "$3" 3>&1 1>&2 2>&3; }
secret() { size; box --title "$1" --passwordbox "$2" "$(fit "$2" 9)" "$W" 3>&1 1>&2 2>&3; }
# menu <title> <text> <tag> <item> ...
menu() {
    local title=$1 text=$2
    shift 2
    size
    box --title "$title" --menu "$text" "$(fit "$text" $(( $# / 2 + 8 )))" "$W" $(( $# / 2 )) "$@" 3>&1 1>&2 2>&3
}

# Cancel or Esc in the wizard: stop, or go on with the question
cancelled() {
    if yesno "$(t CANCEL_T)" "$(t CANCEL_Q)"; then
        clear
        t CANCELLED; echo
        exit 0
    fi
}

# Runs a command into the log, with a gauge that shows the last line of its output; on failure shows that output and stops.
# (No infobox: whiptail clears it at once in most terminals.)
run() {
    local what=$1 pid status=0 start
    shift
    printf '\n=== %s: %s\n' "$(date -Is)" "$what" >> "$LOG"
    start=$(wc -l < "$LOG")
    "$@" >> "$LOG" 2>&1 < /dev/null &
    pid=$!
    size
    {
        local percent=0 line
        while kill -0 "$pid" 2>/dev/null; do
            # the length is not known: slower and slower towards 95
            percent=$(( percent + (95 - percent + 39) / 40 ))
            # the gauge shows only one line of text: the last one of the output
            line=$(tail -n +"$(( start + 1 ))" "$LOG" | tail -n 5 | tr -cd '[:print:]\n' | grep -v '^[[:space:]]*$' | tail -n 1 \
                | sed 's/^[[:space:]]*//' | cut -c1-$(( W - 8 )) || true)
            printf 'XXX\n%s\n%s\nXXX\n' "$percent" "${line:-...}"
            sleep 1
        done
        echo 100
    } | box --title "$what" --gauge "..." 7 "$W" 0
    wait "$pid" || status=$?
    if [ "$status" != 0 ]; then
        # what this step wrote, at most its last 40 lines
        tail -n +"$(( start + 1 ))" "$LOG" | tail -n 40 | tr -cd '[:print:]\n' | cut -c1-$(( W - 6 )) > "$WORK/tail"
        box --title "$(t FAILED_T "$what")" --scrolltext --textbox "$WORK/tail" "$H" "$W" || true
        fail "$(t FAILED "$what" "$LOG")"
    fi
}

# --- the host ----------------------------------------------------------------------------------------------------------
need_tui() {
    if command -v whiptail >/dev/null; then TUI=whiptail; return; fi
    if command -v dialog >/dev/null; then TUI=dialog; return; fi
    t TUI_INSTALL; echo
    if command -v apt-get >/dev/null; then
        apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq whiptail >/dev/null
    elif command -v dnf >/dev/null; then
        dnf install -y -q newt >/dev/null
    elif command -v yum >/dev/null; then
        yum install -y -q newt >/dev/null
    elif command -v zypper >/dev/null; then
        zypper -q -n install newt >/dev/null
    fi
    command -v whiptail >/dev/null || fail "$(t ERR_TUI)"
    TUI=whiptail
}

check_host() {
    [ "$(id -u)" = 0 ] || fail "$(t ERR_ROOT "$0")"
    case "$(uname -m)" in
        x86_64|amd64|aarch64|arm64) ;;
        *) fail "$(t ERR_ARCH "$(uname -m)")" ;;
    esac
    command -v curl >/dev/null || fail "$(t ERR_CURL)"
}

ask_lang() {
    local choice
    [ "$LANG_ASKED" = yes ] && return
    size
    # three languages at once, the buttons in the one of the system
    choice=$(box --title "Language / Sprache / Langue" --default-item "$UI_LANG" --menu "" 12 "$W" 3 \
        en "English" \
        de "Deutsch" \
        fr "Français" 3>&1 1>&2 2>&3) || { clear; exit 0; }
    use_lang "$choice"
}

compose_ok() { docker compose version >/dev/null 2>&1; }

ensure_docker() {
    if ! command -v docker >/dev/null; then
        yesno "$(t DOCKER_T)" "$(t DOCKER_Q)" || fail "$(t ERR_NEED_DOCKER)"
        curl -fsSL --retry 3 -o "$WORK/get-docker.sh" https://get.docker.com || fail "$(t ERR_DOWNLOAD https://get.docker.com)"
        run "$(t STEP_DOCKER)" sh "$WORK/get-docker.sh"
    fi
    if command -v systemctl >/dev/null && [ -d /run/systemd/system ] && ! systemctl is-active --quiet docker; then
        # on failure with the reason, which systemctl only points to
        run "$(t STEP_DOCKER_START)" sh -c 'systemctl enable --now docker || { journalctl -u docker -n 25 --no-pager -o cat; exit 1; }'
    fi
    if ! compose_ok; then
        yesno "$(t COMPOSE_T)" "$(t COMPOSE_Q)" || fail "$(t ERR_NEED_COMPOSE)"
        if command -v apt-get >/dev/null; then
            run "$(t STEP_COMPOSE)" sh -c 'apt-get update -qq && { DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-compose-plugin || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-compose-v2; }'
        elif command -v dnf >/dev/null; then
            run "$(t STEP_COMPOSE)" dnf install -y -q docker-compose-plugin
        else
            fail "$(t ERR_COMPOSE_SELF)"
        fi
        compose_ok || fail "$(t ERR_COMPOSE_STILL)"
    fi
    docker info >/dev/null 2>&1 || fail "$(t ERR_DOCKER_INFO)"
}

# --- versions and files ------------------------------------------------------------------------------------------------
latest_release() {
    curl -fsSL --retry 2 --max-time 15 "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
        | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1
}

# compose.yaml of a version (latest: of main), into $1
fetch_compose() {
    local version=$1 target=$2 ref
    case "$version" in latest) ref=main ;; *) ref=v$version ;; esac
    if curl -fsSL --retry 3 -o "$WORK/compose.yaml" "https://raw.githubusercontent.com/$REPO/$ref/deploy/compose.yaml"; then
        :
    elif [ -n "$HERE" ] && [ -f "$HERE/compose.yaml" ]; then
        # run from a copy of the repository
        cp "$HERE/compose.yaml" "$WORK/compose.yaml"
    else
        fail "$(t ERR_COMPOSE_DL "$ref" "$REPO")"
    fi
    grep -q 'mosquitto-ui-broker' "$WORK/compose.yaml" || fail "$(t ERR_COMPOSE_WRONG)"
    if [ -f "$target" ] && ! cmp -s "$WORK/compose.yaml" "$target"; then
        cp "$target" "$target.bak"
    fi
    install -m 644 "$WORK/compose.yaml" "$target"
}

random() { head -c 48 /dev/urandom | base64 | tr -d '/+=\n' | head -c "$1"; }

port_taken() {
    command -v ss >/dev/null && ss -ltnH "sport = :$1" 2>/dev/null | grep -q .
}

# --- the wizard --------------------------------------------------------------------------------------------------------
ask_dir() {
    local answer
    while true; do
        answer=$(input "$(t DIR_T)" "$(t DIR_Q)" "$DIR") || { cancelled; continue; }
        case "$answer" in
            /*) DIR=${answer%/}; return ;;
            *) msg "$(t DIR_T)" "$(t DIR_ABS)" ;;
        esac
    done
}

ask_version() {
    local latest choice answer
    info "$(t VER_T)" "$(t VER_LOOKUP "$REPO")"
    latest=$(latest_release || true)
    while true; do
        if [ -n "$latest" ]; then
            choice=$(menu "$(t VER_T)" "$(t VER_Q)" \
                "$latest" "$(t VER_RELEASE)" \
                "latest" "$(t VER_LATEST)" \
                "other" "$(t VER_OTHER)") || { cancelled; continue; }
        else
            choice=$(menu "$(t VER_T)" "$(t VER_Q_NONE)" \
                "latest" "$(t VER_LATEST)" \
                "other" "$(t VER_OTHER)") || { cancelled; continue; }
        fi
        if [ "$choice" = other ]; then
            answer=$(input "$(t VER_T)" "$(t VER_ENTER "https://github.com/$REPO/releases")" "${latest:-}") || continue
            answer=${answer#v}
            case "$answer" in
                [0-9]*.[0-9]*.[0-9]*) VERSION=$answer; return ;;
                *) msg "$(t VER_T)" "$(t VER_BAD "$answer")" ;;
            esac
        else
            VERSION=$choice
            return
        fi
    done
}

# ask_port <variable> <what> <default>
ask_port() {
    local var=$1 what=$2 value=$3 answer
    while true; do
        answer=$(input "$(t PORT_T)" "$(t PORT_Q "$what")" "$value") || { cancelled; continue; }
        case "$answer" in
            ''|*[!0-9]*) msg "$(t PORT_T)" "$(t PORT_NAN "$answer")"; continue ;;
        esac
        if [ "$answer" -lt 1 ] || [ "$answer" -gt 65535 ]; then
            msg "$(t PORT_T)" "$(t PORT_RANGE)"
            continue
        fi
        if port_taken "$answer" && ! yesno "$(t PORT_T)" "$(t PORT_TAKEN "$answer")"; then
            continue
        fi
        printf -v "$var" '%s' "$answer"
        return
    done
}

# ask_password <variable> <title> <text>: generated, or entered twice; at least 12 characters, no ' (quotes in .env)
ask_password() {
    local var=$1 title=$2 text=$3 choice first second
    while true; do
        choice=$(menu "$title" "$text" \
            "generate" "$(t PW_GENERATE)" \
            "enter" "$(t PW_OWN)") || { cancelled; continue; }
        if [ "$choice" = generate ]; then
            printf -v "$var" '%s' "$(random 24)"
            return
        fi
        first=$(secret "$title" "$(t PW_FIRST)") || continue
        if [ "${#first}" -lt 12 ]; then msg "$title" "$(t PW_SHORT)"; continue; fi
        case "$first" in *"'"*|*[[:space:]]*) msg "$title" "$(t PW_CHARS)"; continue ;; esac
        second=$(secret "$title" "$(t PW_AGAIN)") || continue
        [ "$first" = "$second" ] || { msg "$title" "$(t PW_DIFFER)"; continue; }
        printf -v "$var" '%s' "$first"
        return
    done
}

ask_secret_key() {
    local choice answer
    while true; do
        choice=$(menu "$(t KEY_T)" "$(t KEY_Q)" \
            "generate" "$(t KEY_NEW)" \
            "enter" "$(t KEY_OLD)") || { cancelled; continue; }
        if [ "$choice" = generate ]; then
            SECRET_KEY=$(head -c 32 /dev/urandom | base64)
            return
        fi
        answer=$(input "$(t KEY_T)" "$(t KEY_ENTER)" "") || continue
        case "$answer" in
            ''|*"'"*|*[[:space:]]*) msg "$(t KEY_T)" "$(t KEY_BAD)" ;;
            *) SECRET_KEY=$answer; return ;;
        esac
    done
}

ask_proxies() {
    local answer
    while true; do
        answer=$(input "$(t PROXY_T)" "$(t PROXY_Q)" "127.0.0.1,172.16.0.0/12") || { cancelled; continue; }
        case "$answer" in
            ''|*[!0-9a-fA-F.:/,]*) msg "$(t PROXY_T)" "$(t PROXY_BAD)" ;;
            *) PROXIES=$answer; return ;;
        esac
    done
}

# --- an update of an existing installation ----------------------------------------------------------------------------
update() {
    local current
    current=$(sed -n "s/^MOSQUITTO_UI_VERSION=['\"]\{0,1\}\([^'\"]*\).*/\1/p" "$DIR/.env" | head -1)
    ask_version
    yesno "$(t UPDATE_T)" "$(t UPDATE_Q "$DIR" "${current:-latest}" "$VERSION")" || { clear; t NOTHING; echo; exit 0; }
    fetch_compose "$VERSION" "$DIR/compose.yaml"
    if grep -q '^MOSQUITTO_UI_VERSION=' "$DIR/.env"; then
        sed -i "s/^MOSQUITTO_UI_VERSION=.*/MOSQUITTO_UI_VERSION='$VERSION'/" "$DIR/.env"
    else
        echo "MOSQUITTO_UI_VERSION='$VERSION'" >> "$DIR/.env"
    fi
    run "$(t STEP_PULL_VERSION "$VERSION")" docker compose --project-directory "$DIR" pull
    run "$(t STEP_UP)" docker compose --project-directory "$DIR" up -d --remove-orphans
    wait_ready "$(sed -n "s/^MOSQUITTO_UI_PORT=['\"]\{0,1\}\([0-9]*\).*/\1/p" "$DIR/.env" | head -1)"
    clear
    t UPDATE_DONE "$DIR" "$VERSION"; echo
}

# --- installing --------------------------------------------------------------------------------------------------------
write_env() {
    local env=$DIR/.env
    umask 077
    cat > "$env" <<EOF
# Settings of mosquitto-ui, written by deploy/install.sh on $(date -I). See env.example of the repository for all of them.
# After a change: docker compose up -d (in this directory)

# Password of the broker user admin, only read on the first start of the broker
MOSQUITTO_PASSWORD='$BROKER_PASSWORD'
MQTT_PORT=$MQTT_PORT
MQTTS_PORT=$MQTTS_PORT

# Login: UI users and identity providers are set up on the page Login; until a provider is on, the local login is.
# local keeps the local login on whatever that page says: the way back in when nobody can log in at the provider
MOSQUITTO_UI_AUTH=oidc
# Only read on the first start, removed by the installer afterwards
MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD='$UI_PASSWORD'

# Encrypts the secrets in the state volume and signs backups. Keep a copy apart from this host: it cannot change later
MOSQUITTO_UI_SECRET_KEY='$SECRET_KEY'

MOSQUITTO_UI_VERSION='$VERSION'
MOSQUITTO_UI_REGISTRY=
MOSQUITTO_UI_PORT=$UI_PORT
MOSQUITTO_UI_MEMORY=512m
TRUSTED_PROXIES=$PROXIES
EOF
    umask 022
    chmod 600 "$env"
}

# Waits for the UI to be ready, with a gauge
wait_ready() {
    local port=${1:-8080} i ok=no
    size
    {
        for i in $(seq 1 90); do
            if curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:$port/q/health/ready" 2>/dev/null; then
                echo 100
                break
            fi
            echo $(( i * 100 / 90 ))
            sleep 2
        done
    } | box --title "$(t START_T)" --gauge "$(t START_WAIT "$port")" 8 "$W" 0
    curl -fsS -o /dev/null --max-time 5 "http://127.0.0.1:$port/q/health/ready" 2>/dev/null && ok=yes
    if [ "$ok" = no ]; then
        docker compose --project-directory "$DIR" ps >> "$LOG" 2>&1 || true
        docker compose --project-directory "$DIR" logs --tail 60 >> "$LOG" 2>&1 || true
        fail "$(t ERR_NOT_READY "$LOG" "$DIR")"
    fi
}

host_address() {
    local address
    address=$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)
    [ -n "$address" ] || address=$(hostname -I 2>/dev/null | awk '{print $1}')
    echo "${address:-$(hostname)}"
}

install_new() {
    ask_version
    ask_port UI_PORT "$(t PORT_UI)" 8080
    ask_port MQTT_PORT "$(t PORT_MQTT)" 1883
    ask_port MQTTS_PORT "$(t PORT_MQTTS)" 8883
    ask_password BROKER_PASSWORD "$(t BROKER_PW_T)" "$(t BROKER_PW_Q)"
    ask_password UI_PASSWORD "$(t UI_PW_T)" "$(t UI_PW_Q)"
    ask_secret_key
    ask_proxies

    yesno "$(t SUMMARY_T)" "$(t SUMMARY "$DIR" "$VERSION" "$UI_PORT" "$MQTT_PORT" "$MQTTS_PORT" "$PROXIES")" \
        || { cancelled; install_new; return; }

    install -d -m 755 "$DIR"
    fetch_compose "$VERSION" "$DIR/compose.yaml"
    write_env
    run "$(t STEP_PULL)" docker compose --project-directory "$DIR" pull
    run "$(t STEP_UP)" docker compose --project-directory "$DIR" up -d
    wait_ready "$UI_PORT"
    # the UI user exists now: the password does not need to stay in .env
    sed -i "s/^MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD=.*/MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD=/" "$DIR/.env"

    local address result
    address=$(host_address)
    result=$(t DONE "$VERSION" "$address:$UI_PORT" "$UI_PASSWORD" "$address:$MQTT_PORT" "$BROKER_PASSWORD" "$SECRET_KEY" "$DIR" "$DIR")
    size
    box --title "$(t DONE_T)" --scrolltext --msgbox "$result" "$H" "$W" || true
    clear
    # also in the terminal, to copy
    printf '%s\n' "$result"
}

main() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --dir) DIR=${2:?--dir needs a directory}; shift 2 ;;
            --lang)
                case "${2:-}" in en|de|fr) UI_LANG=$2; LANG_ASKED=yes ;; *) echo "--lang needs en, de or fr" >&2; exit 2 ;; esac
                shift 2 ;;
            --repo) REPO=${2:?--repo needs owner/name}; shift 2 ;;
            -h|--help) usage; exit 0 ;;
            *) echo "Unknown option $1, see --help" >&2; exit 2 ;;
        esac
    done
    use_lang "${UI_LANG:-$(system_lang)}"
    # piped into bash: the wizard reads from the terminal
    if [ ! -t 0 ]; then
        [ -r /dev/tty ] || fail "$(t ERR_TTY)"
        exec < /dev/tty
    fi
    check_host
    utf8_locale
    need_tui
    WORK=$(mktemp -d)
    trap 'rm -rf "$WORK"' EXIT
    : >> "$LOG"
    chmod 600 "$LOG"

    ask_lang
    msg "$(t WELCOME_T)" "$(t WELCOME)"
    ensure_docker
    ask_dir
    if [ -f "$DIR/.env" ] && [ -f "$DIR/compose.yaml" ]; then
        local choice
        choice=$(menu "$(t EXIST_T)" "$(t EXIST_Q "$DIR")" \
            "update" "$(t EXIST_UPDATE)" \
            "quit" "$(t EXIST_QUIT)") || choice=quit
        if [ "$choice" = update ]; then
            update
        else
            clear
            t NOTHING; echo
        fi
        return
    fi
    install_new
}

main "$@"
