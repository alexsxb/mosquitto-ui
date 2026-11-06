# Token-Anmeldung mit Keycloak

Wie man in Keycloak einen Client anlegt, dessen Access-Tokens (JWT) sich am Broker anmelden, und wie man solche Tokens per Hand
und per curl holt. Gilt für Keycloak 26 und mosquitto-ui ab 0.17.0; die Abschnitte 8 bis 11 (abgelehnte Tokens, neue Schlüssel,
Benachrichtigungen, Regeln pro Benutzer) gelten ab 0.19.0, Abschnitt 12 (Token-Clients mit „zuletzt gesehen“) ab der Version
nach 0.19.0. Alle Aufrufe hier wurden gegen Keycloak 26.8 ausprobiert.

Im Folgenden:

| Platzhalter | Bedeutung | Beispiel |
|---|---|---|
| `KC` | Adresse von Keycloak | `https://keycloak.example.com` |
| `REALM` | der Realm der Geräte | `devices` |
| `BROKER` | Adresse des Brokers | `broker.example.com` |

```sh
KC=https://keycloak.example.com
REALM=devices
BROKER=broker.example.com
```

So läuft eine Anmeldung ab:

1. Das Gerät holt sich bei Keycloak ein Access-Token.
2. Es verbindet sich mit dem Broker und schickt das Token als **Passwort**. Der Benutzername wird nicht angeschaut.
3. Der Broker prüft das Token selbst, ohne Keycloak zu fragen: Signatur, Issuer, Audience, Ablauf.
4. Den Benutzernamen nimmt er aus einem Claim, die Rollen aus einem Claim mit Gruppen.
5. Läuft das Token ab, trennt der Broker die Verbindung. Das Gerät holt dann ein neues Token und verbindet sich wieder.

## Welcher Weg?

| | Client pro Gerät (Abschnitt 1) | Geräte als Benutzer (Abschnitt 2) |
|---|---|---|
| In Keycloak | ein Client je Gerät | ein Client für alle, ein Benutzer je Gerät |
| Auf dem Gerät | Client-ID und Secret | einmal Passwort, danach nur ein Offline-Token |
| Gut für | wenige Geräte | viele Geräte, verwaltet in Gruppen |
| Einzeln stoppen | Client deaktivieren, Secret neu | Benutzer deaktivieren oder Offline-Zugriff entziehen |

In beiden Fällen hat jedes Gerät einen eigenen Benutzernamen am Broker, `%u` in den Regeln trennt die Geräte, und jedes lässt sich
in mosquitto-ui einzeln sperren.

## 1. Client für ein Gerät anlegen (Service-Account)

Ein Client pro Gerät. Er meldet sich mit Client-ID und Secret an (Client-Credentials), ein Benutzer ist nicht beteiligt.

In der Admin-Konsole, Realm `REALM`:

1. **Clients → Create client**
   - Client type: *OpenID Connect*, Client ID: `sensor-1` → *Next*
   - *Client authentication*: **an**
   - Authentication flow: nur **Service accounts roles** anhaken; *Standard flow* und *Direct access grants* aus → *Next* → *Save*
2. **Credentials**: das *Client secret* kopieren.
3. **Audience** – damit das Token für den Broker bestimmt ist und nicht für irgendeine andere Anwendung des Realms:
   - **Client scopes → `sensor-1-dedicated` → Configure a new mapper → Audience**
   - Name `aud-mqtt`, *Included Custom Audience*: `mqtt`, *Add to access token*: **an** → *Save*
4. **Gruppen**, die mosquitto-ui auf Rollen abbildet. Zwei Wege, einer reicht:
   - **Gruppen** (empfohlen):
     - Unter **Groups** eine Gruppe anlegen, z. B. `sensors`.
     - Den Service-Account des Clients in die Gruppe aufnehmen: **Clients → sensor-1 → Service accounts roles**, dort den Link auf den Benutzer `service-account-sensor-1` öffnen, dann **Groups → Join group** und `sensors` wählen.
     - Damit die Gruppen ins Token kommen: **Client scopes → `sensor-1-dedicated` → Add mapper → By configuration → Group Membership**. Name `groups`, *Token Claim Name* `groups`, *Full group path* **aus**, *Add to access token* **an**.
     - In mosquitto-ui: Claim mit den Gruppen = `groups`.
   - **Realm-Rollen**:
     - Unter **Realm roles** eine Rolle anlegen, z. B. `mqtt-sensors`.
     - Bei **Clients → sensor-1 → Service accounts roles → Assign role** zuweisen.
     - In mosquitto-ui: Claim mit den Gruppen = `realm_access.roles`.
