# mosquitto-ui

Small web UI for an [Eclipse Mosquitto](https://mosquitto.org/) broker: clients, groups, roles and access rules through
the dynamic security plugin, the broker configuration, its TLS certificate from Let's Encrypt (DNS-01 through acme-dns or
the PowerDNS API, as offered by pdnsadmin), and a migration from `password_file`, `acl_file` and `mosquitto.db`. Login through OpenID Connect. Quarkus backend, React frontend (`src/main/webui`, served through Quinoa).

**Handbook** with screenshots, in [German](docs/handbuch/index.html), [English](docs/handbuch/en/index.html) and
[French](docs/handbuch/fr/index.html). Every installation serves it at `/handbuch/` without a login, and the user menu links the
one in the language of the UI.

## Development

```shell
./mvnw quarkus:dev
```

starts Mosquitto and Keycloak from `docker/compose.yaml` and the app on <http://localhost:8090>. The containers keep
running afterwards; stop them with `docker compose -f docker/compose.yaml stop`.

| Login (Keycloak) | Password       | Access          |
|------------------|----------------|-----------------|
| `kcadmin`        | `kcadmin-pass` | manages broker  |
| `kcuser`         | `kcuser-pass`  | "no access" page |

After changing the API, update the types of the frontend:

```shell
./mvnw package -DskipTests -Dquarkus.quinoa=false && (cd src/main/webui && npm run gen:api)
```

