# Anmeldung

Wer sich bei der UI anmeldet und wie: mit **UI-Benutzern** (Benutzername und Passwort, von der UI selbst verwaltet) und mit
einem oder mehreren **Identity Providern** (OpenID Connect oder GitHub). Beides richtet die Seite **Broker → Anmeldung** ein.
Gilt ab mosquitto-ui 0.26.0.

## Wege hinein

- **Lokaler Login:** an, solange kein Provider an ist, sonst ein Schalter auf der Seite Anmeldung. `MOSQUITTO_UI_AUTH=local`
  hält ihn immer an, egal was die Seite sagt: der Weg zurück, wenn sich beim Provider niemand mehr anmelden kann.
- **Provider:** jeder, der an ist, bekommt auf der Startseite einen Button „Anmelden mit …“.
- Die UI lässt nicht zu, dass der letzte Weg hinein verschwindet: Den lokalen Login schaltet man erst aus, wenn ein Provider
  an ist, und den letzten Provider erst, wenn der lokale Login an ist und es einen UI-Benutzer gibt.

## Von älteren Versionen

Stehen `QUARKUS_OIDC_AUTH_SERVER_URL`, `QUARKUS_OIDC_CLIENT_ID` und `QUARKUS_OIDC_CREDENTIALS_SECRET` in der Umgebung, wird
daraus beim ersten Start von 0.26 ein Provider (bei Keycloak mit der Vorlage Keycloak). Wer hinein darf, bleibt wie bisher:
die Rolle `MOSQUITTO_UI_ADMIN_ROLE` (Standard `mosquitto-admin`). Der lokale Login bleibt aus, es entsteht kein `admin`.
Danach liest die UI diese Variablen nicht mehr, sie können aus der Umgebung verschwinden.

**Neu ist die Rückruf-Adresse:** `https://<host>/auth/sso/<id>`, beim übernommenen Keycloak `https://<host>/auth/sso/keycloak`.
Steht beim Client in Keycloak als Valid redirect URI `https://<host>/*`, passt sie schon. Sonst die Adresse dort ergänzen.

## Einen Provider einrichten

**Provider hinzufügen** zeigt Vorlagen:

| Gruppe | Vorlagen |
|---|---|
| Selbst betrieben | Keycloak, authentik, Authelia, ZITADEL, Pocket ID, Kanidm |
| Cloud | Google, Microsoft Entra ID, Apple |
| Code-Plattformen | GitHub, GitLab, Gitea / Forgejo |
| Firmen-IdP | Okta, Auth0, Amazon Cognito |
| Andere | OpenID Connect (jeder Provider mit Discovery) |

Die Vorlage füllt Scopes und die Stellen der Rollen im Token aus und sagt, was beim Provider einzurichten ist. Immer gleich:
ein vertraulicher Client mit Authorization Code Flow, als Redirect URI die **Rückruf-Adresse**, die der Dialog zum Kopieren
zeigt. **Testen** prüft, ob die Adresse des Providers als OpenID-Provider antwortet. Unter **Erweitert**: Scopes, Rollen-Claims
(Pfade mit `/`, `{clientId}` wird ersetzt) und aus welchem Token die Rollen kommen.

GitHub ist kein OpenID-Provider, die UI fragt dort das Konto und, für Organisationen und Teams, mit `read:org` die
Mitgliedschaften ab. Apple braucht statt eines Secrets den privaten Schlüssel (`.p8`) mit Key ID und Team ID.

## Wer hinein darf

Pro Provider, eine passende Regel genügt:

- **Jeder, der sich beim Provider anmelden kann:** wenn der Provider selbst festlegt, wer den Client benutzen darf. Bei Google,
  GitHub, Apple hat jeder ein Konto, dort nicht.
