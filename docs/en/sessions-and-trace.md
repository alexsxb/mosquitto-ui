# Sessions and trace

Two pages under **Overview** that look into the broker: **Sessions** shows what each client is doing right now and has
subscribed to, **Trace** records for a while what certain clients do. Both come from the plugin
`docker/mosquitto/inspect/inspect.c`, which is part of the broker image. Applies from mosquitto-ui 0.21.0 on.

## Requirements

- **Broker image from 0.21.0 on.** The `mosquitto.conf` of the image loads the plugin first:

  ```
  plugin /usr/lib/mosquitto_inspect.so
  ```

  It must come before the token login and dynamic security, so that it sees every ACL check (it decides none itself).
  If you use a `mosquitto.conf` of your own, add the line at the top there.
- **Rights of the broker user of the UI** on `$CONTROL/inspect/#`, to send (`publishClientSend`) and subscribe
  (`subscribePattern`). The default user `admin` has them through the role `super-admin` (`$CONTROL/#`). A UI user
  of your own that only has `dynsec-admin` needs a role with these two rules.

If something is missing, both pages show a note instead of the data:

| Note | Cause |
|---|---|
| Not connected to the broker | The UI is not connected to the broker. |
| Plugin not loaded | No `$SYS/broker/inspect/version` from the broker: old image, or the line is missing in `mosquitto.conf`. |
| May not use `$CONTROL/inspect/#` | The broker refused the subscription to the responses, see rights above. |

## Sessions

One row for each session the broker has, the connected ones first. Sessions that the broker keeps for a client that is not
connected right now (persistent session, "Clean Start" off) are listed too.

| Column | Content |
|---|---|
| Client | client ID, user name; *Token* for a login with an access token, *this UI* for the connections of mosquitto-ui |
| Status | "since …" (connected), "gone since …" (session is kept), or "kept, not here since the broker started"; below it "session is kept" for a persistent session |
| Connection | address, MQTT version (3.1, 3.1.1, 5), WebSockets, keep alive |
| Subscriptions | number, as the broker counts them; "N from before", see below |
| Messages | ↑ sent by the client, ↓ delivered to it, number and bytes; when last is in the tooltip and in the details |

The arrow on the left opens the details: the subscriptions with QoS and the MQTT 5 options (*No Local*, *Retain as Published*,
subscription ID), on a phone also connection and messages. The button on the right opens the **Trace** with this
client ID.

The search (client ID, user, address, topic of a subscription) and the filter *Connected* / *Not connected* work on the table. The
page reloads every 5 seconds.

### What the numbers mean and what they do not

- **Everything counts from the start of the broker.** The plugin remembers nothing across a restart. After a restart,
  messages are back at 0, and a kept session shows "not here since the broker started" until the client comes.
- **Subscriptions from before the start.** Mosquitto only tells plugins *how many* subscriptions a client has, not *which*. The plugin sees
  every subscribe and unsubscribe and builds the list from that. A persistent session that the broker loads from its
  database at the start has subscriptions the plugin has never seen: they appear as "N from before" until the client subscribes
  to them again. The number itself is always right.
- **Sent** counts only messages the broker accepted. What the ACLs refuse is only in a trace.
  `$CONTROL` commands (for example those of the UI to dynamic security) do not count.
- **Delivered** counts every message that goes out to the client, also retained messages after a subscription and `$SYS`.
- The plugin's own topics (`$CONTROL/inspect/…`) are neither counted nor recorded.

## Trace

The broker records for a while what clients do, and sends it to the UI about every second. Only one
trace runs at a time; a new one ends the running one.

### Starting

| Field | Meaning |
|---|---|
| Client ID | exactly this one, or with `*` at the end all that begin like this (`sensor-*`). A `*` in the middle is not allowed. Empty: all clients. |
| Topic filter | only messages and subscriptions that match this MQTT filter (`sensors/#`, `+/status`). Empty: all. |
| Duration | 1 to 60 minutes |
| At most events | 1 to 50000; after that the trace stops ("Limit reached") |
| Bytes of the message | how much of the payload of each message comes along, 0 to 4096 |

At least a client ID or a topic filter must be set. Connect and disconnect are only recorded when a client ID
is set; with a topic filter alone, every client that connects would come along.

### Events