5. Optional **Laufzeit** des Tokens: **Clients → sensor-1 → Advanced → Advanced settings → Access Token Lifespan**.
   - Standard sind 5 Minuten.
   - Bei jedem Ablauf trennt der Broker das Gerät, und es verbindet sich neu.
   - Länger heißt weniger Neuverbindungen, aber ein abhandengekommenes Token gilt länger, wenn es nicht gesperrt wird (siehe 7.).

Das Gerät heißt am Broker dann `service-account-sensor-1`, aus dem Claim `preferred_username`.

## 2. Geräte als Benutzer (ein Client für alle)

Ein einziger Client `devices`, jedes Gerät ist ein Keycloak-Benutzer, z. B. `sensor-0001`, in einer Gruppe wie `sensors`. Das
Gerät meldet sich **einmal** mit seinem Passwort an und bekommt dabei ein Offline-Token (standardmäßig 30 Tage gültig). Danach
holt es neue Access-Tokens nur noch damit, das Passwort muss nicht auf dem Gerät bleiben.

Der Weg mit dem Passwort (*Resource Owner Password Credentials*, in Keycloak „Direct access grants“) gilt in OAuth 2.1 als
veraltet. Hier wird er nur einmal pro Gerät benutzt, um an das Offline-Token zu kommen.

**Client, einmal:**

1. **Clients → Create client**, Client ID `devices` → *Next*
2. *Client authentication* **an**, Authentication flow: nur **Direct access grants** → *Next* → *Save*
3. *Client secret* aus **Credentials** kopieren. Es ist für alle Geräte gleich und ersetzt nicht das Passwort des Geräts.
4. Audience- und Group-Membership-Mapper wie in Abschnitt 1, Schritte 3 und 4, hier auf `devices-dedicated`.
5. Empfohlen: **Realm settings → Security defenses → Brute force detection** einschalten.

**Pro Gerät ein Benutzer:**

1. **Users → Add user**, Username `sensor-0001`
   - **Email, First name und Last name ausfüllen**, *Email verified* an. Ohne diese Felder lehnt Keycloak 26 die Anmeldung ab mit
     `Account is not fully set up` (das Profil des Benutzers ist unvollständig). Es reichen Platzhalter wie
     `sensor-0001@devices.invalid`, `Sensor`, `0001`.
   - *Join groups*: `sensors` → *Create*
2. **Credentials → Set password**, *Temporary* **aus**.

Statt die Felder auszufüllen, kann man sie unter **Realm settings → User profile** für den Realm der Geräte als nicht
erforderlich markieren (nicht ausprobiert).

**Auf dem Gerät, einmal mit dem Passwort:**

```sh
curl -s -d grant_type=password -d client_id=devices -d client_secret=CLIENT-GEHEIM \
  -d username=sensor-0001 -d password=GERAETE-PASSWORT -d scope=offline_access \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .refresh_token > offline-token
```

**Danach, für jedes neue Access-Token:**

```sh
RESPONSE=$(curl -s -d grant_type=refresh_token -d client_id=devices -d client_secret=CLIENT-GEHEIM \
  -d refresh_token="$(cat offline-token)" "$KC/realms/$REALM/protocol/openid-connect/token")
TOKEN=$(echo "$RESPONSE" | jq -r .access_token)
# Keycloak kann ein neues Refresh-Token mitschicken: immer das neueste aufheben
echo "$RESPONSE" | jq -r '.refresh_token // empty' > offline-token.new && [ -s offline-token.new ] && mv offline-token.new offline-token
```

Verbinden wie in Abschnitt 4, mit `-i sensor-0001` oder `-i sensor-0001/main`. Der Benutzername am Broker ist `sensor-0001`.

**Wie lange gilt das Offline-Token?**

- **30 Tage Leerlauf** (*Realm settings → Sessions → Offline Session Idle*), und die Frist beginnt bei jeder Erneuerung neu. Ein
  Gerät, das alle paar Minuten ein Access-Token holt, läuft also nie aus.
- **Nur mit dem jeweils neuesten Token:** Keycloak schickt bei jeder Erneuerung ein neues Offline-Token mit. Das alte gilt nur
  noch bis zu seiner eigenen Frist. Deshalb speichert das Skript oben immer das neueste.
- **Keine Gesamtgrenze**, solange *Offline Session Max Limited* aus ist (Standard). Ist sie an, ist nach *Offline Session Max*
  (Standard 60 Tage) Schluss, egal wie oft erneuert wurde.

Ausgetestet mit Keycloak 26.8 und 20 Sekunden Leerlauf: Mit Erneuerungen alle 15 Sekunden lief das Token über eine Minute
weiter, nach 25 Sekunden ohne Erneuerung war es ungültig (`Token is not active`). Mit dem ersten statt dem neuesten Token kam
schon bei der zweiten Erneuerung `Token is not active`.

Ungültig wird das Offline-Token also nur, wenn das Gerät länger als 30 Tage keines holt (aus, ohne Netz) oder sein neuestes
Token verliert. Dann antwortet Keycloak mit `invalid_grant`, und das Gerät braucht wieder sein Passwort. Drei Wege:

| Weg | Vorteil | Nachteil |
|---|---|---|
| Passwort zusätzlich auf dem Gerät behalten und bei `invalid_grant` damit neu anmelden | läuft ohne Zutun weiter | das Passwort liegt auf dem Gerät |
| In Keycloak ein neues Passwort setzen (*Users → sensor-0001 → Credentials → Reset password*) und es einmal ins Gerät geben | kein Passwort auf dem Gerät | Handarbeit nach jeder langen Pause |
| Leerlauf hochsetzen, z. B. 365 Tage, in einem eigenen Realm für die Geräte | lange Pausen überstehen | ein verlorenes Token gilt länger, wenn es nicht gesperrt wird |

Ein Gerät, das bei Bedarf auf das Passwort zurückfällt:

```sh
token() {
  RESPONSE=$(curl -s -d grant_type=refresh_token -d client_id=devices -d client_secret=CLIENT-GEHEIM \
    -d refresh_token="$(cat offline-token 2>/dev/null)" "$KC/realms/$REALM/protocol/openid-connect/token")
  if [ "$(echo "$RESPONSE" | jq -r .error)" = invalid_grant ] && [ -f password ]; then
    # Offline-Token abgelaufen oder verloren: einmal neu anmelden
    RESPONSE=$(curl -s -d grant_type=password -d client_id=devices -d client_secret=CLIENT-GEHEIM \
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

**Ein Gerät stoppen**, in Keycloak:

- Benutzer → *Enabled* aus. Danach bekommt das Gerät keine Tokens mehr („User disabled“).
- Oder dem Benutzer den Offline-Zugriff entziehen (**Users → sensor-0001 → Consents** bzw. Admin-API, siehe Abschnitt 6). Dann
  wird das gespeicherte Offline-Token ungültig („Offline user session not found“), und das Gerät braucht wieder sein Passwort.

Beides wirkt erst beim nächsten Token. Das Access-Token, mit dem das Gerät gerade verbunden ist, gilt bis zu seinem Ablauf. Um es
sofort zu trennen, zusätzlich in mosquitto-ui den Benutzernamen sperren (Abschnitt 7).

## 3. In mosquitto-ui eintragen

Seite **Token-Anmeldung**:

| Feld | Wert |
|---|---|
| Issuer | `KC/realms/REALM`, genau wie im Claim `iss`, z. B. `https://keycloak.example.com/realms/devices` |
| Audience | `mqtt` |
| Claim mit dem Benutzernamen | `preferred_username` |
| Claim mit den Gruppen | `groups` (oder `realm_access.roles`) |
| Client-ID an den Benutzernamen binden | an |
| Gruppen und Rollen | `sensors` → eine Rolle aus *Rollen*, z. B. mit `publishClientSend sensors/%u/#` |

Danach *Token-Anmeldung an* einschalten und *Speichern*. Unter dem Issuer muss nun stehen: „1 Schlüssel … abgerufen“.

`%u` in den Regeln einer Rolle ist der Benutzername, z. B. `sensors/service-account-sensor-1/#`.

## 4. Token per curl holen und damit verbinden

```sh
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=sensor-1 -d client_secret=GEHEIM \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
```

Gleichwertig, mit dem Secret per Basic-Auth statt im Body:

```sh
TOKEN=$(curl -s -u sensor-1:GEHEIM -d grant_type=client_credentials \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
```

Was im Token steht (der mittlere Teil, base64url):

```sh
python3 -c 'import sys,json,base64; s=sys.argv[1].split(".")[1]; s+="="*(-len(s)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(s)), indent=2))' "$TOKEN"
```

Darin sollten stehen:
- `"iss": "https://keycloak.example.com/realms/devices"`
- `"aud"` mit `mqtt`
- `"preferred_username": "service-account-sensor-1"`
- `"groups": ["sensors"]`

Verbinden und senden:
- Die Client-ID (`-i`) muss bei gebundenem Issuer der Benutzername sein oder mit `<benutzername>/` beginnen. Warum das so ist
  und was das für `%c` in Regeln heißt, steht in Abschnitt 11.
- Der Benutzername (`-u`) ist beliebig.

```sh
mosquitto_pub -h "$BROKER" -p 8883 --capath /etc/ssl/certs \
  -i service-account-sensor-1/main -u token -P "$TOKEN" \
  -t sensors/service-account-sensor-1/temperature -m 21.5
```

Ein Gerät, das dauerhaft verbunden bleibt, holt nach jeder Trennung ein neues Token:

```sh
while true; do
  TOKEN=$(curl -s -u sensor-1:GEHEIM -d grant_type=client_credentials \
    "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
  mosquitto_sub -h "$BROKER" -p 8883 --capath /etc/ssl/certs \
    -i service-account-sensor-1/main -u token -P "$TOKEN" -t 'sensors/#'
  sleep 2
done
```