- **Rollen oder Gruppen** aus dem Token (Keycloak, authentik, Entra ID, Okta …).
- **Organisationen und Teams** (GitHub): `org` oder `org/team`.
- **Freigeschaltete Personen:** E-Mail-Adresse, `*@example.org` für eine ganze Domain, oder Benutzername
  (`preferred_username`, bei GitHub der Login). Eine E-Mail-Adresse zählt nicht, wenn der Provider sie als unbestätigt meldet
  (`email_verified: false`). Optional **als UI-Benutzer**: Die Anmeldung gilt dann als dieser Benutzer (Protokoll, Kopfzeile),
  und sie endet, wenn dessen Passwort sich ändert oder er gelöscht wird.

Wer sich anmeldet und keine Regel erfüllt, kommt nicht hinein, sieht auf der Startseite warum und steht auf der Seite Anmeldung
unter **Anmeldungen abgewiesen** (die letzten 20, mit den Rollen aus dem Token). **Freischalten** lässt die Person mit einem
Klick hinein, unter eigenem Namen oder als UI-Benutzer.

## Sitzung

Nach der Anmeldung beim Provider gilt die Sitzung der UI (Cookie `mosquitto-ui-session`, wie beim lokalen Login), die der
OIDC-Erweiterung wird verworfen. Sie endet

- nach 8 Stunden ohne Anfrage (`MOSQUITTO_UI_LOCAL_AUTH_SESSION_TIMEOUT`) und spätestens 7 Tage nach der Anmeldung
  (`MOSQUITTO_UI_LOCAL_AUTH_SESSION_MAX`),
- für alle Sitzungen eines Providers, wenn sich seine Verbindungsdaten ändern, er ausgeschaltet wird oder weniger Personen
  hinein dürfen (ein neuer Name oder mehr Freigeschaltete lassen sie stehen),
- beim lokalen Login, wenn der lokale Login ausgeschaltet wird.

**Abmelden** meldet auch beim Provider ab, wenn er das kann (`end_session_endpoint`), mit dem ID-Token als `id_token_hint`
(in einem eigenen verschlüsselten Cookie, das nur an `/api/auth/logout` geht), danach geht es zurück zur Startseite. Dafür
muss beim Provider die Startseite der UI als Post logout redirect URI erlaubt sein.

## Der erste UI-Benutzer

Startet die UI mit lokalem Login und ohne Benutzer, legt sie `admin` an (`MOSQUITTO_UI_LOCAL_AUTH_INITIAL_USER`):

- mit `MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD`, wenn gesetzt (mindestens 12 Zeichen), sonst
- mit einem erzeugten Passwort in `<Statusverzeichnis>/initial-password` (nur für den Benutzer der UI lesbar). Die Datei
  verschwindet nach der ersten Anmeldung von `admin` oder sobald sein Passwort geändert ist.
  - Docker: `docker compose exec mosquitto-ui cat /var/lib/mosquitto-ui/initial-password`
  - ohne Docker: `sudo cat /var/lib/mosquitto-ui/state/initial-password`

Wer sich ausgesperrt hat: `MOSQUITTO_UI_AUTH=local` setzen und neu starten, dann gilt der lokale Login. Sind auch alle
Passwörter vergessen: UI stoppen, `users.json` im Statusverzeichnis löschen, mit `MOSQUITTO_UI_AUTH=local` starten. Dann
entsteht `admin` neu.

## Die Seite UI-Benutzer

Benutzer anlegen, Passwort setzen, Sitzungen beenden, löschen; auch wenn der lokale Login aus ist (für die Zuordnung von
Personen eines Providers). Jeder Benutzer verwaltet den Broker, Rollen gibt es nicht. Den eigenen Benutzer kann man nicht
löschen, den letzten auch nicht.

## Sicherheit

- **Passwörter:** mindestens 12 Zeichen, PBKDF2-HMAC-SHA256 mit 600 000 Durchläufen und Salz, in `users.json` im
  Statusverzeichnis.
