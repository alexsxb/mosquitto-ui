# Token login with Keycloak

How to create a client in Keycloak whose access tokens (JWT) log in to the broker, and how to get such tokens by hand and with
curl. Applies to Keycloak 26 and mosquitto-ui from 0.17.0 on; sections 8 to 11 (rejected tokens, new keys, notifications, rules
per user) apply from 0.19.0 on, section 12 (token clients with "last seen") from the version after 0.19.0. All calls here were
tried against Keycloak 26.8.

In the following:

| Placeholder | Meaning | Example |
|---|---|---|
| `KC` | address of Keycloak | `https://keycloak.example.com` |
| `REALM` | the realm of the devices | `devices` |
| `BROKER` | address of the broker | `broker.example.com` |

```sh
KC=https://keycloak.example.com
REALM=devices
BROKER=broker.example.com
```

This is how a login works:

1. The device gets an access token from Keycloak.
2. It connects to the broker and sends the token as the **password**. The user name is not looked at.
3. The broker checks the token itself, without asking Keycloak: signature, issuer, audience, expiry.
4. It takes the user name from one claim, the roles from a claim with groups.
5. When the token expires, the broker closes the connection. The device then gets a new token and connects again.

## Which way?

| | Client per device (section 1) | Devices as users (section 2) |
|---|---|---|
| In Keycloak | one client per device | one client for all, one user per device |
| On the device | client id and secret | the password once, after that only an offline token |
| Good for | few devices | many devices, managed in groups |
| Stop one | disable the client, new secret | disable the user or revoke offline access |

In both cases every device has a user name of its own at the broker, `%u` in the rules keeps the devices apart, and each one can
be blocked on its own in mosquitto-ui.

## 1. Create a client for a device (service account)

One client per device. It logs in with client id and secret (client credentials), no user is involved.

In the admin console, realm `REALM`:

1. **Clients → Create client**
   - Client type: *OpenID Connect*, Client ID: `sensor-1` → *Next*
   - *Client authentication*: **on**
   - Authentication flow: check only **Service accounts roles**; *Standard flow* and *Direct access grants* off → *Next* → *Save*
2. **Credentials**: copy the *Client secret*.
3. **Audience** – so that the token is meant for the broker and not for some other application of the realm:
   - **Client scopes → `sensor-1-dedicated` → Configure a new mapper → Audience**
   - Name `aud-mqtt`, *Included Custom Audience*: `mqtt`, *Add to access token*: **on** → *Save*
4. **Groups** that mosquitto-ui maps to roles. Two ways, one is enough:
   - **Groups** (recommended):
     - Under **Groups**, create a group, e.g. `sensors`.
     - Add the service account of the client to the group: **Clients → sensor-1 → Service accounts roles**, open the link to the user `service-account-sensor-1` there, then **Groups → Join group** and choose `sensors`.
     - To get the groups into the token: **Client scopes → `sensor-1-dedicated` → Add mapper → By configuration → Group Membership**. Name `groups`, *Token Claim Name* `groups`, *Full group path* **off**, *Add to access token* **on**.
     - In mosquitto-ui: Claim with the groups = `groups`.
   - **Realm roles**:
     - Under **Realm roles**, create a role, e.g. `mqtt-sensors`.
     - Assign it under **Clients → sensor-1 → Service accounts roles → Assign role**.
     - In mosquitto-ui: Claim with the groups = `realm_access.roles`.
5. Optional, the **lifetime** of the token: **Clients → sensor-1 → Advanced → Advanced settings → Access Token Lifespan**.
   - The default is 5 minutes.
   - Every time it expires, the broker disconnects the device, and it connects again.
   - Longer means fewer reconnects, but a lost token stays valid longer if it is not blocked (see 7.).

The device is then called `service-account-sensor-1` at the broker, from the claim `preferred_username`.

## 2. Devices as users (one client for all)

A single client `devices`, every device is a Keycloak user, e.g. `sensor-0001`, in a group such as `sensors`. The device logs in
**once** with its password and gets an offline token with it (valid for 30 days by default). After that it gets new access
tokens only with this one, the password does not have to stay on the device.

