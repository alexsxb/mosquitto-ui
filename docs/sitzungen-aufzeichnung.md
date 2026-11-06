# Sitzungen und Aufzeichnung

Zwei Seiten unter **Übersicht**, die in den Broker hineinschauen: **Sitzungen** zeigt, was jeder Client gerade tut und
abonniert hat, **Aufzeichnung** schneidet für eine Weile mit, was bestimmte Clients tun. Beides liefert das Plugin
`docker/mosquitto/inspect/inspect.c`, das im Broker-Image steckt. Gilt ab mosquitto-ui 0.21.0.

## Voraussetzungen

- **Broker-Image ab 0.21.0.** Die `mosquitto.conf` des Images lädt das Plugin als erstes:

  ```
  plugin /usr/lib/mosquitto_inspect.so
  ```

  Es muss vor der Token-Anmeldung und Dynamic Security stehen, damit es jede ACL-Prüfung sieht (es entscheidet selbst keine).
  Wer eine eigene `mosquitto.conf` nutzt, trägt die Zeile dort oben ein.
- **Rechte des Broker-Benutzers der UI** auf `$CONTROL/inspect/#`, zum Senden (`publishClientSend`) und Abonnieren
  (`subscribePattern`). Der Standardbenutzer `admin` hat sie über die Rolle `super-admin` (`$CONTROL/#`). Ein eigener
  UI-Benutzer, der nur `dynsec-admin` hat, braucht eine Rolle mit diesen beiden Regeln.

Fehlt etwas, zeigen beide Seiten statt der Daten einen Hinweis:

| Hinweis | Ursache |
|---|---|
| Keine Verbindung zum Broker | Die UI ist nicht mit dem Broker verbunden. |
| Plugin nicht geladen | Kein `$SYS/broker/inspect/version` vom Broker: altes Image oder Zeile fehlt in `mosquitto.conf`. |
| Darf `$CONTROL/inspect/#` nicht nutzen | Der Broker hat das Abo auf die Antworten abgelehnt, siehe Rechte oben. |

## Sitzungen

Jede Sitzung, die der Broker hat, eine Zeile, die verbundenen zuerst. Auch Sitzungen, die der Broker für einen gerade nicht
verbundenen Client aufhebt (persistente Sitzung, „Clean Start“ aus), stehen da.

| Spalte | Inhalt |
|---|---|
| Client | Client-ID, Benutzername; *Token* bei Anmeldung mit Access-Token, *diese UI* für die Verbindungen von mosquitto-ui |
| Status | „seit …“ (verbunden), „weg seit …“ (Sitzung bleibt), oder „aufgehoben, seit Broker-Start nicht da“; darunter „Sitzung bleibt erhalten“ bei persistenter Sitzung |
| Verbindung | Adresse, MQTT-Version (3.1, 3.1.1, 5), WebSockets, Keepalive |
| Abos | Anzahl, wie der Broker sie zählt; „N von früher“, siehe unten |
| Nachrichten | ↑ vom Client gesendet, ↓ an ihn zugestellt, Anzahl und Bytes; zuletzt wann steht im Tooltip und in den Details |

Der Pfeil links klappt die Details auf: die Abos mit QoS und den MQTT-5-Optionen (*No Local*, *Retain as Published*,
Subscription-ID), auf dem Handy auch Verbindung und Nachrichten. Der Knopf rechts öffnet die **Aufzeichnung** mit dieser
Client-ID.

Suche (Client-ID, Benutzer, Adresse, Topic eines Abos) und Filter *Verbunden* / *Nicht verbunden* wirken auf die Tabelle. Die
Seite lädt alle 5 Sekunden neu.

### Was die Zahlen bedeuten und was nicht

- **Alles zählt seit dem Start des Brokers.** Das Plugin merkt sich nichts über einen Neustart. Nach einem Neustart stehen
  Nachrichten wieder auf 0, und eine aufgehobene Sitzung zeigt „seit Broker-Start nicht da“, bis der Client kommt.
