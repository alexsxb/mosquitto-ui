# Nachrichtenregeln

Auf der Seite **Nachrichtenregeln** (unter **Broker**) legst du fest, wie Nachrichten aussehen müssen und wie viele ein Client
senden darf. Der Broker prüft jede Nachricht, die ein Client sendet, bevor irgendjemand sie bekommt, und verwirft, was nicht
passt. Das übernimmt das Plugin `docker/mosquitto/inspect` (Datei `rules.c`), dasselbe wie für
[Sitzungen und Aufzeichnung](sitzungen-aufzeichnung.md). Gilt ab mosquitto-ui 0.22.0.

## Wofür

- **Kaputte Daten aufhalten:** Ein Gerät schickt nach einem Firmware-Fehler halbe JSON-Nachrichten. Ohne Regel bekommen
  Home Assistant, Node-RED oder die Datenbank den Müll und stolpern darüber.
- **Ein Gerät im Amok bremsen:** Ein Sensor hängt in einer Schleife und schickt tausende Nachrichten pro Sekunde. Mosquitto
  arbeitet auf einem Kern; das bremst alle anderen Clients. Mit einer Mengengrenze bleibt der Schaden bei diesem einen Gerät.

## Voraussetzungen

- Broker-Image ab 0.22.0. Die `mosquitto.conf` des Images enthält:

  ```
  plugin /usr/lib/mosquitto_inspect.so
  plugin_opt_rules_file /mosquitto/ui/rules/rules.json
  ```

  Das Plugin muss vor allen anderen Plugins stehen. Wer eine eigene `mosquitto.conf` nutzt, übernimmt beide Zeilen.
- Für die Zähler auf der Seite: dieselben Rechte wie für die Sitzungen (`$CONTROL/inspect/#`, siehe dort). Die Regeln selbst
  wirken auch ohne diese Rechte, denn der Broker liest sie aus der Datei.

## Inhaltsregeln

| Feld | Bedeutung |
|---|---|
| Name | frei, nur zur Anzeige |
| Topic-Filter | für welche Nachrichten die Regel gilt, MQTT-Filter wie `shelly/#` oder `+/status/+` |
| Clients | leer oder `*`: alle Clients; mit `*` am Ende alle Client-IDs, die so beginnen (`shelly*`); sonst genau diese Client-ID |
| Muss gültiges JSON sein | die Nachricht muss sich als JSON lesen lassen |
| Höchstgröße | größere Nachrichten werden verworfen; leer: keine Grenze |
| Pflichtfelder | Felder, die das JSON haben muss, mit Typ. Der Pfad geht mit Punkten durch Objekte, z. B. `aenergy.total`. Typen: Zahl, Text, true/false, Objekt, Liste, `null`, beliebig (nur vorhanden). Felder setzen JSON voraus. |
| Bei Verstoß | *Verwerfen* oder *Verwerfen und trennen* |
| Mail schicken | eine Mail, wenn die Regel Nachrichten verworfen hat, höchstens eine pro Stunde |

Eine Regel muss mindestens eines prüfen: JSON, Höchstgröße oder Felder. Eine Nachricht muss **jede** Inhaltsregel erfüllen,
deren Topic-Filter und Clients zu ihr passen; die erste, die sie nicht erfüllt, verwirft sie.

Beispiel für den Status eines Shelly-Schalters (Gen2 und neuer):

| Topic-Filter | Prüfung |
|---|---|
| `shelly/+/+/+/status/switch:+` | JSON, höchstens 4096 Bytes, `output`: true/false, `apower`: Zahl, `aenergy.total`: Zahl |

## Mengengrenzen

| Feld | Bedeutung |
|---|---|
| Clients | wie oben |
| Pro Sekunde | wie viele Nachrichten pro Sekunde auf Dauer erlaubt sind, auch Bruchteile (0.5 = eine alle zwei Sekunden) |
| Auf einmal | wie viele direkt hintereinander gehen, bevor die Grenze greift; leer: so viele wie pro Sekunde |
| Bei Verstoß, Mail | wie oben |

Jeder Client hat einen Vorrat von „Auf einmal“ Nachrichten, der sich mit „Pro Sekunde“ wieder auffüllt. Jede Nachricht nimmt
eine heraus; ist der Vorrat leer, verwirft der Broker. Für einen Client gilt nur die **erste** Grenze in der Liste, die zu ihm
passt. So kannst du oben eine großzügige Grenze für bestimmte Geräte setzen und unten eine strenge für alle anderen.

Eine Grenze zählt jede Nachricht des Clients, auch solche, die danach eine Inhaltsregel verwirft.

## Was bei einem Verstoß passiert

- Die Nachricht kommt bei niemandem an, auch nicht als retained Nachricht.
- **MQTT 5:** Der Client bekommt bei QoS 1 und 2 den Grund in seiner Bestätigung, *Not authorized* (Inhaltsregel) oder *Quota
  exceeded* (Mengengrenze). `mosquitto_pub` zeigt das als „Publish failed: Quota exceeded“.