Bei einer Trennung versucht `mosquitto_sub` zunächst, sich mit dem alten Token wieder zu verbinden. Das lehnt der Broker ab, `mosquitto_sub` beendet sich, und die Schleife holt ein neues Token.

## 5. Token per Hand, für Menschen (Device-Flow)

Für einen Menschen statt eines Geräts, etwa zum Testen mit `mosquitto_sub`. Man meldet sich mit seinem eigenen Keycloak-Konto im Browser an, die Kommandozeile holt danach das Token.

Einmal einen Client dafür anlegen:

1. **Clients → Create client**, Client ID `mqtt-cli` → *Next*
2. *Client authentication* **aus**. Bei Authentication flow nur **OAuth 2.0 Device Authorization Grant** anhaken, *Standard flow* und *Direct access grants* aus → *Next* → *Save*.
3. Audience- und Group-Membership-Mapper wie in Abschnitt 1, Schritte 3 und 4.

Dann jedes Mal:

```sh
curl -s -d client_id=mqtt-cli "$KC/realms/$REALM/protocol/openid-connect/auth/device" | tee device.json | jq
```

`verification_uri_complete` im Browser öffnen, anmelden, bestätigen. Danach:

```sh
TOKEN=$(curl -s -d grant_type=urn:ietf:params:oauth:grant-type:device_code -d client_id=mqtt-cli \
  -d device_code=$(jq -r .device_code device.json) \
  "$KC/realms/$REALM/protocol/openid-connect/token" | jq -r .access_token)
```

Vor der Bestätigung im Browser antwortet Keycloak mit `authorization_pending`. Dann einfach noch einmal fragen. Der Benutzername am Broker ist der eigene, etwa `alice`, und die Client-ID entsprechend `alice/test`.

Zum Reiter *Client scopes → Evaluate* der Admin-Konsole: Er zeigt nur, **was** in einem Token stünde. Ein signiertes Token, das man als Passwort benutzen könnte, liefert er nicht.

## 6. Dasselbe über die Admin-API

Zum Beispiel, um viele Geräte anzulegen. Mit einem Admin-Token:

```sh
ADMIN=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=ADMIN -d password=ADMIN-PASSWORT \
  "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
API="$KC/admin/realms/$REALM"
```

Client mit Audience- und Group-Membership-Mapper:

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

Einen Gerätebenutzer anlegen (Abschnitt 2), gleich mit Gruppe und Passwort:

```sh
curl -s -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' "$API/users" -d '{
  "username": "sensor-0001", "enabled": true, "email": "sensor-0001@devices.invalid", "emailVerified": true,
  "firstName": "Sensor", "lastName": "0001", "groups": ["sensors"],
  "credentials": [{"type": "password", "value": "GERAETE-PASSWORT", "temporary": false}]}'
```

Ihm den Offline-Zugriff für den Client `devices` entziehen:

```sh
USER=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/users?username=sensor-0001&exact=true" | jq -r '.[0].id')
curl -s -X DELETE -H "Authorization: Bearer $ADMIN" "$API/users/$USER/consents/devices"
```

Für einen Client pro Gerät (Abschnitt 1) das Secret lesen und den Service-Account in die Gruppe `sensors` aufnehmen:

```sh
CID=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/clients?clientId=sensor-1" | jq -r '.[0].id')
curl -s -H "Authorization: Bearer $ADMIN" "$API/clients/$CID/client-secret" | jq -r .value
SA=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/clients/$CID/service-account-user" | jq -r .id)
GID=$(curl -s -H "Authorization: Bearer $ADMIN" "$API/groups?search=sensors" | jq -r '.[0].id')
curl -s -X PUT -H "Authorization: Bearer $ADMIN" "$API/users/$SA/groups/$GID"
```

## 7. Sperren

Ein verlorenes Gerät oder ein abgeflossenes Token lässt sich auf der Seite **Clients** (Knopf *Sperren* am Token-Client) oder auf der Seite **Token-Anmeldung** (Karte *Gesperrt*) sperren:

- **Nur dieses Token (`jti`):** Das Token kommt nicht mehr rein, ein neues Token des Geräts schon. Die Sperre muss nur so lange gelten wie das Token selbst.
- **Benutzername oder Subjekt (`sub`):** Jedes Token des Geräts bleibt draußen.

Der Broker trennt betroffene Verbindungen innerhalb einer Sekunde. Damit das Gerät auch kein neues Token mehr bekommt, zusätzlich in Keycloak den Client deaktivieren oder sein Secret neu erzeugen.

## 8. Abgelehnte Tokens