The way with the password (*Resource Owner Password Credentials*, in Keycloak "Direct access grants") is deprecated in
OAuth 2.1. Here it is only used once per device, to get the offline token.

**Client, once:**

1. **Clients → Create client**, Client ID `devices` → *Next*
2. *Client authentication* **on**, Authentication flow: only **Direct access grants** → *Next* → *Save*
3. Copy the *Client secret* from **Credentials**. It is the same for all devices and does not replace the password of the device.
4. Audience and Group Membership mappers as in section 1, steps 3 and 4, here on `devices-dedicated`.
5. Recommended: turn on **Realm settings → Security defenses → Brute force detection**.

**One user per device:**

1. **Users → Add user**, Username `sensor-0001`
   - **Fill in Email, First name and Last name**, *Email verified* on. Without these fields Keycloak 26 refuses the login with
     `Account is not fully set up` (the profile of the user is incomplete). Placeholders are enough, such as
     `sensor-0001@devices.invalid`, `Sensor`, `0001`.
   - *Join groups*: `sensors` → *Create*
2. **Credentials → Set password**, *Temporary* **off**.

Instead of filling in the fields, you can mark them as not required under **Realm settings → User profile** for the realm of the
devices (not tried).

**On the device, once with the password:**

```sh
curl -s -d grant_type=password -d client_id=devices -d client_secret=CLIENT-SECRET \
  -d username=sensor-0001 -d password=DEVICE-PASSWORD -d scope=offline_access \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .refresh_token > offline-token
```

**After that, for every new access token:**

```sh
RESPONSE=$(curl -s -d grant_type=refresh_token -d client_id=devices -d client_secret=CLIENT-SECRET \
  -d refresh_token="$(cat offline-token)" "$KC/realms/$REALM/protocol/openid-connect/token")
TOKEN=$(echo "$RESPONSE" | jq -r .access_token)
# Keycloak may send a new refresh token along: always keep the latest
echo "$RESPONSE" | jq -r '.refresh_token // empty' > offline-token.new && [ -s offline-token.new ] && mv offline-token.new offline-token
```

Connect as in section 4, with `-i sensor-0001` or `-i sensor-0001/main`. The user name at the broker is `sensor-0001`.

**How long is the offline token valid?**

- **30 days idle** (*Realm settings → Sessions → Offline Session Idle*), and the period starts again with every renewal. A
  device that gets an access token every few minutes therefore never runs out.
- **Only with the latest token:** Keycloak sends a new offline token with every renewal. The old one is only valid until its own
  period ends. That is why the script above always stores the latest one.
- **No overall limit**, as long as *Offline Session Max Limited* is off (the default). If it is on, it ends after *Offline
  Session Max* (60 days by default), no matter how often it was renewed.

Tested with Keycloak 26.8 and 20 seconds idle: with renewals every 15 seconds the token kept working for over a minute, after
25 seconds without a renewal it was invalid (`Token is not active`). With the first instead of the latest token,
`Token is not active` came already at the second renewal.

So the offline token only becomes invalid if the device gets none for longer than 30 days (switched off, no network) or loses
its latest token. Keycloak then answers with `invalid_grant`, and the device needs its password again. Three ways:

| Way | Advantage | Disadvantage |
|---|---|---|
| Also keep the password on the device and log in again with it on `invalid_grant` | keeps running without any action | the password is on the device |
| Set a new password in Keycloak (*Users → sensor-0001 → Credentials → Reset password*) and give it to the device once | no password on the device | manual work after every long pause |
| Raise the idle time, e.g. to 365 days, in a realm of its own for the devices | survives long pauses | a lost token stays valid longer if it is not blocked |

A device that falls back to the password when needed:

```sh
token() {
  RESPONSE=$(curl -s -d grant_type=refresh_token -d client_id=devices -d client_secret=CLIENT-SECRET \
    -d refresh_token="$(cat offline-token 2>/dev/null)" "$KC/realms/$REALM/protocol/openid-connect/token")
  if [ "$(echo "$RESPONSE" | jq -r .error)" = invalid_grant ] && [ -f password ]; then
    # offline token expired or lost: log in again once
    RESPONSE=$(curl -s -d grant_type=password -d client_id=devices -d client_secret=CLIENT-SECRET \
      -d username=sensor-0001 -d password="$(cat password)" -d scope=offline_access \
      "$KC/realms/$REALM/protocol/openid-connect/token")
  fi
  echo "$RESPONSE" | jq -r '.refresh_token // empty' > offline-token.new && [ -s offline-token.new ] && mv offline-token.new offline-token
  echo "$RESPONSE" | jq -r '.access_token // empty'
}

while true; do
  TOKEN=$(token)
  [ -n "$TOKEN" ] && mosquitto_sub -h "$BROKER" -p 8883 --capath /etc/ssl/certs \
    -i sensor-0001/main -u token -P "$TOKEN" -t 'sensors/#'
  sleep 5
done
```

**Stop a device**, in Keycloak:

- User → *Enabled* off. After that the device gets no more tokens ("User disabled").
- Or revoke the offline access of the user (**Users → sensor-0001 → Consents** or the admin API, see section 6). Then the
  stored offline token becomes invalid ("Offline user session not found"), and the device needs its password again.

Both only take effect with the next token. The access token the device is connected with right now stays valid until it
expires. To disconnect it at once, also block the user name in mosquitto-ui (section 7).

## 3. Enter it in mosquitto-ui

Page **Token login**:

| Field | Value |
|---|---|
| Issuer | `KC/realms/REALM`, exactly as in the claim `iss`, e.g. `https://keycloak.example.com/realms/devices` |
| Audience | `mqtt` |
| Claim with the user name | `preferred_username` |
| Claim with the groups | `groups` (or `realm_access.roles`) |
| Bind client id to the user name | on |
| Groups and roles | `sensors` → a role from *Roles*, e.g. with `publishClientSend sensors/%u/#` |

Then turn on *Token login on* and *Save*. Below the issuer it now has to say: "1 key … fetched".

`%u` in the rules of a role is the user name, e.g. `sensors/service-account-sensor-1/#`.

## 4. Get a token with curl and connect with it

```sh
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=sensor-1 -d client_secret=SECRET \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
```

The same, with the secret through basic auth instead of in the body:

```sh
TOKEN=$(curl -s -u sensor-1:SECRET -d grant_type=client_credentials \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
```

What is in the token (the middle part, base64url):

```sh
python3 -c 'import sys,json,base64; s=sys.argv[1].split(".")[1]; s+="="*(-len(s)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(s)), indent=2))' "$TOKEN"
```

It should contain:
- `"iss": "https://keycloak.example.com/realms/devices"`
- `"aud"` with `mqtt`
- `"preferred_username": "service-account-sensor-1"`
- `"groups": ["sensors"]`

Connect and publish:
- With a bound issuer, the client id (`-i`) has to be the user name or start with `<user name>/`. Why that is and what it means
  for `%c` in rules is in section 11.
- The user name (`-u`) can be anything.

```sh
mosquitto_pub -h "$BROKER" -p 8883 --capath /etc/ssl/certs \
  -i service-account-sensor-1/main -u token -P "$TOKEN" \
  -t sensors/service-account-sensor-1/temperature -m 21.5
```

A device that stays connected gets a new token after every disconnect:

```sh
while true; do
  TOKEN=$(curl -s -u sensor-1:SECRET -d grant_type=client_credentials \
    "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
  mosquitto_sub -h "$BROKER" -p 8883 --capath /etc/ssl/certs \
    -i service-account-sensor-1/main -u token -P "$TOKEN" -t 'sensors/#'
  sleep 2
done
```

After a disconnect, `mosquitto_sub` first tries to reconnect with the old token. The broker refuses that, `mosquitto_sub` exits, and the loop gets a new token.

## 5. A token by hand, for people (device flow)

For a person instead of a device, for example to test with `mosquitto_sub`. You log in with your own Keycloak account in the browser, then the command line gets the token.

Create a client for this once:

1. **Clients → Create client**, Client ID `mqtt-cli` → *Next*
2. *Client authentication* **off**. Under Authentication flow check only **OAuth 2.0 Device Authorization Grant**, *Standard flow* and *Direct access grants* off → *Next* → *Save*.
3. Audience and Group Membership mappers as in section 1, steps 3 and 4.

Then every time:

```sh
curl -s -d client_id=mqtt-cli "$KC/realms/$REALM/protocol/openid-connect/auth/device" | tee device.json | jq
```

Open `verification_uri_complete` in the browser, log in, confirm. Then:

```sh
TOKEN=$(curl -s -d grant_type=urn:ietf:params:oauth:grant-type:device_code -d client_id=mqtt-cli \
  -d device_code=$(jq -r .device_code device.json) \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
```

Before the confirmation in the browser, Keycloak answers with `authorization_pending`. Then just ask again. The user name at the broker is your own, say `alice`, and the client id accordingly `alice/test`.

About the tab *Client scopes → Evaluate* of the admin console: it only shows **what** a token would contain. It does not give you a signed token that you could use as a password.

## 6. The same through the admin API

For example, to create many devices. With an admin token:

```sh
ADMIN=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=ADMIN -d password=ADMIN-PASSWORD \
  "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
API="$KC/admin/realms/$REALM"
```

Client with Audience and Group Membership mappers:

```sh
curl -s -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' "$API/clients" -d '{
  "clientId": "sensor-1", "publicClient": false, "serviceAccountsEnabled": true,
  "standardFlowEnabled": false, "directAccessGrantsEnabled": false,
  "protocolMappers": [
    {"name": "aud-mqtt", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
     "config": {"included.custom.audience": "mqtt", "access.token.claim": "true", "id.token.claim": "false"}},
    {"name": "groups", "protocol": "openid-connect", "protocolMapper": "oidc-group-membership-mapper",
     "config": {"claim.name": "groups", "full.path": "false", "access.token.claim": "true", "id.token.claim": "false"}}
  ]}'
```

Create a device user (section 2), with group and password right away:

```sh
curl -s -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' "$API/users" -d '{
  "username": "sensor-0001", "enabled": true, "email": "sensor-0001@devices.invalid", "emailVerified": true,
  "firstName": "Sensor", "lastName": "0001", "groups": ["sensors"],
  "credentials": [{"type": "password", "value": "DEVICE-PASSWORD", "temporary": false}]}'
```

Revoke its offline access for the client `devices`:

```sh
USER=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/users?username=sensor-0001&exact=true" | jq -r '.[0].id')
curl -s -X DELETE -H "Authorization: Bearer $ADMIN" "$API/users/$USER/consents/devices"
```

For a client per device (section 1), read the secret and add the service account to the group `sensors`:

```sh
CID=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/clients?clientId=sensor-1" | jq -r '.[0].id')
curl -s -H "Authorization: Bearer $ADMIN" "$API/clients/$CID/client-secret" | jq -r .value
SA=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/clients/$CID/service-account-user" | jq -r .id)
GID=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/groups?search=sensors" | jq -r '.[0].id')
curl -s -X PUT -H "Authorization: Bearer $ADMIN" "$API/users/$SA/groups/$GID"
```

## 7. Blocking

A lost device or a leaked token can be blocked on the page **Clients** (button *Block* on the token client) or on the page **Token login** (card *Blocked*):

- **Only this token (`jti`):** the token no longer gets in, a new token of the device does. The block only has to last as long as the token itself.
- **User name or subject (`sub`):** every token of the device stays out.

The broker disconnects affected connections within a second. So that the device does not get a new token either, also disable the client in Keycloak or generate a new secret for it.

## 8. Rejected tokens

At the bottom of the page **Token login** is the card **Rejected tokens**. It lists every login with a token that the broker did
not accept since its last start, the latest first. So you no longer have to look into the log of the broker to see why a device
does not get in.

What counts as a token: a password that looks like a JWT (starts with `ey`, three parts separated by dots). A normal password of
a dynamic security client never shows here, not even a wrong one.