- **Abos von vor dem Start.** Mosquitto sagt Plugins nur, *wie viele* Abos ein Client hat, nicht *welche*. Das Plugin sieht
  jedes Abonnieren und Abbestellen und baut die Liste daraus. Eine persistente Sitzung, die der Broker beim Start aus seiner
  Datenbank lädt, hat Abos, die das Plugin nie gesehen hat: Sie erscheinen als „N von früher“, bis der Client sie neu
  abonniert. Die Zahl selbst stimmt immer.
- **Gesendet** zählt nur Nachrichten, die der Broker angenommen hat. Was die ACLs ablehnen, steht nur in einer Aufzeichnung.
  `$CONTROL`-Befehle (etwa der UI an Dynamic Security) zählen nicht.
- **Zugestellt** zählt jede Nachricht, die an den Client hinausgeht, auch retained Nachrichten nach einem Abo und `$SYS`.
- Die eigenen Topics des Plugins (`$CONTROL/inspect/…`) werden weder gezählt noch aufgezeichnet.

## Aufzeichnung

Der Broker schreibt eine Weile mit, was Clients tun, und schickt es etwa jede Sekunde an die UI. Es läuft immer nur eine
Aufzeichnung; eine neue beendet die laufende.

### Starten

| Feld | Bedeutung |
|---|---|
| Client-ID | genau diese, oder mit `*` am Ende alle, die so beginnen (`sensor-*`). Ein `*` mitten drin ist nicht erlaubt. Leer: alle Clients. |
| Topic-Filter | nur Nachrichten und Abos, die zu diesem MQTT-Filter passen (`sensors/#`, `+/status`). Leer: alle. |
| Dauer | 1 bis 60 Minuten |
| Höchstens Ereignisse | 1 bis 50000; danach hört die Aufzeichnung auf („Höchstzahl erreicht“) |
| Bytes der Nachricht | wie viel vom Inhalt jeder Nachricht mitkommt, 0 bis 4096 |

Mindestens Client-ID oder Topic-Filter muss gesetzt sein. Verbinden und Trennen werden nur aufgezeichnet, wenn eine Client-ID
gesetzt ist; mit einem Topic-Filter allein käme sonst jeder Client, der sich verbindet.

### Ereignisse

| Ereignis | Was passiert ist | Details |
|---|---|---|
| Verbunden | Client hat sich angemeldet | Benutzer, Adresse, MQTT-Version, Keepalive, ob die Sitzung bleibt |
| Weg, Sitzung bleibt | Verbindung zu, die Sitzung hebt der Broker auf | Grund |
| Getrennt | Verbindung zu, Sitzung beendet | Grund |
| Abonniert | Abo angenommen | Topic-Filter, QoS |
| Abo verweigert | ACL hat das Abo abgelehnt | Topic-Filter, QoS |
| Abo beendet | Abbestellt | Topic-Filter |
| Gesendet | Client hat eine Nachricht geschickt, der Broker hat sie angenommen | Topic, QoS, retain, Größe, Inhalt |
| Senden verweigert | ACL hat die Nachricht abgelehnt | Topic, QoS, retain, Größe |
| Verworfen (Regel) | eine [Nachrichtenregel](nachrichtenregeln.md) hat die Nachricht verworfen | Regel, Grund, Topic, Größe, Inhalt |
| Zugestellt | Broker hat dem Client eine Nachricht geschickt | Topic, QoS, retain, Größe, Inhalt |

Gründe beim Trennen sind die von Mosquitto, etwa *disconnected* (sauber abgemeldet), *connection lost* (Verbindung
abgerissen, oft auch bei Clients, die einfach den Socket schließen), *keep alive timeout*, *session taken over* (ein anderer
Client mit derselben ID), *disconnected by the broker*.

Der Inhalt erscheint als Text, wenn er gültiges UTF-8 ist, sonst als Hex in Grau; „…“ heißt abgeschnitten.

### Ansehen

Die Ereignisse kommen, während die Aufzeichnung läuft, alle 1,5 Sekunden dazu, die neuesten oben. Suche (Client-ID, Topic,
Benutzer) und die Auswahl *Nachrichten*, *Verbinden und Trennen*, *Abos*, *Verweigert* filtern. **Anhalten** beendet sie
vorzeitig, **JSON** lädt die ganze Aufzeichnung mit allen Ereignissen herunter, wahlweise:

- **Inhalt als Text:** Jeder Inhalt, der gültiges UTF-8 ist, steht als Text in `payload` (`"payloadEncoding": "utf-8"`), etwa
  das JSON eines Shelly als String. Binäre Inhalte bleiben base64 (`"payloadEncoding": "base64"`).
- **Inhalt als base64:** alle Inhalte so, wie der Broker sie schickt (`"payloadEncoding": "base64"`). Zum Lesen im Terminal:
  `jq -r '.events[] | select(.payload) | .payload | @base64d' trace-….json`

Ein abgeschnittener Inhalt (`"truncated": true`) kann mitten in einem UTF-8-Zeichen enden und bleibt dann base64.

Die letzten fünf Aufzeichnungen bleiben in der UI, im Speicher: Ein Neustart der UI löscht sie. Startet der Broker während
einer Aufzeichnung neu, kommt kein Ende mehr; die UI markiert sie 15 Sekunden nach ihrer Endzeit als „Ende nicht gemeldet“.

### Wie „verweigert“ erkannt wird

Mosquitto sagt Plugins nicht, wie eine ACL-Prüfung ausging. Das Plugin sitzt deshalb vor allen anderen, sieht jeden Versuch zu
senden oder zu abonnieren, lässt die anderen entscheiden, und merkt sich den Versuch. Folgt im selben Durchgang keine
angenommene Nachricht bzw. kein Abo, war er verweigert. Das gilt nur während einer Aufzeichnung und nur für Clients, die sie
erfasst.

## Sicherheit

- Eine Aufzeichnung schickt Inhalte fremder Nachrichten an die UI. Sie gehen auf `$CONTROL/inspect/v1/trace`, das nur
  bekommt, wer `$CONTROL/inspect/#` lesen darf. Dieses Topic niemandem außer dem Benutzer der UI geben; `super-admin` hat es
  über `$CONTROL/#` ohnehin.
- Wer eine Aufzeichnung startet und wofür, steht im **Protokoll** der UI und im Log des Brokers
  (`inspect: trace … started by alex for client sensor-*, topic #, 300 s`).
- Die Liste der Sitzungen enthält Adressen der Clients und geht ebenfalls nur über `$CONTROL/inspect/v1/response`.

## Last

Ohne laufende Aufzeichnung zählt das Plugin pro Nachricht einen Zähler beim Sender und bei jedem Empfänger hoch und sieht kurz
in eine Tabelle; jede ACL-Prüfung gibt es sofort weiter. Mit Aufzeichnung kommt pro passendem Ereignis ein kleines JSON-Objekt
dazu, das einmal pro Sekunde gebündelt verschickt wird (höchstens 500 je Nachricht). Für Broker mit sehr vielen Nachrichten
pro Sekunde die Aufzeichnung eng fassen: eine Client-ID statt aller, ein Topic-Filter, wenig Bytes.

## API

| Aufruf | Zweck |
|---|---|
| `GET /api/inspect/status` | `available`, sonst `reason`: `broker`, `plugin` oder `rights` |
| `GET /api/inspect/sessions` | alle Sitzungen |
| `GET /api/inspect/traces` | die aufgehobenen Aufzeichnungen, neueste zuerst |
| `POST /api/inspect/traces` | starten: `{ "clientid": "sensor-*", "topic": null, "seconds": 300, "maxEvents": 5000, "payloadBytes": 256 }` |
| `GET /api/inspect/traces/{id}?after=N` | Aufzeichnung mit den Ereignissen ab Index N und `total` |
| `POST /api/inspect/traces/{id}/stop` | anhalten |
| `DELETE /api/inspect/traces/{id}` | löschen (nur beendete) |

Direkt am Broker, etwa mit `mosquitto_pub`/`mosquitto_sub` als `admin`: Befehle auf `$CONTROL/inspect/v1` wie bei Dynamic
Security, `{"commands":[{"command":"listClients"}]}`, Antworten auf `$CONTROL/inspect/v1/response`. Weitere Befehle:
`startTrace` (`id`, `clientid`, `topic`, `seconds`, `maxEvents`, `payloadBytes`, `startedBy`), `stopTrace` (`id`),
`getTrace`. Die Ereignisse kommen auf `$CONTROL/inspect/v1/trace`.