Die Seite **Token-Anmeldung** hat unten die Karte **Abgelehnte Tokens**. Dort steht jede Anmeldung mit einem Token, die der Broker
seit seinem letzten Start nicht angenommen hat, die neueste zuerst. Man muss also nicht mehr ins Log des Brokers schauen, um zu
sehen, warum ein Gerät nicht reinkommt.

Was zählt als Token: ein Passwort, das wie ein JWT aussieht (beginnt mit `ey`, drei Teile mit Punkten). Ein normales Passwort
eines Clients von Dynamic Security steht hier nie, auch kein falsches.

| Spalte | Inhalt |
|---|---|
| Zuletzt | Wann zuletzt. Versucht es derselbe Client mit demselben Aussteller, Namen und Grund noch einmal, wird kein neuer Eintrag angelegt, sondern gezählt („14 Mal“). So drängt ein Gerät, das sich jede Sekunde neu versucht, die anderen nicht hinaus. |
| Client | Client-ID und Adresse, von der die Verbindung kam (hinter einem Proxy mit PROXY-Protokoll die echte Adresse). |
| Benutzername | Der Benutzername, wie ihn das Token angibt, mit dem Präfix des Ausstellers. **nicht geprüft** heißt: Die Signatur hat nicht gestimmt (oder wurde gar nicht erst geprüft), der Name ist nur, was im Token steht, und kann von jedem kommen. |
| Grund | Was nicht gepasst hat, mit einem Hinweis, was zu tun ist (Tabelle unten). |
| Aussteller | Der Wert von `iss` im Token, auch wenn kein Aussteller so eingerichtet ist. Gerade dann hilft er: Meist ist es ein Tippfehler oder ein anderer Realm. |
| Knopf *Sperren* | Nur bei Tokens mit gültiger Signatur. Öffnet denselben Dialog wie auf der Seite Clients, mit `sub` und `jti` aus dem Token. |

Die Liste kommt vom Plugin im Broker. Sie hält höchstens 50 Einträge und beginnt nach jedem Neustart des Brokers leer. Das Plugin
veröffentlicht sie retained auf `$SYS/broker/token-auth/rejected`, als JSON-Liste mit `at` (Sekunden seit 1970), `count`,
`clientid`, `address`, `issuer`, `kid`, `username`, `sub`, `jti`, `verified` und `reason`. `sub` und `jti` stehen nur bei
gültiger Signatur drin. Über die API: `GET /api/token-auth/rejected`. Das Token selbst wird nirgends gespeichert.

| Grund (Code) | Bedeutung | Was tun |
|---|---|---|
| Kein JWT (`not_jwt`) | Sieht aus wie ein Token, lässt sich aber nicht lesen | Token ist abgeschnitten oder kaputt kopiert |
| Algorithmus nicht erlaubt (`algorithm`) | `alg` ist nicht RS*, PS* oder ES* | `none` und HS* (geteiltes Geheimnis) gehen nie |
| Kritischer Header (`critical_header`) | Header enthält `crit` | Wird grundsätzlich nicht angenommen |
| Unbekannter Aussteller (`unknown_issuer`) | Kein eingerichteter Aussteller hat diesen Wert von `iss` | Spalte *Aussteller* mit dem Eintrag vergleichen, auf `localhost` statt Hostname achten |
| Keine Signatur (`no_signature`) | Dritter Teil fehlt | Token vollständig schicken |
| Unbekannter Schlüssel (`unknown_key`) | Das Token nennt eine `kid`, die keiner der Schlüssel des Ausstellers hat | Meist nichts: mosquitto-ui lädt die Schlüssel sofort neu, siehe Abschnitt 9 |
| Signatur ungültig (`signature`) | Mit keinem Schlüssel des Ausstellers gültig | Token von einem anderen Realm oder Server, oder verändert |
| Kein Ablauf (`no_expiry`) | `exp` fehlt | Tokens ohne Ablauf werden nicht angenommen |
| Abgelaufen (`expired`) | `exp` liegt mehr als 30 Sekunden zurück | Neues Token holen, Uhren prüfen |
| Noch nicht gültig (`not_yet_valid`) | `nbf` liegt mehr als 30 Sekunden in der Zukunft | Uhr des Ausstellers oder Brokers falsch |
| Falsche Audience (`audience`) | `aud` enthält nicht die eingetragene Audience | Audience-Mapper in Keycloak prüfen |
| Kein Benutzername (`no_username`) | Der Claim für den Benutzernamen fehlt | Feld *Claim mit dem Benutzernamen* prüfen |
| Name gehört Dynamic Security (`username_reserved`) | Es gibt einen Client von Dynamic Security mit diesem Namen | Präfix beim Aussteller setzen |
| Client-ID passt nicht (`clientid`) | Bei gebundener Client-ID: `-i` ist weder der Benutzername noch beginnt sie mit `<benutzername>/` | Siehe Abschnitt 11 |
| Gesperrt (`blocked`) | Eine Sperre trifft zu | Karte *Gesperrt* |
| Keine Rolle (`no_role`) | Keine Gruppe des Tokens ist einer Rolle zugeordnet | Gruppen-Mapper, Gruppenmitgliedschaft und Zuordnung prüfen |
| Interner Fehler (`error`) | Speicher oder Broker | Log des Brokers |