| Event | What happened | Details |
|---|---|---|
| Connected | client logged in | user, address, MQTT version, keep alive, whether the session is kept |
| Gone, session kept | connection closed, the broker keeps the session | reason |
| Disconnected | connection closed, session ended | reason |
| Subscribed | subscription accepted | topic filter, QoS |
| Subscription refused | ACL refused the subscription | topic filter, QoS |
| Unsubscribed | unsubscribed | topic filter |
| Sent | client sent a message, the broker accepted it | topic, QoS, retain, size, payload |
| Sending refused | ACL refused the message | topic, QoS, retain, size |
| Dropped (rule) | a [message rule](message-rules.md) dropped the message | rule, reason, topic, size, payload |
| Delivered | broker sent the client a message | topic, QoS, retain, size, payload |

The reasons for a disconnect are those of Mosquitto, for example *disconnected* (logged out cleanly), *connection lost* (connection
broke off, often also with clients that simply close the socket), *keep alive timeout*, *session taken over* (another
client with the same ID), *disconnected by the broker*.

The payload shows as text when it is valid UTF-8, otherwise as hex in grey; "…" means cut off.

### Viewing

While the trace runs, the events come in every 1.5 seconds, the newest at the top. The search (client ID, topic,
user) and the choice *Messages*, *Connect and disconnect*, *Subscriptions*, *Refused* filter them. **Stop** ends it
early, **JSON** downloads the whole trace with all events, either:

- **Payload as text:** every payload that is valid UTF-8 is text in `payload` (`"payloadEncoding": "utf-8"`), for example
  the JSON of a Shelly as a string. Binary payloads stay base64 (`"payloadEncoding": "base64"`).
- **Payload as base64:** all payloads as the broker sends them (`"payloadEncoding": "base64"`). To read them in a terminal:
  `jq -r '.events[] | select(.payload) | .payload | @base64d' trace-….json`

A payload that was cut off (`"truncated": true`) can end in the middle of a UTF-8 character and then stays base64.

The last five traces stay in the UI, in memory: a restart of the UI deletes them. If the broker restarts during
a trace, no end comes any more; the UI marks it as "No end reported" 15 seconds after its end time.

### How "refused" is detected

Mosquitto does not tell plugins how an ACL check turned out. So the plugin sits in front of all others, sees every attempt to
send or subscribe, lets the others decide, and remembers the attempt. If no accepted message or subscription
follows in the same pass, it was refused. This applies only during a trace and only to clients it
covers.

## Security

- A trace sends the payloads of other clients' messages to the UI. They go to `$CONTROL/inspect/v1/trace`, which only
  someone who may read `$CONTROL/inspect/#` gets. Give this topic to nobody but the user of the UI; `super-admin` has it
  through `$CONTROL/#` anyway.
- Who starts a trace and for what is in the **Audit log** of the UI and in the log of the broker
  (`inspect: trace … started by alex for client sensor-*, topic #, 300 s`).
- The list of sessions contains addresses of the clients and likewise only goes over `$CONTROL/inspect/v1/response`.

## Load

Without a running trace, the plugin increments a counter at the sender and at each recipient per message and takes a short look
into a table; it passes every ACL check on at once. With a trace, a small JSON object is added per matching event,
which is sent bundled once a second (at most 500 per message). For brokers with very many messages
a second, keep the trace narrow: one client ID instead of all, a topic filter, few bytes.

## API

| Call | Purpose |
|---|---|
| `GET /api/inspect/status` | `available`, otherwise `reason`: `broker`, `plugin` or `rights` |
| `GET /api/inspect/sessions` | all sessions |
| `GET /api/inspect/traces` | the kept traces, newest first |
| `POST /api/inspect/traces` | start: `{ "clientid": "sensor-*", "topic": null, "seconds": 300, "maxEvents": 5000, "payloadBytes": 256 }` |
| `GET /api/inspect/traces/{id}?after=N` | trace with the events from index N on and `total` |
| `POST /api/inspect/traces/{id}/stop` | stop |
| `DELETE /api/inspect/traces/{id}` | delete (only ended ones) |

Directly at the broker, for example with `mosquitto_pub`/`mosquitto_sub` as `admin`: commands on `$CONTROL/inspect/v1` as with dynamic
security, `{"commands":[{"command":"listClients"}]}`, responses on `$CONTROL/inspect/v1/response`. More commands:
`startTrace` (`id`, `clientid`, `topic`, `seconds`, `maxEvents`, `payloadBytes`, `startedBy`), `stopTrace` (`id`),
`getTrace`. The events come on `$CONTROL/inspect/v1/trace`.
