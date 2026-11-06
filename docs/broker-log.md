# Logs

Die Seite **Logs** (unter **Übersicht**) zeigt das Log des Brokers, live. Bisher stand es nur in `docker logs` auf dem Host.
Gilt ab mosquitto-ui 0.23.0.

## Wie die Zeilen in die UI kommen

Mosquitto veröffentlicht jede Logzeile zusätzlich als Nachricht auf `$SYS/broker/log/<Stufe>`, wenn in der `mosquitto.conf`
`log_dest topic` steht. Die UI abonniert `$SYS/broker/log/#` über ihre Verbindung zum Broker. Kein Plugin, kein Docker-Socket.

- Broker-Image ab 0.23.0. Die `mosquitto.conf` des Images enthält:

  ```
  log_dest stderr
  log_dest topic
  ```

  `log_dest stderr` muss mit dabei stehen: Sobald irgendein `log_dest` gesetzt ist, schreibt Mosquitto nur noch dorthin, und
  `docker logs` bliebe leer. Wer eine eigene `mosquitto.conf` nutzt, übernimmt beide Zeilen. Die Änderung braucht einen Neustart
  des Brokers.
- Der Broker-Benutzer der UI muss `$SYS/broker/log/#` abonnieren dürfen; der Standardbenutzer `admin` darf es. Sonst zeigt die
  Seite „Kein Zugriff auf das Log“.
- Welche Stufen der Broker schreibt, stellst du wie bisher auf der Seite **Konfiguration** ein (`log_type`). Abonnements
  (`subscribe`, `unsubscribe`) erscheinen nur, wenn sie dort an sind. **Debug-Zeilen schickt Mosquitto nie auf ein Topic**, sie
  stehen nur in `docker logs`.

## Die Seite

- Wie `tail -f`: Die UI schickt jede neue Zeile sofort an die Seite (Server-Sent Events auf `/api/logs/stream`, wie die Seite
  Topics), die neueste steht unten, und die Ansicht läuft mit. Oben rechts steht *Live*, *Angehalten* oder *Getrennt*; nach
  einer Unterbrechung verbindet sich die Seite selbst wieder und lädt alle behaltenen Zeilen neu.
- **Hochscrollen** hält das Mitlaufen an, damit eine Zeile nicht davonläuft; neue Zeilen kommen trotzdem unten dazu. **Zum Ende**
  springt zurück und läuft wieder mit.
- **Anhalten** friert die Ansicht ganz ein. Die UI sammelt neue Zeilen im Hintergrund und zeigt, wie viele es sind; **Weiter**
  hängt sie an.
- Angezeigt werden höchstens die letzten 2000 passenden Zeilen, damit die Seite flüssig bleibt; der Download enthält alle.
- Suche über den Text, also auch nach Client-ID, Benutzername oder Adresse.
- **Log eines Clients:** Der Knopf *Log* auf den Seiten **Clients** und **Sitzungen** öffnet die Seite nur mit den Zeilen dieses
  Clients. Die Zeilen nennen meist nur die Client-ID, den Benutzernamen nur die Zeile der Verbindung (`u'…'`). Von **Clients**
  aus zählen deshalb alle Client-IDs, mit denen sich der Benutzer laut Log angemeldet hat, und die gerade verbundenen. Das
  Kennzeichen über der Liste nimmt den Filter wieder weg.
- **Stufen:** Auswahl mit Häkchen, mit der Zahl der Zeilen je Stufe, z. B. nur Fehler und Warnungen. Nichts angehakt heißt alle
  Stufen. Die Wahl merkt sich der Browser.
- Stufen, die der Broker laut `log_type` der Seite **Konfiguration** nicht schreibt, sind in der Auswahl ausgegraut und mit
  *aus* markiert; mit alten Zeilen bleiben sie wählbar. Steht die Log-Stufe auf **Aus** (`log_type none`), warnt die Seite: Dann
  schreibt der Broker gar nichts, auch keine Fehler und Verbindungen. Die UI kennt nur das `log_type` ihrer eigenen Datei, nicht
  eines, das in einer eigenen `mosquitto.conf` steht.