## 9. Neue Schlüssel bei Keycloak

Keycloak signiert die Tokens mit einem Schlüssel des Realms. Den kann man unter **Realm settings → Keys** wechseln (rotieren),
etwa mit einem neuen *rsa-generated*-Provider mit höherer Priorität. Jedes Token nennt im Header die `kid` des Schlüssels, mit dem
es signiert ist.

mosquitto-ui ruft die Schlüssel aller Aussteller regelmäßig ab, standardmäßig alle 15 Minuten
(`mosquitto-ui.token-auth.key-refresh`). Bis dahin kennt der Broker einen neuen Schlüssel nicht. Deshalb gilt zusätzlich:

1. Ein Gerät meldet sich mit einem Token an, dessen `kid` der Broker nicht kennt.
2. Der Broker lehnt ab, Grund *Unbekannter Schlüssel*, und meldet das innerhalb einer Sekunde auf `$SYS`.
3. mosquitto-ui ruft sofort die Schlüssel dieses Ausstellers neu ab und schreibt sie dem Broker. Der liest sie innerhalb einer
   Sekunde.
4. Der nächste Versuch des Geräts klappt. Geräte, die sich nach einer Trennung von selbst neu verbinden (wie die Schleife in
   Abschnitt 4), merken davon nur eine kurze Verzögerung.

Damit niemand mit erfundenen `kid`s Keycloak mit Abrufen überschüttet, gelten Grenzen:

- Höchstens alle 30 Sekunden je Aussteller.
- Nur für eingerichtete Aussteller, deren Schlüssel von einer Adresse kommen (*Discovery* oder *JWKS-Adresse*). Bei eingefügten
  Schlüsseln gibt es nichts abzurufen: Dort muss man den neuen Schlüssel selbst einfügen.
- Nicht, wenn die Schlüssel ohnehin nach dem Token abgerufen wurden.

Kennt Keycloak die `kid` auch nach dem Abruf nicht, bleibt es bei *Unbekannter Schlüssel*: Dann kommt das Token nicht von diesem
Realm. Der Knopf *Schlüssel neu abrufen* auf der Seite geht weiterhin jederzeit.

Für einen geplanten Wechsel ohne jede Ablehnung: den neuen Schlüssel in Keycloak zuerst *passiv* anlegen (*Active* aus, *Enabled*
an). Er steht dann schon in der JWKS, signiert aber noch nicht. Nach dem nächsten Abruf (spätestens 15 Minuten, oder
*Schlüssel neu abrufen*) aktiv schalten. Den alten Schlüssel erst entfernen, wenn alle damit signierten Tokens abgelaufen sind.

## 10. Benachrichtigungen für Token-Clients

Die Mail „Client fehlt“ der Seite **Benachrichtigungen** gilt auch für Clients, die sich mit einem Token anmelden, ebenso die
Liste der fehlenden Clients im Dashboard. Es gelten dieselben Regeln wie für Clients von Dynamic Security:

- Gemerkt wird jeder Benutzername, der mit einem Token verbunden war. mosquitto-ui schaut alle 15 Sekunden nach, wer verbunden
  ist.
- „Fehlt“ heißt: Die letzte Verbindung hat mindestens eine Stunde gehalten, und der Client ist seit 15 Minuten weg. Geräte, die
  nur kurz einen Wert schicken und wieder gehen, fehlen also nie.
- Nach 7 Tagen ohne Verbindung fehlt ein Benutzername nicht mehr. Auf der Seite **Clients** bleibt er stehen, bis man ihn
  ausblendet (Abschnitt 12); ein ausgeblendeter fehlt ebenfalls nicht mehr.
- Mehrere Verbindungen mit demselben Benutzernamen (Client-IDs `sensor-1/a`, `sensor-1/b`) zählen als ein Client: Er fehlt erst,
  wenn keine mehr da ist.

Ein Token-Client, der gar nicht wiederkommen kann, fehlt nicht. Das ist der Fall, wenn

- die Token-Anmeldung aus ist,
- sein Aussteller entfernt wurde,
- sein Benutzername, `sub` oder `jti` gesperrt ist,
- oder sein Token von mosquitto-ui ausgestellt und zurückgenommen wurde oder abgelaufen ist.

Ein Token von Keycloak, das abgelaufen ist, zählt dagegen: Wenn das Gerät sich kein neues holt, ist das ein echter Ausfall.

Einzelne Clients schweigen lassen: auf der Seite **Benachrichtigungen** unter *Diese Clients nicht melden* den Benutzernamen
eintragen, wie er am Broker heißt, also mit Präfix des Ausstellers. Gemerkt werden die Verbindungen in
`token-client-logins.json` im Statusverzeichnis.

