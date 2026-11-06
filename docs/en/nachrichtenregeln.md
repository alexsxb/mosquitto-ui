# Message rules

On the page **Message rules** (under **Broker**) you set what messages must look like and how many a client
may send. The broker checks every message a client sends, before anyone gets it, and drops what does not
fit. This is done by the plugin `docker/mosquitto/inspect` (file `rules.c`), the same one as for
[Sessions and trace](sitzungen-aufzeichnung.md). Applies from mosquitto-ui 0.22.0 on.

## What for

- **Stop broken data:** After a firmware bug, a device sends half JSON messages. Without a rule,
  Home Assistant, Node-RED or the database get the garbage and trip over it.
- **Slow down a device running wild:** A sensor is stuck in a loop and sends thousands of messages a second. Mosquitto
  runs on one core; that slows down all other clients. With a rate limit the damage stays with this one device.

## Requirements

- Broker image from 0.22.0 on. The `mosquitto.conf` of the image has:

  ```
  plugin /usr/lib/mosquitto_inspect.so
  plugin_opt_rules_file /mosquitto/ui/rules/rules.json
  ```

  The plugin must come before all other plugins. If you use a `mosquitto.conf` of your own, add both lines.
- For the counts on the page: the same rights as for the sessions (`$CONTROL/inspect/#`, see there). The rules themselves
  work without these rights too, because the broker reads them from the file.

## Content rules

| Field | Meaning |
|---|---|
| Name | free, only for display |
| Topic filter | which messages the rule applies to, an MQTT filter like `shelly/#` or `+/status/+` |
| Clients | empty or `*`: all clients; with `*` at the end all client IDs that begin like this (`shelly*`); otherwise exactly this client ID |
| Must be valid JSON | the message must be readable as JSON |
| Size limit | larger messages are dropped; empty: no limit |
| Required fields | fields the JSON must have, with a type. The path goes through objects with dots, e.g. `aenergy.total`. Types: number, text, true/false, object, list, `null`, any (only present). Fields need JSON. |
| When broken | *Drop* or *Drop and disconnect* |
| Send a mail | a mail when the rule has dropped messages, at most one an hour |

A rule must check at least one thing: JSON, size limit or fields. A message must hold for **every** content rule
whose topic filter and clients match it; the first one it does not hold for drops it.

Example for the status of a Shelly switch (Gen2 and newer):

| Topic filter | Check |
|---|---|
| `shelly/+/+/+/status/switch:+` | JSON, at most 4096 bytes, `output`: true/false, `apower`: number, `aenergy.total`: number |

## Rate limits

| Field | Meaning |
|---|---|
| Clients | as above |
| Per second | how many messages a second are allowed in the long run, fractions too (0.5 = one every two seconds) |
| At once | how many can go directly one after the other before the limit takes hold; empty: as many as per second |
| When broken, mail | as above |

Each client has a store of "At once" messages that fills up again with "Per second". Every message takes
one out; when the store is empty, the broker drops. For a client only the **first** limit in the list that matches it
counts. So you can put a generous limit for certain devices at the top and a strict one for all others at the bottom.

A limit counts every message of the client, also those that a content rule drops afterwards.

## What happens when a rule is broken

- Nobody gets the message, not as a retained message either.
- **MQTT 5:** With QoS 1 and 2 the client gets the reason in its acknowledgement, *Not authorized* (content rule) or *Quota
  exceeded* (rate limit). `mosquitto_pub` shows this as "Publish failed: Quota exceeded".
- **MQTT 3.1.1:** The client notices nothing, the message is simply gone.
- **Drop and disconnect:** The broker also disconnects the client within a second ("administrative action" in the log
  of the broker). It can connect again at once; only a ban or *Disabled* on the page
  Clients keeps it out for good.
- The count of the rule goes up, with time, client and reason of the last dropped message. On the page **Sessions**
  the client shows "N dropped", and in a **Trace** the event *Dropped (rule)* appears with rule, reason
  and payload.
- With *Send a mail* a mail goes to the recipients of the page **Notifications** (kind "Message rule refused messages",
  can be turned off there). mosquitto-ui looks every 30 seconds and sends at most one mail an hour for each rule. What was dropped
  before the UI was running is not reported.

Reasons, as they appear in counts, trace and mail:

| Reason | Meaning |
|---|---|
| `not valid JSON` | the message is not JSON |
| `N bytes, more than M` | larger than the size limit |
| `path is missing` | a required field is missing |
| `path is not of type T` | a required field has the wrong type |
| `more than N messages per second` | the rate limit is reached |

## Exceptions and limits

- **The users of the UI are never checked:** its broker user (usually `admin`), `mosquitto-ui-monitor` (page **Topics**)
  and the short-lived `mosquitto-ui-import-…`, which send retained messages during an import and when restoring a backup.
  Otherwise a backup would get stuck on a rate limit. So the broker does not check what you send on the page **Topics**.
  For that reason, give no device a user name that begins with `mosquitto-ui-`, not through a token either.
- **Bridges** are clients like all others: a limit for `*` also applies to everything a bridge brings from another broker.
  For a bridge that brings a lot, add a limit of its own with its client ID first.
- `$CONTROL` commands (for example those of the UI to dynamic security) and `$SYS` are not checked.
- The counts start at 0 with every start of the broker. When a rule is changed, it keeps counting; one that is deleted and
  created again starts at 0.
- At most 200 rules of each kind, 32 fields per rule. JSON is read once per message, even when several rules match.
  For brokers with very many messages, keep the topic filters narrow.

## Saving, file, backup

- The rules are in `message-rules.json` in the state directory of the UI. For the broker, the UI writes only the active
  rules to `rules/rules.json` in the shared directory; the broker reads the file again within a second, without a
  restart. If it cannot read it, it keeps the rules from before, and the page shows "The broker cannot read the rules"
  with the reason.
- The rules belong to the backup section **Access** (`access/message-rules.json`). An older backup without rules leaves the rules
  as they are when it is restored.

## API

| Call | Purpose |
|---|---|
| `GET /api/rules` | all rules: `{ "content": [...], "rate": [...] }` |
| `PUT /api/rules` | replace all rules; a rule without an `id` gets a new one |
| `GET /api/rules/status` | `available`, `loaded`, `error` and per rule `dropped`, `lastAt`, `lastClient`, `lastReason` |

Example:

```json
{
  "content": [
    { "name": "Shelly status", "topic": "shelly/+/+/+/status/switch:+", "clients": null, "json": true, "maxBytes": 4096,
      "fields": [ { "path": "output", "type": "boolean" }, { "path": "aenergy.total", "type": "number" } ],
      "action": "drop", "alert": true, "enabled": true }
  ],
  "rate": [
    { "name": "Sensors", "clients": "sensor-*", "perSecond": 20, "burst": 50, "action": "drop", "alert": false, "enabled": true }
  ]
}
```

Directly at the broker: `{"commands":[{"command":"ruleStats"}]}` on `$CONTROL/inspect/v1`.
