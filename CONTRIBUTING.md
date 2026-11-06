# Contributing

Issues and pull requests are welcome: bug reports, ideas, fixes, translations.

## Pull requests

The build runs the tests on every pull request. It helps if a pull request

- does one thing, and says why in its description;
- is based on the current `main`;
- passes the tests (see below);
- keeps the style around it: code comments, commit messages and the README in English.

For larger changes please open an issue first, so we can agree on the way before you put work in.

## Building and testing

```shell
./mvnw quarkus:dev     # the app on http://localhost:8090, with Mosquitto and Keycloak from docker/compose.yaml
./mvnw verify          # all tests; needs Docker (Testcontainers start Mosquitto and an ACME server)
```

After changing the API, update the types of the frontend:

```shell
./mvnw package -DskipTests -Dquarkus.quinoa=false && (cd src/main/webui && npm run gen:api)
```

The frontend lives in `src/main/webui` (React, TypeScript). `npm run build` there checks the types with `tsc -b`.

## Texts and translations

The UI comes in German, English and French (`src/main/webui/src/i18n/de.ts`, `en.ts`, `fr.ts`); a new text needs all three.
The documents in `docs/` are in German with an English version in `docs/en/`, the handbook in `docs/handbuch/` in all three
languages.

## License

By contributing you agree that your contribution is licensed under the GNU Affero General Public License v3.0, as the rest
of the project ([LICENSE](LICENSE)).

## Security

Please do not report security problems in a public issue. Use GitHub's private vulnerability reporting
(Security → Report a vulnerability) instead.