## 11. Ein Keycloak-Client, viele Benutzer: Regeln mit `%u`

Der übliche Aufbau ist der aus Abschnitt 2: **ein** Client in Keycloak (z. B. `devices` oder `mqtt`), **viele** Benutzer, je
Gerät oder je Mensch einer. Jeder Benutzer hat am Broker seinen eigenen Benutzernamen aus `preferred_username`. Die Rechte kommen
über Gruppen, die Trennung zwischen den Benutzern über `%u` in den Regeln.

Beispiel: Gruppe `sensors` → Rolle `sensors` mit

| Regel | Topic | Erlaubt |
|---|---|---|
| `publishClientSend` | `sensors/%u/#` | ja |
| `subscribePattern` | `commands/%u/#` | ja |
| `publishClientReceive` | `commands/%u/#` | ja |

Der Benutzer `sensor-0001` darf dann nach `sensors/sensor-0001/...` senden und `commands/sensor-0001/...` lesen, aber nichts
von `sensor-0002`. Eine Rolle reicht für alle Benutzer. Neue Geräte brauchen nur einen neuen Keycloak-Benutzer in der Gruppe,
in mosquitto-ui ändert sich nichts.

### Warum `%u` und nicht `%c`

*Client-ID an den Benutzernamen binden* (empfohlen, für neue Aussteller an) heißt: Die Client-ID muss genau der Benutzername sein,
oder mit `<benutzername>/` beginnen. Erlaubt für `sensor-0001` sind also `sensor-0001` und `sensor-0001/main`, nicht
`sensor-0002` und nicht `sensor-0001-x`. So kann niemand mit seinem gültigen Token die Sitzung eines anderen Benutzers übernehmen
(eine neue Verbindung mit derselben Client-ID wirft die alte hinaus und erbt deren Sitzung).

`%c` in einer Regel ist die Client-ID. Mosquitto setzt `%c` und `%u` aber immer nur für **eine Ebene** des Topics ein: Der
Vergleich endet am nächsten `/` des Topics. Eine Client-ID mit `/`, wie `sensor-0001/main`, passt deshalb auf keine Regel mit
`%c`. Das ist so in Mosquitto selbst (`libcommon/topic_common.c`, 2.1.2), Dynamic Security verhält sich genauso.

Daraus folgt:

- In Regeln für Token-Clients `%u` nehmen. Das passt immer und ist auch das, was man meint: Die Rechte hängen am Benutzer, nicht
  an der Verbindung.
- `%c` geht nur, wenn die Client-ID genau der Benutzername ist, ohne `/…`. Dann ist `%c` gleich `%u` und bringt nichts.
- Braucht ein Benutzer mehrere gleichzeitige Verbindungen, bekommen sie Client-IDs wie `sensor-0001/main` und `sensor-0001/ota`.
  Die Regeln mit `%u` gelten für alle davon.

Ein anderes Trennzeichen statt `/` (etwa `sensor-0001:main`, damit `%c` passt) ist bewusst nicht eingebaut. Es wäre nur sicher,
wenn das Zeichen in keinem Benutzernamen vorkommen kann. Mit `-` zum Beispiel dürfte der Benutzer `sensor` die Client-ID
`sensor-1-x` nehmen, die dem Benutzer `sensor-1` zusteht. Bei `/` kann das nicht passieren.

Ohne Bindung (Schalter aus) ist die Client-ID frei. Dann kann aber jeder Benutzer, dessen Token gilt, die Sitzung eines anderen
übernehmen, wenn er dessen Client-ID kennt. Das nur abschalten, wenn alle Benutzer eines Ausstellers einander vertrauen.

## 12. Token-Clients auf der Seite Clients

Unter der Tabelle von Dynamic Security steht auf der Seite **Clients** die Karte *Clients mit Access-Token*. Sie zeigt jeden
Benutzernamen, der sich irgendwann mit einem Token verbunden hat, nicht nur die gerade verbundenen. Zuerst kommen die
verbundenen, dann die anderen, die zuletzt gesehenen oben.

Suche und Filter oben auf der Seite gelten auch für diese Karte: die Suche für Benutzername, Client-ID, Rollen und Issuer,
*Verbunden* und *Nicht verbunden* nach der Verbindung, *Deaktiviert* für die, die nicht mehr reinkommen (Hinweis unten), und
die Rolle für die Rollen der letzten Verbindung. Die Rollen der Token-Clients stehen mit in der Auswahl. Ist ein Filter
gesetzt, steht im Titel, wie viele von allen angezeigt werden; passt keiner, fehlt die Karte.

