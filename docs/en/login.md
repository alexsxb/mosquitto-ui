# Login

Who logs in to the UI and how: with **UI users** (user name and password, managed by the UI itself) and with
one or more **identity providers** (OpenID Connect or GitHub). The page **Broker → Login** sets up both.
Applies from mosquitto-ui 0.26.0.

## Ways in

- **Local login:** on as long as no provider is on, otherwise a switch on the page Login. `MOSQUITTO_UI_AUTH=local`
  always keeps it on, whatever the page says: the way back when nobody can log in at the provider any more.
- **Providers:** every provider that is on gets a button "Log in with …" on the start page.
- The UI does not let the last way in disappear: you can only turn the local login off once a provider
  is on, and the last provider only once the local login is on and there is a UI user.

## From older versions

If `QUARKUS_OIDC_AUTH_SERVER_URL`, `QUARKUS_OIDC_CLIENT_ID` and `QUARKUS_OIDC_CREDENTIALS_SECRET` are in the environment,
the first start of 0.26 turns them into a provider (for Keycloak with the template Keycloak). Who may come in stays as before:
the role `MOSQUITTO_UI_ADMIN_ROLE` (default `mosquitto-admin`). The local login stays off, no `admin` is created.
After that the UI no longer reads these variables, you can remove them from the environment.

**The callback address is new:** `https://<host>/auth/sso/<id>`, for the taken-over Keycloak `https://<host>/auth/sso/keycloak`.
If the client in Keycloak has `https://<host>/*` as Valid redirect URI, it already fits. Otherwise add the address there.

## Setting up a provider

**Add provider** shows templates:

| Group | Templates |
|---|---|
| Self-hosted | Keycloak, authentik, Authelia, ZITADEL, Pocket ID, Kanidm |
| Cloud accounts | Google, Microsoft Entra ID, Apple |
| Code platforms | GitHub, GitLab, Gitea / Forgejo |
| Enterprise IdP | Okta, Auth0, Amazon Cognito |
| Other | OpenID Connect (any provider with discovery) |

The template fills in the scopes and where the roles are in the token, and says what to set up at the provider. Always the same:
a confidential client with authorization code flow, with the **callback address** as redirect URI, which the dialog shows for
copying. **Test** checks whether the address of the provider answers as an OpenID provider. Under **Advanced**: scopes, role claims
(paths with `/`, `{clientId}` is replaced) and which token the roles come from.

GitHub is not an OpenID provider, there the UI queries the account and, for organisations and teams, the memberships with
`read:org`. Instead of a secret, Apple needs the private key (`.p8`) with Key ID and Team ID.

## Who may come in

Per provider, one rule that fits is enough:

- **Everyone who can log in at the provider:** when the provider itself decides who may use the client. Not for Google,
  GitHub, Apple, where everyone has an account.
- **Roles or groups** from the token (Keycloak, authentik, Entra ID, Okta …).
- **Organisations and teams** (GitHub): `org` or `org/team`.
- **People allowed:** e-mail address, `*@example.org` for a whole domain, or user name
  (`preferred_username`, for GitHub the login). An e-mail address does not count if the provider reports it as unverified
  (`email_verified: false`). Optionally **as UI user**: the login then counts as that user (audit log, header),
  and it ends when that user's password changes or the user is deleted.

Whoever logs in and fits no rule does not get in, sees why on the start page and shows up on the page Login
under **logins turned away** (the last 20, with the roles from the token). **Let in** lets the person in with one
click, under their own name or as a UI user.

## Session

After the login at the provider, the session of the UI applies (cookie `mosquitto-ui-session`, as with the local login); the one of the
OIDC extension is discarded. It ends

- after 8 hours without a request (`MOSQUITTO_UI_LOCAL_AUTH_SESSION_TIMEOUT`) and at the latest 7 days after the login
  (`MOSQUITTO_UI_LOCAL_AUTH_SESSION_MAX`),
- for all sessions of a provider when its connection settings change, it is turned off or fewer people
  may come in (a new name or more people allowed leave them in place),
- for the local login when the local login is turned off.

**Log out** also logs out at the provider if it supports that (`end_session_endpoint`), with the ID token as `id_token_hint`
(in a separate encrypted cookie that only goes to `/api/auth/logout`), then it goes back to the start page. For this,
the provider has to allow the start page of the UI as post logout redirect URI.