- **Provider:** in `sso.json` im Statusverzeichnis, mit den Client-Secrets. Beide Dateien sind verschlüsselt mit
  `MOSQUITTO_UI_SECRET_KEY`.
- **Backup:** Provider und UI-Benutzer gehören zum Abschnitt **Zugriff** (nur verschlüsselt). Beim Wiederherstellen ersetzen
  die Provider die vorhandenen, UI-Benutzer kommen dazu, vorhandene behalten ihr Passwort. Ob der lokale Login an ist, bleibt
  wie auf dem Server, auf dem wiederhergestellt wird.
- **Code-Flow:** PKCE (außer GitHub), State und Nonce prüft die OIDC-Erweiterung von Quarkus, ebenso Signatur, Aussteller,
  Zielgruppe und Ablauf des ID-Tokens. Die Cookies des Code-Flows gelten nur unter `/auth/sso/<id>`. Ist der Provider nicht
  erreichbar, geht es mit einer Meldung zurück zur Startseite, der Grund steht im Log.
- **Sitzungs-Cookie:** AES-GCM mit dem Secret Key, `HttpOnly`, `SameSite=Lax`, `Secure`, sobald die Seite über HTTPS kommt
  (auch hinter einem Proxy mit `X-Forwarded-Proto`). Auf dem Server liegt nichts. Ein neuer Secret Key beendet alle Sitzungen.
- **Durchprobieren** (lokaler Login): Nach 5 Fehlversuchen in 15 Minuten für einen Namen oder von einer Adresse wartet jeder
  weitere Versuch (`MOSQUITTO_UI_LOCAL_AUTH_MAX_FAILURES`, `MOSQUITTO_UI_LOCAL_AUTH_FAILURE_WINDOW`). Hinter einem Proxy zählt
  die Adresse aus `X-Forwarded-For`, wenn der Proxy vertraut ist.
- **CSRF:** Anmelden und Ändern brauchen den Header `X-Requested-With`, wie die ganze API.
- Anmeldungen, Abweisungen und Fehlversuche stehen im **Protokoll** mit Provider und Adresse.
- Ohne TLS gehen Passwörter und Codes im Klartext übers Netz: die UI hinter einen Reverse Proxy mit HTTPS stellen. Viele
  Provider (Google, Apple, Entra ID) erlauben ohnehin nur HTTPS-Rückruf-Adressen.

## API

| Aufruf | Zweck |
|---|---|
| `GET /api/auth/mode` | `{ local, providers: [{ id, name, template }] }`, offen für alle |
| `POST /api/auth/login` | `{ username, password }`, 204 mit Cookie; 401 falsch, 404 lokaler Login aus, 429 zu viele Versuche |
| `POST /api/auth/logout` | löscht das Cookie; `{ redirect }`: wohin zum Abmelden beim Provider |
| `GET /auth/sso/{id}` | Anmeldung über den Provider (Code-Flow), danach zurück zur Startseite |
| `GET /api/sso` | lokaler Login, Provider (ohne Secrets), Vorlagen |
| `PUT /api/sso/local-login` | `{ enabled }` |
| `POST /api/sso/providers`, `PUT`/`DELETE /api/sso/providers/{id}` | Provider anlegen, ändern (`clientSecret: null` lässt es), löschen |
| `POST /api/sso/providers/{id}/allow` | `{ match, localUser }`: eine Person freischalten |
| `DELETE /api/sso/providers/{id}/denied` | die Liste der Abgewiesenen leeren |
| `POST /api/sso/discover` | `{ issuer }`: antwortet die Adresse als OpenID-Provider? |
| `GET/POST /api/local-users` | UI-Benutzer auflisten, anlegen `{ username, password }` |
| `PUT /api/local-users/{name}/password` | neues Passwort `{ password }` |
| `POST /api/local-users/{name}/end-sessions` | alle Sitzungen beenden |
| `DELETE /api/local-users/{name}` | löschen |