| Spalte | Inhalt |
|---|---|
| Benutzername | wie am Broker, mit Präfix des Ausstellers; darunter ein Hinweis, wenn er nicht mehr reinkommt (siehe unten) |
| Client-ID | die Client-ID und Adresse der letzten Verbindung |
| Rollen | die Rollen der letzten Verbindung, so wie die Gruppen des Tokens sie ergaben |
| Zuletzt gesehen | grüner Punkt und „seit …“, wenn er verbunden ist; sonst wann er zuletzt da war |
| Token gültig bis / Issuer | das Ende des zuletzt benutzten Tokens und wer es ausgestellt hat |

Der Hinweis unter dem Benutzernamen sagt, warum er nicht mehr reinkommt:

| Hinweis | Bedeutung |
|---|---|
| Gesperrt | Eine Sperre auf seinen Benutzernamen, sein `sub` oder sein `jti` gilt. |
| Token zurückgenommen | Sein Token stammt von mosquitto-ui und wurde zurückgenommen oder ist abgelaufen. |
| Issuer entfernt | Sein Aussteller ist nicht mehr eingetragen. |
| Token-Anmeldung aus | Die Token-Anmeldung ist ausgeschaltet. |

Ein abgelaufenes Token von Keycloak gibt keinen Hinweis, denn das Gerät kann sich jederzeit ein neues holen.

Knöpfe am Ende der Zeile:

- **Sperren** öffnet denselben Dialog wie in Abschnitt 7, auch für einen, der gerade nicht verbunden ist. So lässt sich ein
  verlorenes Gerät sperren, bevor es sich wieder meldet. Bei einem gesperrten oder zurückgenommenen Token fehlt der Knopf.
- **Ausblenden** nimmt den Benutzernamen aus der Liste. Er fehlt dann auch nicht mehr (Abschnitt 10). Verbindet er sich
  wieder, steht er wieder da. Ausblenden sperrt nichts. Einen verbundenen kann man nicht ausblenden.

Die Liste kommt aus `token-client-logins.json` im Statusverzeichnis (nicht Teil des Backups). mosquitto-ui schaut alle 15
Sekunden nach, wer verbunden ist; eine Verbindung, die kürzer dauert, sieht es nicht, und solange die UI nicht läuft, auch
nichts. Mehrere Verbindungen mit demselben Benutzernamen stehen als eine Zeile da, mit der ersten Client-ID, die der Broker
nennt. Einträge, die 0.19.0 geschrieben hat, haben noch keine Client-ID, Rollen und Ablaufzeit, bis sich der Client wieder
verbindet.

Über die API: `GET /api/token-auth/seen` liefert alle gesehenen mit `connected`, `lastSeen` und `lockedOut` (`null`,
`disabled`, `issuer`, `revoked` oder `blocked`). `DELETE /api/token-auth/seen/{benutzername}` blendet einen aus; das gibt 409,
solange er verbunden ist, und 404, wenn er nicht in der Liste steht.

## Fehlersuche

Zuerst die Karte *Abgelehnte Tokens* ansehen (Abschnitt 8). 
Außerdem schreibt der Broker bei jeder Ablehnung den Grund ins Log, nie das Token selbst:

```sh
docker compose logs mosquitto | grep token-auth
```

| Im Log | Ursache |
|---|---|
| `not accepted: unknown issuer` | `iss` im Token weicht vom eingetragenen Issuer ab. Das passiert schon, wenn Keycloak unter einer anderen Adresse aufgerufen wurde, etwa `localhost` statt seines Namens. |
| `not accepted: wrong audience` | Audience-Mapper fehlt oder hat einen anderen Wert. |
| `not accepted: no key with the kid of the token` | Keycloak hat einen neuen Schlüssel. mosquitto-ui ruft die Schlüssel von selbst neu ab, siehe Abschnitt 9. |
| `not accepted: signature not valid` | Das Token passt zu keinem Schlüssel des Ausstellers: anderer Realm oder Server, oder verändert. |
| `not accepted: expired` | Token abgelaufen. Ein neues holen, und die Uhren von Gerät und Broker prüfen. |
| `not accepted: … has no role` | Keine Gruppe im Token ist einer Rolle zugeordnet: Mapper, Gruppenmitgliedschaft und Zuordnung in mosquitto-ui prüfen. |
| `not accepted: client id does not belong to the user name` | `-i` passt nicht zum Benutzernamen, siehe Abschnitte 4 und 11. |
| `not accepted: blocked` | Gesperrt, siehe Seite *Token-Anmeldung*. |
| Keycloak: `Account is not fully set up` | Gerätebenutzer ohne E-Mail, Vor- oder Nachname, siehe Abschnitt 2. |
| `not accepted: user name taken by dynamic security` | Den Namen gibt es schon als Benutzer in Dynamic Security. Ein Präfix für den Issuer setzen. |
| `disconnected, its token expired or lost its rights` | Token abgelaufen, Rolle entzogen oder gesperrt. |