## The first UI user

If the UI starts with the local login and without users, it creates `admin` (`MOSQUITTO_UI_LOCAL_AUTH_INITIAL_USER`):

- with `MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD` if set (at least 12 characters), otherwise
- with a generated password in `<state directory>/initial-password` (readable only by the user of the UI). The file
  disappears after the first login of `admin` or as soon as its password has changed.
  - Docker: `docker compose exec mosquitto-ui cat /var/lib/mosquitto-ui/initial-password`
  - without Docker: `sudo cat /var/lib/mosquitto-ui/state/initial-password`

If you locked yourself out: set `MOSQUITTO_UI_AUTH=local` and restart, then the local login applies. If all
passwords are forgotten too: stop the UI, delete `users.json` in the state directory, start with `MOSQUITTO_UI_AUTH=local`. Then
`admin` is created again.

## The page UI users

Create users, set passwords, end sessions, delete; also when the local login is off (for mapping
people of a provider). Every user manages the broker, there are no roles. You cannot delete your own user,
nor the last one.

## Security

- **Passwords:** at least 12 characters, PBKDF2-HMAC-SHA256 with 600 000 iterations and salt, in `users.json` in the
  state directory.
- **Providers:** in `sso.json` in the state directory, with the client secrets. Both files are encrypted with
  `MOSQUITTO_UI_SECRET_KEY`.
- **Backup:** providers and UI users belong to the part **Access** (encrypted only). On restore, the providers replace
  the existing ones, UI users are added, existing ones keep their password. Whether the local login is on stays
  as on the server you restore to.
- **Code flow:** PKCE (except GitHub), state and nonce are checked by the OIDC extension of Quarkus, as are signature, issuer,
  audience and expiry of the ID token. The cookies of the code flow only apply under `/auth/sso/<id>`. If the provider cannot
  be reached, you go back to the start page with a message, the reason is in the log.
- **Session cookie:** AES-GCM with the secret key, `HttpOnly`, `SameSite=Lax`, `Secure` as soon as the page comes over HTTPS
  (also behind a proxy with `X-Forwarded-Proto`). Nothing is stored on the server. A new secret key ends all sessions.
- **Brute force** (local login): after 5 failed attempts in 15 minutes for a name or from an address, every
  further attempt waits (`MOSQUITTO_UI_LOCAL_AUTH_MAX_FAILURES`, `MOSQUITTO_UI_LOCAL_AUTH_FAILURE_WINDOW`). Behind a proxy,
  the address from `X-Forwarded-For` counts if the proxy is trusted.
- **CSRF:** logging in and changing things need the header `X-Requested-With`, like the whole API.
- Logins, rejections and failed attempts are in the **audit log** with provider and address.
- Without TLS, passwords and codes go over the network in plain text: put the UI behind a reverse proxy with HTTPS. Many
  providers (Google, Apple, Entra ID) only allow HTTPS callback addresses anyway.

## API

| Call | Purpose |
|---|---|
| `GET /api/auth/mode` | `{ local, providers: [{ id, name, template }] }`, open to everyone |
| `POST /api/auth/login` | `{ username, password }`, 204 with cookie; 401 wrong, 404 local login off, 429 too many attempts |
| `POST /api/auth/logout` | deletes the cookie; `{ redirect }`: where to go to log out at the provider |
| `GET /auth/sso/{id}` | login through the provider (code flow), then back to the start page |
| `GET /api/sso` | local login, providers (without secrets), templates |
| `PUT /api/sso/local-login` | `{ enabled }` |
| `POST /api/sso/providers`, `PUT`/`DELETE /api/sso/providers/{id}` | create, change (`clientSecret: null` keeps it), delete a provider |
| `POST /api/sso/providers/{id}/allow` | `{ match, localUser }`: let a person in |
| `DELETE /api/sso/providers/{id}/denied` | clear the list of logins turned away |
| `POST /api/sso/discover` | `{ issuer }`: does the address answer as an OpenID provider? |
| `GET/POST /api/local-users` | list UI users, create `{ username, password }` |
| `PUT /api/local-users/{name}/password` | new password `{ password }` |
| `POST /api/local-users/{name}/end-sessions` | end all sessions |
| `DELETE /api/local-users/{name}` | delete |