| Column | Content |
|---|---|
| Last | When it last happened. If the same client tries again with the same issuer, name and reason, no new entry is added, it is counted instead ("14 times"). That way a device that retries every second does not push out the others. |
| Client | Client id and the address the connection came from (behind a proxy with the PROXY protocol, the real address). |
| User name | The user name as the token gives it, with the prefix of the issuer. **not verified** means: the signature did not hold (or was not checked at all), the name is only what the token says and can come from anyone. |
| Reason | What did not match, with a hint on what to do (table below). |
| Issuer | The value of `iss` in the token, even if no issuer is set up like that. That is exactly when it helps: usually it is a typo or another realm. |
| Button *Block* | Only for tokens with a valid signature. Opens the same dialog as on the page Clients, with `sub` and `jti` from the token. |

The list comes from the plugin in the broker. It holds at most 50 entries and starts empty after every restart of the broker. The
plugin publishes it retained on `$SYS/broker/token-auth/rejected`, as a JSON list with `at` (seconds since 1970), `count`,
`clientid`, `address`, `issuer`, `kid`, `username`, `sub`, `jti`, `verified` and `reason`. `sub` and `jti` are only included
with a valid signature. Through the API: `GET /api/token-auth/rejected`. The token itself is not stored anywhere.

| Reason (code) | Meaning | What to do |
|---|---|---|
| Not a JWT (`not_jwt`) | Looks like a token, but cannot be read | The token is cut off or was copied broken |
| Algorithm not allowed (`algorithm`) | `alg` is not RS*, PS* or ES* | `none` and HS* (shared secret) never work |
| Critical header (`critical_header`) | The header contains `crit` | Never accepted |
| Unknown issuer (`unknown_issuer`) | No issuer that is set up has this value of `iss` | Compare the column *Issuer* with the entry, watch out for `localhost` instead of the host name |
| No signature (`no_signature`) | The third part is missing | Send the complete token |
| Unknown key (`unknown_key`) | The token names a `kid` that none of the keys of the issuer has | Usually nothing: mosquitto-ui fetches the keys again at once, see section 9 |
| Signature not valid (`signature`) | Not valid with any key of the issuer | Token from another realm or server, or modified |
| No expiry (`no_expiry`) | `exp` is missing | Tokens without expiry are not accepted |
| Expired (`expired`) | `exp` is more than 30 seconds in the past | Get a new token, check the clocks |
| Not valid yet (`not_yet_valid`) | `nbf` is more than 30 seconds in the future | Clock of the issuer or the broker is wrong |
| Wrong audience (`audience`) | `aud` does not contain the audience entered | Check the Audience mapper in Keycloak |
| No user name (`no_username`) | The claim for the user name is missing | Check the field *Claim with the user name* |
| Name belongs to dynamic security (`username_reserved`) | There is a dynamic security client with this name | Set a prefix for the issuer |
| Client id does not match (`clientid`) | With a bound client id: `-i` is neither the user name nor starts with `<user name>/` | See section 11 |
| Blocked (`blocked`) | A block applies | Card *Blocked* |
| No role (`no_role`) | No group of the token is mapped to a role | Check the group mapper, group membership and mapping |
| Internal error (`error`) | Storage or broker | Log of the broker |

## 9. New keys in Keycloak

Keycloak signs the tokens with a key of the realm. You can change (rotate) it under **Realm settings → Keys**, for example with a
new *rsa-generated* provider with a higher priority. Every token names in its header the `kid` of the key it is signed with.

mosquitto-ui fetches the keys of all issuers regularly, every 15 minutes by default
(`mosquitto-ui.token-auth.key-refresh`). Until then the broker does not know a new key. That is why this also applies:

1. A device logs in with a token whose `kid` the broker does not know.
2. The broker refuses, reason *Unknown key*, and reports that on `$SYS` within a second.
3. mosquitto-ui fetches the keys of this issuer again at once and writes them to the broker. The broker reads them within a
   second.
4. The next attempt of the device works. Devices that reconnect on their own after a disconnect (like the loop in section 4)
   only notice a short delay.

So that nobody floods Keycloak with fetches through made-up `kid`s, there are limits:

- At most every 30 seconds per issuer.
- Only for issuers that are set up and whose keys come from an address (*OpenID discovery* or *JWKS address*). With keys
  entered here there is nothing to fetch: there you have to paste the new key yourself.