The tests need Docker, they run against a real broker and a real ACME server ([Pebble](https://github.com/letsencrypt/pebble)).

## Deployment

A host needs `deploy/compose.yaml` and a `.env` made from `deploy/env.example`, nothing else of this repository:

```shell
cp env.example .env   # and fill in
docker compose up -d
```

starts the broker and the UI from the images of a release, `mosquitto-ui` and `mosquitto-ui-broker`. The second one is Mosquitto
with the agent and the configuration from `docker/mosquitto`. Both have the same version, because the agent and the UI have to
fit each other. For an update, set `MOSQUITTO_UI_VERSION` in `.env`, then run `docker compose pull && docker compose up -d`. A
new broker image restarts the broker, which disconnects its clients.

For a configuration of your own, mount your file, see the comment in `deploy/compose.yaml`. To build the images from this
repository, add `-f compose.yaml -f compose.build.yaml` and `--build`.

The UI listens on
port 8080 on all addresses and runs read-only as an unprivileged user; put a reverse proxy with TLS in front of it. The broker
publishes 1883 and 8883 directly. `MOSQUITTO_PASSWORD` has to have at least 12 characters, see below.

Besides `backups` for backups to a directory, three volumes hold the data: `data` (users, retained messages, certificate), `state` (settings and account of the
certificate, API token) and `shared` (configuration of the UI). Back up all three.

## Keycloak login theme

`docker/keycloak-theme/mosquitto-ui` gives the login pages of Keycloak the look of the UI, in light and dark and for all flows, as
it only changes the styles of the theme `keycloak.v2`. Copy the directory to `/opt/keycloak/themes/mosquitto-ui` or mount it there,
and choose it for this client alone: *Clients → mosquitto-ui → Settings → Login settings → Login theme*. As a theme of the realm it
would show the name mosquitto-ui to the other applications of the realm as well. Keycloak picks up a new directory after a restart.

## Broker setup

Requires Mosquitto 2.1 (the image tag is e.g. `2.1.2-alpine`). In `mosquitto.conf`:

```
# The pages Sessions, Trace and Message rules; first, so that it sees every ACL check and every message
plugin /usr/lib/mosquitto_inspect.so
plugin_opt_rules_file /mosquitto/ui/rules/rules.json

# Login with an access token, see Token login; before dynamic security
plugin /usr/lib/mosquitto_token_auth.so
plugin_opt_config_file /mosquitto/ui/token-auth/token-auth.json

plugin /usr/lib/mosquitto_dynamic_security.so
plugin_opt_config_file /mosquitto/data/dynamic-security.json

# Retained messages and sessions, written as they change (flushed every 5 seconds)
persistence_location /mosquitto/data
global_plugin /usr/lib/mosquitto_persist_sqlite.so

# Managed by the UI, should stay the last line
include_dir /mosquitto/ui/conf.d
```

This is the configuration in the broker image. The image runs `docker/mosquitto/agent.sh`, which shares a directory with the UI:

```yaml
services:
  mosquitto:
    image: ghcr.io/alexsxb/mosquitto-ui-broker:latest
    restart: unless-stopped
    volumes:
      - mosquitto-ui:/mosquitto/ui
      - mosquitto-data:/mosquitto/data
  mosquitto-ui:
    # the same volume at /mosquitto/ui
    volumes:
      - mosquitto-ui:/mosquitto/ui
```

The agent is what lets the UI test a configuration, reload and restart the broker without the Docker socket. It goes
back to the previous file if the broker refuses a new one. Without it, everything except the configuration page and the
import of users works.

- On the first start, the plugin creates the user `admin` with the password from `MOSQUITTO_DYNSEC_PASSWORD`. It needs
  at least **12 characters**: with a shorter one the broker still starts, but no user exists and every client is rejected.
- `/mosquitto/data` has to be a volume, and belongs in the backup: it holds users and rights
  (`dynamic-security.json`) as well as the retained messages (`mosquitto.sqlite3`).
- `persist_sqlite` and `persistence true` cannot be combined, the broker refuses to start.
- The user of the UI needs the role `dynsec-admin`. Its client and the groups and roles it depends on are locked in the
  UI, so that it cannot lock itself out.

## TLS certificate

On the page *Certificate*, the UI orders a certificate with [acme4j](https://acme4j.shredzone.org/) and renews it in the
last third of its lifetime. Start with the Let's Encrypt staging server, as mistakes count against the rate limits of the
real one. Failed renewals show on the dashboard and in the log.

| API | URL | API token | |
|---|---|---|---|
| acme-dns | `https://dns.example.com/acme-dns` | ACME key or DynDNS host token of pdnsadmin | With a real acme-dns server also user and subdomain of the registration |
| PowerDNS | `https://dns.example.com` | API key, restricted to `_acme-challenge` records | Also PowerDNS itself. Needed for several names that the acme-dns key does not cover |

Besides Let's Encrypt, the page offers ZeroSSL and Google Trust Services, both free. They only take accounts bound to one of
theirs (external account binding): the key ID and HMAC key come from the ZeroSSL dashboard (*Developer*) or from
`gcloud publicca external-account-keys create`. Any other ACME CA works through its directory URL.

Certificates come in sets: domains, CA, DNS API and the certificate belong together, for example one set for staging and one
for production. Only one set is active. The broker uses its certificate, and only this one renews automatically; requesting a
certificate for another set just stores it. The first certificate of a broker becomes active by itself. *Activate* switches
the broker without touching the paths in the configuration: a reload for the same key type and port, which leaves clients
connected, and a restart otherwise. If the broker does not start with the certificate, it goes back to the previous one. An
expired certificate cannot be activated, and the active set cannot be deleted. Settings of a set take effect with its next
certificate, its TLS port when it gets active. An installation from before there were sets turns its certificate into an
active set the first time the page is opened, and the agent keeps its key in that set with the first switch.

A wildcard shares `_acme-challenge.<domain>` with its domain; both APIs handle the two values. The acme-dns protocol cannot
delete, its records stay until the next order.

The new key and certificate go through the agent to `/mosquitto/data/certs/sets/<id>`, readable only by the broker; the UI
cannot read the key back. The active set is copied to `fullchain.pem` and `privkey.pem` next to them, the two paths the
configuration uses. With *Set up the TLS listener automatically* (port 8883 by default) the first certificate adds the listener
to the configuration of the UI, which restarts the broker once and disconnects all clients; deploy/compose.yaml publishes
8883, publish another port yourself. Without it, enter the paths shown on the page for a listener under *Configuration*. A
port that `mosquitto.conf` already listens on is refused, the certificate stays installed. A reload brings a renewed
certificate to new connections and leaves existing clients connected. When the key type changes, the agent restarts the
broker instead: after a reload, OpenSSL would keep serving the old certificate of the other type.

Settings, API tokens and account keys are in `/var/lib/mosquitto-ui`, in files only the application can read.

## Options that need a restart

`mosquitto.conf(5)` marks many options as reloaded, but measured with Mosquitto 2.1.2, `max_packet_size` and
`max_queued_messages` keep their old value after a reload, without any message. So the UI only reloads for options whose effect
was seen: `allow_anonymous`, `certfile`, `keyfile`, `connection_messages`, `log_timestamp`, `log_timestamp_format`, `log_type`,
`sys_interval` and `upgrade_outgoing_qos`. Everything else asks for a restart, which disconnects all clients.

## Bridges

Bridges are kept in `conf.d/mosquitto-ui-bridges.conf`, apart from the configuration text of the UI, because the file holds
their passwords in plain text: Mosquitto reads them that way. The API never returns them, backups of the file are only
readable by the UI. Measured with Mosquitto 2.1.2:

- A reload starts new bridges and stops removed ones. Changes to an existing bridge are ignored by a reload, so the UI asks
  for a restart. Under a new name it is a new bridge and needs none. A new bridge can take about 10 seconds to connect.
- A disabled bridge stays in the file as comments, with its password. Disabling and enabling are a removal and a new bridge
  for the broker, so they need no restart and clients stay connected. A disabled bridge does not count for the check below,
  which runs again when it is enabled.
- Two bridges to the same broker with overlapping topics send messages in an endless circle, tens of thousands per second,
  `try_private` or not. The UI refuses that if it sees the same address. It cannot see two names of one host.
- With `try_private false` and MQTT 3.1.1, a message from a local client on a `both` topic comes back once, as this protocol
  has no way to ask for it not to. MQTT 5 (`bridge_protocol_version mqttv50`) or `try_private true` prevent that. Overlapping
  topics in one bridge multiply messages further. The UI shows both as warnings.
- The status comes from `$SYS/broker/connection/<client id>/state`, it shows as unknown if the broker user of the UI may not
  read it.

### Victron GX devices

*Connect Victron GX* on the page *Bridges* sets up a bridge in one step, read only or two-way. The way there is taken from
Victron's `dbus-flashmq`, `venus-influx-loader` and `victron_mqtt`; it was tested against a stand-in with real TLS and
Mosquitto, not yet against a GX.

- With the Secured profile (Venus OS 3.80 or later) the UI gets a token from `https://<GX>/auth/generate-token/` while pairing
  mode is on, 120 seconds (Settings > Integrations > MQTT Devices). It stores the self-signed certificate that the device
  presents at that moment for `bridge_cafile`, and trusts that one from then on. Host names are not checked
  (`bridge_insecure true`), as the certificate is not made for the address. If *MQTT Access* was off, pairing sets it to
  *Paired devices only*, and clients without credentials need a token too. The token shows on the device, which is also where
  it is removed; deleting the bridge here does not.
- With the Unsecured profile it connects to 1883 without a login.
- The portal ID comes from `N/+/system/0/Serial`, or is entered by hand.
- Read only is `N/#` in. Two-way adds `R/#` and `W/#` out, so anyone who may publish to `W/` on this broker controls the device:
  give that only to clients that should.
- A GX stops publishing 60 seconds after the last keepalive, and a bridge sends none. So the UI sends it, on a connection of its
  own to the device, every 30 seconds. **If the UI is down, values stop arriving after a minute**, the bridge itself stays up.
  This way a read-only bridge has no way back to the device at all.

## Topics

The page *Topics* shows what goes through the broker, as a live tree with the last value, a search over topics and values, and
the details of a topic: JSON, QoS, retained flag, the values since the page was opened, and a chart.

- **Charts** draw plain numbers and the numbers in JSON, a field can be chosen; GX devices start with `value`. They only know what
  the browser saw since the page was opened: 300 values per topic, up to 86,400 for a selected or pinned one. Pinned charts are
  remembered in the browser, their data is not.
- **Publishing** goes out over the connection of the page and needs it to be open. Topics with wildcards and `$` are refused. It
  reaches subscribers and bridges like any message, so `W/...` can control a Victron GX behind a two-way bridge. With MQTT 3.1.1
  the broker cannot report a refused message. The log tells who published to which topic, not what.
- **Deleting retained messages** works on a topic and all below it, after a confirmation with their number. A new subscription
  asks the broker which ones exist; bridges pass the deletion on. It cannot be undone. The badge *Retained* can be missing
  until then: without MQTT 5 the flag only comes with a subscription, not with a message that arrives live.

While someone looks, the UI creates a broker user `mosquitto-ui-monitor` with a random password and a role that may subscribe and
publish on all topics without `$`, because the user of the UI has no access to application topics. It subscribes to `#` and
`$SYS/#`, and removes that user 60 seconds after the last viewer left (`MOSQUITTO_UI_TOPICS_IDLE_STOP`). Everyone with access to
the UI can so read and write all messages. Values are kept in memory only, up to 8 KB per topic and 100,000 topics; browsers get
changes twice a second, so a fast topic shows every value it gets, not every message.

## Releases

Every push and pull request runs the tests (`.github/workflows/build.yml`). A version tag on a commit of `main` runs them
again, builds both images for `linux/amd64` and `linux/arm64`, pushes them to ghcr.io and attaches the tarball for hosts without
Docker to a GitHub release (`.github/workflows/release.yml`); nothing gets deployed:

```sh
git tag v1.2.3 && git push origin v1.2.3
```

The images are `ghcr.io/alexsxb/mosquitto-ui:1.2.3` and `ghcr.io/alexsxb/mosquitto-ui-broker:1.2.3`, each also as `:latest`.
The workflow needs no secrets of its own. `deploy/compose.yaml` takes them from `MOSQUITTO_UI_REGISTRY`, by default
`ghcr.io/alexsxb`.

## Backups

The page *Backups* keeps jobs (what, where, when, how encrypted), targets (SFTP, FTP/FTPS, S3, a directory, e-mail), a run log
and the restore. Jobs that failed or are overdue show on the dashboard, and failures go by mail, see [Notifications](#notifications).
deploy/compose.yaml mounts the volume `backups` at `/backups` for a directory
target.

| Part | Holds | Unencrypted |
|---|---|---|
| Access | Clients, groups and roles with password hashes | no |
| Configuration | Options and listeners of the UI | yes |
| Bridges | Bridges with passwords, certificates of GX devices | no |
| Certificates | All sets with keys, API tokens and account keys | no |
| Retained messages | Topics and values of the running broker | no |
| Audit log | Who changed what | no |
| Backup jobs and targets | Jobs, targets with passwords, settings | no |

`mosquitto.conf` is not part of a backup. Retained messages are read from the running broker, which keeps working. A run fails if
clearly fewer arrive than the broker counts, instead of writing half a backup; the UI user needs `sys-observe` for that check. A restore
publishes them like the import does: with QoS 1, without MQTT 5 properties, and to everyone subscribed. It adds to what the broker holds
and deletes nothing. They come before the bridges, but bridges that already exist pass them on. For a backup, the agent hands the keys of the certificates to
the UI for a moment, in a directory only the UI can read; apart from that the UI cannot read them.

Encrypted backups can be opened without the UI: `openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -in FILE.zip.enc -out
FILE.zip` asks for the password, and `gpg -d FILE.zip.gpg > FILE.zip` is for GPG, whose ZIP then gets uploaded for a restore. Each
fresh archive is read back before it leaves the UI.

A restore replaces the chosen parts and restarts the broker, which disconnects all clients. It runs in a fixed order and stops at
the first part that fails, those before it stay restored. The UI keeps its own broker client as it is now, as the password of an
older one might no longer fit. Archives are signed with the secret key: an archive that is unsigned, changed or from an
installation with another key is only restored after confirming *I trust this file*. A password-encrypted file itself has no
authentication, so the signature is what tells a planted backup. Keep `MOSQUITTO_UI_SECRET_KEY` to move to a new server without
this question.

## Secrets at rest

Targets, jobs, settings, certificate sets with API tokens and account keys are encrypted in the state directory with AES-256-GCM,
as a whole file (`backup.json`, `certificate-sets.json`). The key comes from `MOSQUITTO_UI_SECRET_KEY`, generated with
`openssl rand -base64 32`. Without it, the UI generates `secret.key` in the state directory, which needs no setup, but then whoever
gets the volume gets the key as well. Changing the key makes these files unreadable. Existing plain files get encrypted the first
time they are read. Bridge passwords stay in clear text in `conf.d`, because the broker has to read them.

## Reset

The tab *Reset* under *Configuration* takes chosen parts back to the state of a new installation, after typing `RESET`.
`mosquitto.conf` and retained messages stay.

- **Configuration:** empties the file of the UI, listeners included. The old one stays in `backups`.
- **Bridges:** all of them, with the certificates of GX devices. Tokens stay on the devices. `backups` still has the old
  file with its passwords.
- **Backups:** jobs, targets with their passwords, the run log, the mail server and the settings of the notifications. The backups on the targets stay.
- **Certificate:** all sets with certificate, key, settings, API token and account key, and the TLS listener. The CA is not told, the certificate
  stays valid until it expires. If `mosquitto.conf` still uses the files, the reset stops there and deletes nothing.
- **Clients, groups and roles**, not selected by default: everything except the broker user of the UI, what it depends on, the
  group for anonymous clients and the default roles of the plugin. No device can log in afterwards. The previous state
  stays as `dynamic-security.json.pre-import`.

## Migrating an existing broker

On the page *Import*, upload one or several files, or ZIP files, in any combination. The check tells from the content of each
file what it is, shows a preview, and changes nothing yet. You choose which parts to import. They run in this order, and one
that fails ends the import, with the ones before it done: users and rules, retained messages, configuration. Several password
or ACL or configuration files are read as one, of several `mosquitto.db` only the first is used. ZIP files are unpacked in
memory, up to 500 files and 256 MB, without their folders.

**Configuration** (`mosquitto.conf`, `conf.d/*.conf`): bridges become bridges of the UI, comments in them are lost. Other
options go into the configuration of the UI, if they do not exist there yet. Left out, and listed with the reason, are what
this setup does otherwise: `password_file`, `acl_file`, persistence, plugins, `include_dir`, `log_dest`, `user`, `pid_file`,
and listeners on ports that `mosquitto.conf` or the UI already use. The agent reports the listeners of `mosquitto.conf`, as
`--test-config` lets a second listener on a port pass and only the start of the broker fails.

**Users and rules** (`password_file`, `acl_file`): hashes are taken over unchanged, devices keep their passwords.

- The control topic of the plugin only takes plain text passwords, so the import writes into `dynamic-security.json`
  while the broker is stopped. That takes a few seconds and disconnects all clients. The previous file stays as
  `dynamic-security.json.pre-import` and comes back if the broker does not start.
- `$7$` and `$6$` hashes work. `$argon2id$` does not with the official image, these users are listed as not importable.
- Every user gets a role `imported-<user>`, `pattern` lines a role `imported-patterns` for all imported clients, rules
  before the first `user` a role `imported-anonymous` in the group for anonymous clients. A `deny` gets priority 10.
- Existing clients are not touched. Remove `password_file` and `acl_file` from `mosquitto.conf` afterwards.

**Retained messages** (`mosquitto.db`, format versions 5 and 6, tested with files of 1.6.15, 2.0.22 and 2.1.2): they are
published to the running broker by a temporary client, with QoS 1 and retained. Sessions, subscriptions and queued
messages are not taken over, MQTT 5 properties of the messages are lost. Do not use the `mosquitto.db` of a running
broker: Mosquitto writes it only now and then.

## Notifications

The page *Notifications* holds the mail server, the recipients and what they hear about. Everything is on by default, and each kind
can be turned off:

| Mail | When |
|---|---|
| Broker unreachable | the UI has no connection for `mosquitto-ui.notifications.outage-after` (5 minutes) |
| Bridge disconnected | a bridge that is switched on and reports its state stays down that long |
| Client missing | what the dashboard calls missing, see [Last connections](#last-connections); clients can be left out by name |
| Certificate not renewed | a renewal failed |
| Backup failed | a job failed |
| Message rule | a message rule that says to tell refused messages, at most one mail an hour for each rule |
| Error in the log | the broker logged a line of the level error, see [Logs](#logs) |
| Many rejected logins | at least `mosquitto-ui.notifications.rejected-logins` (10) in `rejected-window` (10 minutes): wrong password, locked or unknown client |

The two about the log come at most once in `mosquitto-ui.notifications.log-mail-every` (1 hour) each, with what came in between.
They only see lines the broker writes (`log_type`, see [Logs](#logs)) while the UI is connected to it.

For the first three, a second mail says when it is over. The UI looks once a minute (`mosquitto-ui.notifications.interval`), and one
look makes one mail however much happened: a network outage does not send one mail per client. A short restart of the broker stays
quiet, and clients get the same time to come back after an outage before anyone counts them as missing. What was reported is kept in
`notifications.json`, so an update of the UI neither repeats a mail nor loses the one about the end. A mail that does not get through
is tried again at the next look.

The UI runs next to the broker: if the whole host goes down, nothing can tell you. That takes a check from outside, for example of
`/q/health`.

Older versions kept the mail server in the settings of the backups. It moves on the first start, and backups of those versions still
bring theirs along. The file is encrypted, and it is part of the section *Backups*.

## Access rules

The page *Rules* answers what a client may do with a topic, for publishing, receiving, subscribing and unsubscribing, and names the
rule that decides. The plugin has no command for this, so the UI works it out from the current rules, in the order of the plugin of
2.1.2, and changes nothing on the broker: roles of the client, then roles of its groups, each by priority; a literal rule of a role
comes before its patterns, whatever the priorities say. `AccessRulesTest` compares the outcome with real clients on a real broker,
for publishing, receiving and subscribing. A new Mosquitto version needs a look at `acl.c` and this test. Bridges are not subject to
these rules.

There too are the default access, for what no rule matches, and the group for clients without a login. A change of the default
access applies to all clients at once and disconnects nobody; turned off, receiving needs a rule in every role, or the clients of
that role go silent without an error. `$CONTROL` is never reached by the default access. The group for anonymous clients can be
replaced but not removed, cannot be deleted while it has this task, and only matters with `allow_anonymous true`. The groups of the
broker user of the UI are refused, as all anonymous clients would get its rights.

*Disconnect* on the page *Clients* closes all connections of a login. The plugin has no command for that either: it disconnects a
client on every change, so the UI sends a change without content. Most devices come right back, a client that should stay out
needs to be set to inactive.

## Token login

How to set up a client in Keycloak and get tokens by hand or with curl: [docs/en/token-login-keycloak.md](docs/en/token-login-keycloak.md) (German: [docs/token-login-keycloak.md](docs/token-login-keycloak.md))
(German).

Besides the users of dynamic security, a client can log in with an access token (JWT) of an identity provider such as Keycloak:
the token is the password, the user name is ignored. The plugin `docker/mosquitto/token-auth`, built into the broker image, checks
it offline against the public keys in `token-auth/token-auth.json` in the shared directory, which the UI writes: signature
(RS, PS and ES with 256, 384 or 512 bits, never `none` or HS), issuer, audience, expiry. The user name comes from a claim
(optionally with a prefix), the roles from a claim with groups mapped to roles of dynamic security, with the same rules as there:
roles in order, a literal rule before the patterns, then the default access. A token without a role is refused.

- The plugin is loaded before dynamic security. For a client it let in, it decides every ACL check itself, so a token named
  like a user of dynamic security never gets that user's rights; names of such users are refused anyway. Any other password, a
  token that does not hold, or no file, and dynamic security decides as before.
- When the token expires, or the file takes the rights away, the client is disconnected. It has to come back with a new token;
  the plugin knows nothing of refresh tokens or MQTT 5 re-authentication.
- It never goes to the network: the UI fetches the keys and writes the file, the plugin reads it again within a second when it
  changes. Without the UI the last file stays in force.
- The broker log names the reason for every token it refuses (`token-auth: ... not accepted: expired`), never the token.
- *Bind client id* (per issuer, on for new ones): the client id has to be the user name or start with `<user name>/`. Without it a
  token may use any client id, and with that of another client take over its connection and its persistent session.
- Keys of an issuer come from its discovery document (an OpenID provider such as Keycloak), from a JWKS at an address of its
  own, or are pasted on the page: PEM blocks of public keys or certificates, or a JWKS as JSON, RSA from 2048 bits, EC on
  P-256, P-384 or P-521. With the last two, the issuer is any text without spaces, exactly as in `iss`; pasted keys count as soon
  as they are saved, nothing is fetched. `StaticIssuerTest` signs tokens for all three, a certificate made by keytool included.
- mosquitto-ui can issue tokens itself (`/api/token-auth/issued`), without an identity provider: signed with a key of its own
  (ES256, the private key encrypted in the state directory, the public one in the file of the plugin under the issuer
  `mosquitto-ui`). A token carries the user name, the audience `mqtt`, its expiry and its id; its roles stay in the UI, by that
  id, so they can change later, and taking the token back keeps it out at once. The token is shown once and not kept. Client
  ids are always bound for these tokens. Like an API key it is good as long as it is not taken back or expired, so keep it
  like a password.
- Blocks (`/api/token-auth/blocks`) keep out a user name, a subject (`sub`) or a single token (`jti`), of one issuer or of all,
  until a time or for good. The plugin disconnects clients they hold for within a second. A block of a single token can end
  when the token would have expired; other tokens of the same client still get in.
- `TokenAuthTest` signs its own tokens and runs them against the real broker image, including tokens that must not get in.

The page *Token login* holds the identity providers: issuer, audience, the claims with the user name and the groups, an optional
prefix, and which groups get which roles. The UI fetches the keys from the discovery document of each issuer (again every 15
minutes, `mosquitto-ui.token-auth.key-refresh`, and with *Fetch keys again* after a rotation; when a provider is down, its keys
before stay in force) and writes the file whenever the settings, the keys, the roles or the clients of dynamic security change,
through the UI at once, otherwise within a minute (`mosquitto-ui.token-auth.interval`). Roles of the UI itself cannot be mapped.
The settings are in `token-auth.json` in the state directory and go into the backup part *Access*; `TokenAuthApiTest` runs a
small identity provider of its own.

Dynamic security does not know the clients that came in with a token. The plugin publishes them, retained, on
`$SYS/broker/token-auth/clients` (client id, user name, issuer, address, expiry, roles), and the page *Clients* shows them in a
table of their own. Whoever may subscribe to that topic sees them, as with the rest of `$SYS`.

## Sessions and trace

Details: [docs/en/sitzungen-aufzeichnung.md](docs/en/sitzungen-aufzeichnung.md) (German: [docs/sitzungen-aufzeichnung.md](docs/sitzungen-aufzeichnung.md)).

The plugin `docker/mosquitto/inspect`, built into the broker image from 0.21.0 on, follows the events of the broker and answers
on `$CONTROL/inspect/v1`, as dynamic security does on its own topic. The broker user of the UI needs `$CONTROL/inspect/#` to send
and subscribe; the role `super-admin` of the default user `admin` has `$CONTROL/#`. Without the plugin or the rights both pages
say so, the rest of the UI keeps working.

- **Sessions** lists every session the broker has, connected or kept for later (persistent sessions): client id, user name,
  address, MQTT version, keep alive, the subscriptions with their QoS and options, and how many messages and bytes each client
  sent and got, with the last time. Times and counts start with the broker. The broker gives plugins only the number of
  subscriptions of a client, not which: the plugin sees each subscribe, so those of a session kept from before the broker started
  show only as a number until the client subscribes again.
- **Trace** records for up to an hour what clients do: connect and disconnect with their reason, subscribe and unsubscribe,
  every message they send and every one delivered to them, with the first bytes of the payload (at most 4096), and what the
  broker refused them, a message or subscription the ACLs did not allow. For one client id, all with a prefix (`sensor-*`), a
  topic filter, or both; connect and disconnect only with a client id. One trace runs at a time, at most 50000 events. The UI
  keeps the last five in memory, until it restarts, and downloads one as JSON, with the payloads as text where they are UTF-8 or all as base64. Starting a trace is in the audit log.

A trace sends the payloads of other clients to the UI, on `$CONTROL/inspect/v1/trace`, which only who may use
`$CONTROL/inspect/#` gets: give that topic to nobody else. The plugin costs nearly nothing while no trace runs: a counter per
message and client.

## Message rules

Details: [docs/en/nachrichtenregeln.md](docs/en/nachrichtenregeln.md) (German: [docs/nachrichtenregeln.md](docs/nachrichtenregeln.md)).

The page Message rules sets what messages on a topic must look like (valid JSON, a size limit, required fields with their
type, by a dotted path) and how many messages a client may send (a bucket of *burst* messages that fills up with *per
second*; the first limit that matches a client counts). The plugin `docker/mosquitto/inspect` (`rules.c`) checks every message
a client sends before anyone gets it and drops what does not hold; with *disconnect* it also disconnects the client, with
*mail* the UI sends a mail, at most one an hour for each rule. MQTT 5 clients see *Not authorized* or *Quota exceeded* in their
acknowledgement. The UI keeps the rules in `message-rules.json`, writes those that are on to `rules/rules.json` in the shared
directory, which the broker reads again within a second, and puts them in the backup section Access. Its own users (its
broker user, `mosquitto-ui-monitor`, `mosquitto-ui-import-*`) are never checked, so that restoring a backup is not slowed down.
Dropped messages count per rule and client (pages Message rules and Sessions) and show in a trace as *dropped*.

## Logs

Details: [docs/en/broker-log.md](docs/en/broker-log.md) (German: [docs/broker-log.md](docs/broker-log.md)).

The broker image from 0.23.0 on has `log_dest stderr` and `log_dest topic`: the log still goes to `docker logs`, and each line
also to `$SYS/broker/log/<level>`, which the UI subscribes to. The page Logs shows the lines live like `tail -f` (server-sent events on `/api/logs/stream`; it follows the end, scrolling up or pause stops it), with a search, a choice of levels, filters
(errors and refusals, connections, subscriptions, bridges), marks for refused logins, timeouts and disconnects, and a download.
The UI keeps the latest 10 000 lines (`MOSQUITTO_UI_BROKER_LOG_KEEP`) in memory and in `broker-log.jsonl` in its state
directory, so they outlast a restart of the UI; they are not part of the backup. Mosquitto never publishes debug lines. Anyone
who may read `$SYS/#` gets the lines too; the page Topics leaves them out.

## Last connections

The table of clients shows when each one was last connected, and from which address. The broker only tells who is connected right
now, so the UI looks every 15 seconds and keeps the result in `client-logins.json` in its state volume, for as long as the client
exists. A connection between two looks is not seen, and neither is anything while the UI is down. The file is not part of backups.

Clients with an access token are kept the same way, by user name, in `token-client-logins.json`. They stay in the card *Clients with
an access token* below the table, with when they were last seen, until hidden there; one hidden shows again when it connects again.
See [docs/en/token-login-keycloak.md](docs/en/token-login-keycloak.md), section 12.

The state of a bridge is the notification of the broker on `$SYS/broker/connection/<id>/state`, so `notifications false` or a
`notification_topic` of your own hide it, and the UI then shows it as unknown with a warning. Since when a bridge is in its state
stays in `bridge-states.json` in the state volume. A connected bridge never counts as older than the broker, and a change while the UI
is down gets the time it was noticed.

The dashboard names clients that are usually connected and are not: their last connection lasted at least an hour, and it ended
between 15 minutes and 7 days ago. Disabled clients are left out. After a short reconnect a client does not count as usually
connected until the new connection has lasted that hour too.

## Audit log

The page *Audit log* shows who changed what through the UI and whether it worked, and every entry also goes to the log of the
container. It keeps the last 20000 changes in `audit-log.jsonl` in the state volume. A reset leaves it alone. In a backup it is a part of its
own, and restoring it adds what is missing instead of replacing the log, so nothing newer gets lost. Only names and targets are written: passwords, keys, configurations and the values of published messages never are.
Automatic runs such as scheduled backups and certificate renewals are not listed, nor is what clients do over MQTT.

## Broker figures

The dashboard shows what the broker publishes under `$SYS`: version, uptime, connections, subscriptions, retained messages, memory
and throughput. The broker user of the UI needs the role `sys-observe`, which the default user `admin` has, and `sys_interval` must
not be 0; otherwise the dashboard says so and the rest of the UI keeps working. Every 5 minutes the UI adds a point to a history of
7 days in `broker-stats.json` in its state volume, so the time it is down shows up as a gap. The file is not part of backups.

## Without Docker

Details: [docs/en/standalone.md](docs/en/standalone.md) (German: [docs/standalone.md](docs/standalone.md)).

Each release has `mosquitto-ui-<version>.tar.gz` (`standalone/package.sh`): the UI, the agent and the sources of the plugins.
`sudo ./install.sh` from the unpacked tarball installs two systemd services, `mosquitto-ui-broker` (Mosquitto 2.1.2 built from
source into `/usr/local`, run by the agent) and `mosquitto-ui` (Java 21), with settings in `/etc/mosquitto-ui` and data in
`/var/lib/mosquitto` and `/var/lib/mosquitto-ui`. Log in with the [local login](#login) first, identity providers are set up on the page *Login*. The
same command with a newer tarball updates; the broker only restarts when Mosquitto, the plugins or the agent changed.
Debian, Ubuntu, Fedora and RHEL/Rocky/Alma (apt or dnf).

## Login

Details: [docs/en/anmeldung.md](docs/en/anmeldung.md) (German: [docs/anmeldung.md](docs/anmeldung.md)).

People log in with **UI users** (user name and password, kept by the UI) and with one or more **identity providers**, both set
up on the page *Login*. There are templates for Keycloak, authentik, Authelia, ZITADEL, Pocket ID, Kanidm, Google, Microsoft
Entra ID, Apple, GitHub, GitLab, Gitea/Forgejo, Okta, Auth0, Amazon Cognito, and one for any OpenID Connect provider with
discovery. Each provider gets a button on the start page; its callback address is `https://<host>/auth/sso/<id>`.

- **Who may come in**, per provider, any rule that fits: everyone who can log in there, a role or group from the token, members
  of a GitHub organisation or team, or people by e-mail address (`*@domain` for a domain, only verified addresses) or user name,
  optionally as one of the UI users. Who logs in but fits no rule is turned away and listed on the page, one click lets them in.
- **Local login:** on while no provider is on, else a switch on the page. `MOSQUITTO_UI_AUTH=local` keeps it on whatever the page
  says, the way back in. The UI does not let the last way in go. The first start with the local login on creates `admin`
  (`MOSQUITTO_UI_LOCAL_AUTH_INITIAL_USER`) with `MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD`, or with a generated password in
  `<state dir>/initial-password`, which goes away after the first login.
- **From older versions:** `QUARKUS_OIDC_AUTH_SERVER_URL`, `QUARKUS_OIDC_CLIENT_ID` and `QUARKUS_OIDC_CREDENTIALS_SECRET` become
  a provider at the first start of 0.26 (letting in `MOSQUITTO_UI_ADMIN_ROLE`, the local login stays off), and are not read
  afterwards. The callback address is new: a redirect URI `https://<host>/*` at the provider fits it already.
- **Session:** an encrypted cookie of the UI (HttpOnly, SameSite=Lax, Secure over HTTPS) for both ways; the one of the OIDC
  extension is thrown away after the code flow. It ends after 8 hours without a request (`MOSQUITTO_UI_LOCAL_AUTH_SESSION_TIMEOUT`)
  and 7 days after the login (`..._SESSION_MAX`), when the provider connects differently or lets fewer in, and with a new
  password of the UI user. Logging out also logs out at the provider when it can (`end_session_endpoint`, with `id_token_hint`).
- Passwords: PBKDF2-HMAC-SHA256 with 600 000 iterations, at least 12 characters. UI users (`users.json`) and providers with their
  secrets (`sso.json`) are in the state directory, encrypted with the secret key, and in the backup section *access*.
- After 5 failed local logins in 15 minutes for one name or from one address, further ones wait (`..._MAX_FAILURES`,
  `..._FAILURE_WINDOW`). Logins, turned away ones and failures are in the audit log.

## Configuration

| Environment variable              | Meaning                                             | Default           |
|-----------------------------------|-----------------------------------------------------|-------------------|
| `MOSQUITTO_HOST`, `MOSQUITTO_PORT`| Broker                                              | `localhost`, 1883 |
| `MOSQUITTO_TLS`                   | TLS to the broker                                   | `false`           |
| `MOSQUITTO_USERNAME`              | User of the UI at the broker (required)             |                   |
| `MOSQUITTO_PASSWORD`              | Its password (required)                             |                   |
| `MOSQUITTO_UI_AUTH`               | `local` keeps the local login on whatever the page *Login* says, see [Login](#login) | `oidc` |
| `QUARKUS_OIDC_AUTH_SERVER_URL`, `QUARKUS_OIDC_CLIENT_ID`, `QUARKUS_OIDC_CREDENTIALS_SECRET` | Of older versions: become a provider at the first start, not read afterwards | |
| `MOSQUITTO_UI_ADMIN_ROLE`         | The role the provider taken over from `QUARKUS_OIDC_*` lets in | `mosquitto-admin` |
| `MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD` | Password of the first UI user, only when it is created | generated |
| `MOSQUITTO_UI_SHARED_DIR`         | Directory shared with the agent                     | `/mosquitto/ui`   |
| `MOSQUITTO_UI_STATE_DIR`          | Settings and account of the certificate             | `/var/lib/mosquitto-ui` |
| `MOSQUITTO_UI_CLIENT_LOGINS_INTERVAL` | How often the UI looks who is connected, see below | `15s`             |
| `MOSQUITTO_UI_CERT_DIR`           | Certificate as the broker sees it                   | `/mosquitto/data/certs` |
| `MOSQUITTO_UI_SECRET_KEY`         | Encrypts secrets in the state directory             | generated there      |
| `MOSQUITTO_UI_BACKUP_KEYSERVER`   | Where GPG keys of backup recipients are looked up   | `https://keys.openpgp.org` |

The identity provider needs `https://<host>/*` as redirect URI and post logout redirect URI, and has to put the roles
into a JWT access token. Behind a reverse proxy, see the commented settings in `application.properties`.

## License

Copyright (C) 2026 Alexander Schlenker

mosquitto-ui is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public
License, version 3, as published by the Free Software Foundation. It is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
[LICENSE](LICENSE) for the full text.

If you run a modified version for users over a network, the license asks you to offer them its source code (section 13).
The libraries it uses keep their own licenses (Apache-2.0, MIT, BSD, EPL-2.0/EDL-1.0 for Mosquitto).