- **MQTT 3.1.1:** Der Client merkt nichts, die Nachricht ist einfach weg.
- **Verwerfen und trennen:** Zusätzlich trennt der Broker den Client innerhalb einer Sekunde („administrative action“ im Log
  des Brokers). Er kann sich sofort wieder verbinden; dauerhaft draußen hält ihn nur eine Sperre oder *Inaktiv* auf der Seite
  Clients.
- Der Zähler der Regel geht hoch, mit Zeit, Client und Grund der letzten verworfenen Nachricht. Auf der Seite **Sitzungen**
  steht bei dem Client „N verworfen“, und in einer **Aufzeichnung** erscheint das Ereignis *Verworfen (Regel)* mit Regel, Grund
  und Inhalt.
- Mit *Mail schicken* geht eine Mail an die Empfänger der Seite **Benachrichtigungen** (Art „Nachrichtenregel greift“, dort
  abschaltbar). mosquitto-ui schaut alle 30 Sekunden nach und schickt pro Regel höchstens eine Mail pro Stunde. Was verworfen
  wurde, bevor die UI lief, meldet sie nicht.

Gründe, wie sie in Zählern, Aufzeichnung und Mail stehen:

| Grund | Bedeutung |
|---|---|
| `not valid JSON` | die Nachricht ist kein JSON |
| `N bytes, more than M` | größer als die Höchstgröße |
| `path is missing` | ein Pflichtfeld fehlt |
| `path is not of type T` | ein Pflichtfeld hat den falschen Typ |
| `more than N messages per second` | die Mengengrenze ist erreicht |

## Ausnahmen und Grenzen

- **Die Benutzer der UI werden nie geprüft:** ihr Broker-Benutzer (meist `admin`), `mosquitto-ui-monitor` (Seite **Topics**)
  und die kurzlebigen `mosquitto-ui-import-…`, die beim Import und beim Wiederherstellen eines Backups retained Nachrichten
  senden. Sonst würde ein Backup an einer Mengengrenze hängen bleiben. Was du auf der Seite **Topics** sendest, prüft der Broker
  also nicht. Gib deshalb keinem Gerät einen Benutzernamen, der mit `mosquitto-ui-` beginnt, auch nicht über einen Token.
- **Bridges** sind Clients wie alle anderen: Eine Grenze für `*` gilt auch für alles, was eine Bridge von einem anderen Broker
  bringt. Für eine Bridge, die viel bringt, vorher eine eigene Grenze mit ihrer Client-ID eintragen.
- `$CONTROL`-Befehle (etwa der UI an Dynamic Security) und `$SYS` werden nicht geprüft.
- Die Zähler beginnen mit jedem Start des Brokers bei 0. Wird eine Regel geändert, zählt sie weiter; eine gelöschte und neu
  angelegte beginnt bei 0.
- Höchstens 200 Regeln jeder Art, 32 Felder pro Regel. JSON wird pro Nachricht einmal gelesen, auch wenn mehrere Regeln passen.
  Für Broker mit sehr vielen Nachrichten die Topic-Filter eng halten.

## Speichern, Datei, Backup

- Die Regeln liegen in `message-rules.json` im Statusverzeichnis der UI. Für den Broker schreibt die UI nur die aktiven
  Regeln nach `rules/rules.json` im gemeinsamen Verzeichnis; der Broker liest die Datei innerhalb einer Sekunde neu, ohne
  Neustart. Kann er sie nicht lesen, behält er die Regeln davor, und die Seite zeigt „Der Broker kann die Regeln nicht lesen“
  mit dem Grund.
- Die Regeln gehören zum Backup-Teil **Zugriff** (`access/message-rules.json`). Ein älteres Backup ohne Regeln lässt die Regeln
  beim Wiederherstellen, wie sie sind.

## API

| Aufruf | Zweck |
|---|---|
| `GET /api/rules` | alle Regeln: `{ "content": [...], "rate": [...] }` |
| `PUT /api/rules` | alle Regeln ersetzen; eine Regel ohne `id` bekommt eine neue |
| `GET /api/rules/status` | `available`, `loaded`, `error` und pro Regel `dropped`, `lastAt`, `lastClient`, `lastReason` |

Beispiel:

```json
{
  "content": [
    { "name": "Shelly-Status", "topic": "shelly/+/+/+/status/switch:+", "clients": null, "json": true, "maxBytes": 4096,
      "fields": [ { "path": "output", "type": "boolean" }, { "path": "aenergy.total", "type": "number" } ],
      "action": "drop", "alert": true, "enabled": true }
  ],
  "rate": [
    { "name": "Sensoren", "clients": "sensor-*", "perSecond": 20, "burst": 50, "action": "drop", "alert": false, "enabled": true }
  ]
}
```

Direkt am Broker: `{"commands":[{"command":"ruleStats"}]}` auf `$CONTROL/inspect/v1`.