- Not if the keys were fetched after the token anyway.

If Keycloak does not know the `kid` even after the fetch, it stays at *Unknown key*: then the token does not come from this
realm. The button *Fetch keys again* on the page still works at any time.

For a planned change without a single refusal: first create the new key in Keycloak as *passive* (*Active* off, *Enabled* on).
It is then already in the JWKS, but does not sign yet. After the next fetch (at the latest 15 minutes, or *Fetch keys again*),
make it active. Only remove the old key once all tokens signed with it have expired.

## 10. Notifications for token clients

The mail "Client missing" of the page **Notifications** also applies to clients that log in with a token, as does the list of
missing clients in the dashboard. The same rules apply as for dynamic security clients:

- Every user name that was connected with a token is remembered. mosquitto-ui checks every 15 seconds who is connected.
- "Missing" means: the last connection lasted at least an hour, and the client has been gone for 15 minutes. Devices that
  only send a value briefly and leave again are therefore never missing.
- After 7 days without a connection, a user name is no longer missing. On the page **Clients** it stays until you hide it
  (section 12); a hidden one is no longer missing either.
- Several connections with the same user name (client ids `sensor-1/a`, `sensor-1/b`) count as one client: it is only missing
  when none of them is left.

A token client that cannot come back at all is not missing. That is the case when

- token login is off,
- its issuer was removed,
- its user name, `sub` or `jti` is blocked,
- or its token was issued by mosquitto-ui and revoked, or has expired.

An expired token from Keycloak does count, on the other hand: if the device does not get a new one, that is a real outage.

To silence single clients: on the page **Notifications**, under *Do not report these clients*, enter the user name as it is
called at the broker, that is with the prefix of the issuer. The connections are remembered in
`token-client-logins.json` in the state directory.

## 11. One Keycloak client, many users: rules with `%u`

The usual setup is the one from section 2: **one** client in Keycloak (e.g. `devices` or `mqtt`), **many** users, one per
device or per person. Every user has a user name of its own at the broker, from `preferred_username`. The rights come through
groups, the separation between the users through `%u` in the rules.

Example: group `sensors` → role `sensors` with

| Rule | Topic | Allowed |
|---|---|---|
| `publishClientSend` | `sensors/%u/#` | yes |
| `subscribePattern` | `commands/%u/#` | yes |
| `publishClientReceive` | `commands/%u/#` | yes |

The user `sensor-0001` may then publish to `sensors/sensor-0001/...` and read `commands/sensor-0001/...`, but nothing of
`sensor-0002`. One role is enough for all users. New devices only need a new Keycloak user in the group, nothing changes in
mosquitto-ui.

### Why `%u` and not `%c`

*Bind client id to the user name* (recommended, on for new issuers) means: the client id has to be exactly the user name, or
start with `<user name>/`. Allowed for `sensor-0001` are therefore `sensor-0001` and `sensor-0001/main`, not `sensor-0002` and not
`sensor-0001-x`. That way nobody can take over the session of another user with their own valid token (a new connection with
the same client id throws out the old one and inherits its session).

`%c` in a rule is the client id. But Mosquitto only ever substitutes `%c` and `%u` for **one level** of the topic: the comparison
ends at the next `/` of the topic. A client id with `/`, such as `sensor-0001/main`, therefore matches no rule with `%c`. That is
how Mosquitto itself works (`libcommon/topic_common.c`, 2.1.2), dynamic security behaves the same.

It follows that:

- Use `%u` in rules for token clients. It always matches and it is also what you mean: the rights belong to the user, not to
  the connection.
- `%c` only works if the client id is exactly the user name, without `/…`. Then `%c` is the same as `%u` and gains nothing.
- If a user needs several connections at the same time, they get client ids such as `sensor-0001/main` and `sensor-0001/ota`.
  The rules with `%u` apply to all of them.

Another separator instead of `/` (say `sensor-0001:main`, so that `%c` matches) is deliberately not built in. It would only be
safe if the character cannot occur in any user name. With `-`, for example, the user `sensor` could take the client id
`sensor-1-x`, which belongs to the user `sensor-1`. With `/` that cannot happen.

