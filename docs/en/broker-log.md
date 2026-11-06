# Logs

The page **Logs** (under **Overview**) shows the log of the broker, live. Before, it was only in `docker logs` on the host.
Applies from mosquitto-ui 0.23.0 on.

## How the lines get into the UI

Mosquitto also publishes every log line as a message on `$SYS/broker/log/<level>` when the `mosquitto.conf` has
`log_dest topic`. The UI subscribes to `$SYS/broker/log/#` over its connection to the broker. No plugin, no Docker socket.

- Broker image from 0.23.0 on. The `mosquitto.conf` of the image has:

  ```
  log_dest stderr
  log_dest topic
  ```

  `log_dest stderr` has to be there too: as soon as any `log_dest` is set, Mosquitto writes only there, and
  `docker logs` would stay empty. If you use a `mosquitto.conf` of your own, add both lines. The change needs a restart
  of the broker.
- The broker user of the UI must be allowed to subscribe to `$SYS/broker/log/#`; the default user `admin` is. Otherwise the
  page shows "No access to the log".
- Which levels the broker writes is set on the page **Configuration**, as before (`log_type`). Subscriptions
  (`subscribe`, `unsubscribe`) only show when they are on there. **Mosquitto never sends debug lines to a topic**, they
  are only in `docker logs`.

## The page

- Like `tail -f`: the UI sends every new line to the page at once (server-sent events on `/api/logs/stream`, like the page
  Topics), the newest is at the bottom, and the view follows. At the top right it says *Live*, *Paused* or *Disconnected*; after
  an interruption the page reconnects by itself and loads all kept lines again.
- **Scrolling up** stops following, so that a line does not run away; new lines still come in at the bottom. **To the end**
  jumps back and follows again.
- **Pause** freezes the view completely. The UI collects new lines in the background and shows how many there are; **Resume**
  appends them.
- At most the last 2000 matching lines are shown, so that the page stays smooth; the download has all of them.
- The search goes over the text, so also for a client ID, user name or address.
- **Log of a client:** The button *Log* on the pages **Clients** and **Sessions** opens the page with only the lines of that
  client. The lines mostly name only the client ID, and only the line of the connection names the user name (`u'…'`). From **Clients**
  therefore all client IDs count with which the user logged in according to the log, and those connected right now. The
  mark above the list removes the filter again.
- **Levels:** a choice with checkboxes, with the number of lines per level, e.g. only errors and warnings. Nothing checked means all
  levels. The browser remembers the choice.
- Levels that the broker does not write according to `log_type` on the page **Configuration** are greyed out in the choice and marked
  *off*; with old lines they can still be chosen. If the log level is **Off** (`log_type none`), the page warns: then
  the broker writes nothing at all, not even errors and connections. The UI only knows the `log_type` of its own file, not
  one that is in a `mosquitto.conf` of your own.
- On the page **Configuration**, the log level **Off** shows a red note that this turns off the whole log.
- Filters: all lines, errors and refusals (levels error and warning, refused logins, timeouts),
  connections, subscriptions, bridges.
- Frequent lines get a mark: *Connected*, *Refused* (`disconnected: not authorised`, wrong password,
  locked or unknown client), *Disconnected*, *Timeout* (keep alive expired), *Bridge*.
- **Download** saves the lines of the current filter as a text file.
- The time is when the line reached the UI. The timestamp that Mosquitto itself puts in front (`log_timestamp`)
  is removed by the UI when it begins with a digit (seconds since 1970 or a format like `%Y-%m-%dT%H:%M:%S`).

## Mails

On the page **Notifications** there are two mails about the log, both on from the start:

- **Error in the log:** every line of the level error. At most one mail an hour (`MOSQUITTO_UI_NOTIFICATIONS_LOG_MAIL_EVERY`),
  with all lines since the last one (the last 20).
- **Many rejected logins:** at least 10 (`MOSQUITTO_UI_NOTIFICATIONS_REJECTED_LOGINS`) in 10 minutes
  (`MOSQUITTO_UI_NOTIFICATIONS_REJECTED_WINDOW`), counted from `disconnected: not authorised`, `bad username or password` and
  `banned`. Mosquitto writes `not authorised` for a wrong password, a locked and an unknown client. The mail counts by
  client ID and address. After that the count starts again, at most one mail an hour.

Both only see lines that the broker writes (`log_type`: error and notice must be on) and that come in while the UI
is connected.

## What the UI keeps

- The last 10 000 lines in memory (`MOSQUITTO_UI_BROKER_LOG_KEEP`), and the same in `broker-log.jsonl` and
  `broker-log.1.jsonl` in the state directory, together at most about 4 MB (`MOSQUITTO_UI_BROKER_LOG_FILE_BYTES`). So the log
  is still there after a restart of the UI. The files are readable only by the user of the UI and are not part of the backup.
- What the broker writes while the UI is not connected to it is missing, so also the first lines after a start of the
  broker, before the UI is connected again. Those are in `docker logs`.

## Who else gets the lines

- Log lines contain client IDs, user names and addresses. Every client that may read `$SYS/#` or `$SYS/broker/log/#`
  gets them too, for example MQTT Explorer with the user `admin`. Do not give devices rights on `$SYS/#`.
- The page **Topics** does not show `$SYS/broker/log/…`: its user `mosquitto-ui-monitor` does not get these messages.
- Message rules do not apply to the log, it comes from the broker itself.

## API

| Call | Purpose |
|---|---|
| `GET /api/logs` | all kept lines: `{ run, lines: [ { id, time, level, text } ], last, connected, readable, keep }` |
| `GET /api/logs?run=…&after=N` | only the lines after the one with the id `N`. `run` changes with every start of the UI; if it does not match, all come |
| `GET /api/logs/stream` | server-sent events: first all kept lines (`reset: true` in the first event), then each new one, collected at most four times a second; an empty event every 15 seconds |

Levels: `error`, `warning`, `notice`, `info`, `subscribe`, `unsubscribe`, `websockets`.