- Auf der Seite **Konfiguration** steht bei der Log-Stufe **Aus** ein roter Hinweis, dass damit das ganze Log aus ist.
- Filter: alle Zeilen, Fehler und Ablehnungen (Stufen Fehler und Warnung, abgelehnte Anmeldungen, Zeitüberschreitungen),
  Verbindungen, Abonnements, Bridges.
- Häufige Zeilen bekommen ein Kennzeichen: *Verbunden*, *Abgelehnt* (`disconnected: not authorised`, falsches Passwort,
  gesperrter oder unbekannter Client), *Getrennt*, *Zeitüberschreitung* (Keep-alive abgelaufen), *Bridge*.
- **Herunterladen** speichert die gerade gefilterten Zeilen als Textdatei.
- Die Zeit ist die, zu der die Zeile bei der UI ankam. Den Zeitstempel, den Mosquitto selbst voranstellt (`log_timestamp`),
  nimmt die UI weg, wenn er mit einer Ziffer beginnt (Sekunden seit 1970 oder ein Format wie `%Y-%m-%dT%H:%M:%S`).

## Mails

Auf der Seite **Benachrichtigungen** gibt es zwei Mails zum Log, beide von Anfang an an:

- **Fehler im Log:** jede Zeile der Stufe Fehler. Höchstens eine Mail pro Stunde (`MOSQUITTO_UI_NOTIFICATIONS_LOG_MAIL_EVERY`),
  darin alle Zeilen seit der letzten (die letzten 20).
- **Viele abgelehnte Anmeldungen:** mindestens 10 (`MOSQUITTO_UI_NOTIFICATIONS_REJECTED_LOGINS`) in 10 Minuten
  (`MOSQUITTO_UI_NOTIFICATIONS_REJECTED_WINDOW`), gezählt aus `disconnected: not authorised`, `bad username or password` und
  `banned`. Mosquitto schreibt bei falschem Passwort, gesperrtem und unbekanntem Client `not authorised`. Die Mail zählt nach
  Client-ID und Adresse. Danach wird neu gezählt, höchstens eine Mail pro Stunde.

Beide sehen nur Zeilen, die der Broker schreibt (`log_type`: Fehler und Hinweis müssen an sein) und die ankommen, während die UI
verbunden ist.

## Was die UI behält

- Die letzten 10 000 Zeilen im Speicher (`MOSQUITTO_UI_BROKER_LOG_KEEP`), und dieselben in `broker-log.jsonl` und
  `broker-log.1.jsonl` im Statusverzeichnis, zusammen höchstens etwa 4 MB (`MOSQUITTO_UI_BROKER_LOG_FILE_BYTES`). So ist das Log
  nach einem Neustart der UI noch da. Die Dateien sind nur für den Benutzer der UI lesbar und gehören nicht zum Backup.
- Was der Broker schreibt, während die UI nicht mit ihm verbunden ist, fehlt, also auch die ersten Zeilen nach einem Start des
  Brokers, bevor die UI wieder verbunden ist. Die stehen in `docker logs`.

## Wer die Zeilen noch bekommt

- Logzeilen enthalten Client-IDs, Benutzernamen und Adressen. Jeder Client, der `$SYS/#` oder `$SYS/broker/log/#` lesen darf,
  bekommt sie auch, etwa der MQTT Explorer mit dem Benutzer `admin`. Geräten keine Rechte auf `$SYS/#` geben.
- Die Seite **Topics** zeigt `$SYS/broker/log/…` nicht: Ihr Benutzer `mosquitto-ui-monitor` bekommt diese Nachrichten nicht.
- Nachrichtenregeln gelten nicht für das Log, es kommt vom Broker selbst.

## API

| Aufruf | Zweck |
|---|---|
| `GET /api/logs` | alle behaltenen Zeilen: `{ run, lines: [ { id, time, level, text } ], last, connected, readable, keep }` |
| `GET /api/logs?run=…&after=N` | nur die Zeilen nach der mit der id `N`. `run` ändert sich mit jedem Start der UI; passt es nicht, kommen alle |
| `GET /api/logs/stream` | Server-Sent Events: zuerst alle behaltenen Zeilen (`reset: true` im ersten Ereignis), dann jede neue, höchstens viermal pro Sekunde gesammelt; alle 15 Sekunden ein leeres Ereignis |

Stufen: `error`, `warning`, `notice`, `info`, `subscribe`, `unsubscribe`, `websockets`.