Without binding (switch off), the client id is free. But then every user whose token is valid can take over the session of
another one if they know its client id. Only turn it off if all users of an issuer trust each other.

## 12. Token clients on the page Clients

Below the dynamic security table, the page **Clients** has the card *Clients with an access token*. It shows every user name
that has ever connected with a token, not only the ones connected right now. The connected ones come first, then the others,
the most recently seen at the top.

Search and filters at the top of the page also apply to this card: the search to user name, client id, roles and issuer,
*Connected* and *Not connected* to the connection, *Disabled* to those that no longer get in (hint below), and the role to the
roles of the last connection. The roles of the token clients are included in the selection. When a filter is set, the title
says how many of all are shown; if none matches, the card is left out.

| Column | Content |
|---|---|
| User name | as at the broker, with the prefix of the issuer; below it a hint if it no longer gets in (see below) |
| Client ID | the client id and address of the last connection |
| Roles | the roles of the last connection, as the groups of the token gave them |
| Last seen | green dot and "since …" when it is connected; otherwise when it was last there |
| Token valid until / issuer | the end of the token used last and who issued it |

The hint below the user name says why it no longer gets in:

| Hint | Meaning |
|---|---|
| Blocked | A block on its user name, its `sub` or its `jti` applies. |
| Token revoked | Its token comes from mosquitto-ui and was revoked or has expired. |
| Issuer removed | Its issuer is no longer set up. |
| Token login off | Token login is turned off. |

An expired token from Keycloak gives no hint, because the device can get a new one at any time.

Buttons at the end of the row:

- **Block** opens the same dialog as in section 7, also for one that is not connected right now. That way you can block a lost
  device before it shows up again. For a blocked or revoked token the button is missing.
- **Hide** takes the user name out of the list. It is then no longer missing either (section 10). If it connects again, it is
  back. Hiding blocks nothing. You cannot hide one that is connected.

The list comes from `token-client-logins.json` in the state directory (not part of the backup). mosquitto-ui checks every 15
seconds who is connected; a connection that lasts less than that it does not see, and while the UI is not running, it sees
nothing. Several connections with the same user name show as one row, with the first client id the broker names. Entries
written by 0.19.0 have no client id, roles and expiry yet, until the client connects again.

Through the API: `GET /api/token-auth/seen` returns all seen ones with `connected`, `lastSeen` and `lockedOut` (`null`,
`disabled`, `issuer`, `revoked` or `blocked`). `DELETE /api/token-auth/seen/{username}` hides one; it returns 409 while it is
connected, and 404 if it is not in the list.

## Troubleshooting

First look at the card *Rejected tokens* (section 8). 
Besides that, the broker writes the reason to its log for every refusal, never the token itself:

```sh
docker compose logs mosquitto | grep token-auth
```

| In the log | Cause |
|---|---|
| `not accepted: unknown issuer` | `iss` in the token differs from the issuer entered. That already happens when Keycloak was called under another address, such as `localhost` instead of its name. |
| `not accepted: wrong audience` | The Audience mapper is missing or has another value. |
| `not accepted: no key with the kid of the token` | Keycloak has a new key. mosquitto-ui fetches the keys again on its own, see section 9. |
| `not accepted: signature not valid` | The token matches no key of the issuer: another realm or server, or modified. |
| `not accepted: expired` | Token expired. Get a new one, and check the clocks of device and broker. |
| `not accepted: … has no role` | No group in the token is mapped to a role: check the mapper, group membership and the mapping in mosquitto-ui. |
| `not accepted: client id does not belong to the user name` | `-i` does not match the user name, see sections 4 and 11. |
| `not accepted: blocked` | Blocked, see the page *Token login*. |
| Keycloak: `Account is not fully set up` | Device user without email, first or last name, see section 2. |
| `not accepted: user name taken by dynamic security` | The name already exists as a user in dynamic security. Set a prefix for the issuer. |
| `disconnected, its token expired or lost its rights` | Token expired, role taken away, or blocked. |
