# The broker of mosquitto-ui: Mosquitto with the agent the UI talks to and a configuration to start from. Released together with
# the image of the UI, as the agent and the UI have to fit each other (.gitea/workflows/release.yml).

# The plugins of mosquitto-ui: token login (token-auth/token_auth.c) and the look into the broker (inspect/inspect.c), built
# against the headers of the same Mosquitto version and the Alpine release of the image below, so that OpenSSL and cJSON are
# the ones the broker has.
FROM alpine:3.23.7 AS token-auth
# Only include/ is needed. It comes from the archive of the tag on GitHub (the same headers as the tarball on mosquitto.org,
# which drops connections from GitHub's runners now and then).
ARG MOSQUITTO_VERSION=2.1.2
ARG MOSQUITTO_SHA256=20e998aae86d1629e787a924c044bb912d21ac8f5d1c1b707f2af06eb4c6016d
RUN apk add --no-cache build-base openssl-dev cjson-dev curl
RUN curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 -o /tmp/mosquitto.tar.gz \
        https://github.com/eclipse-mosquitto/mosquitto/archive/refs/tags/v${MOSQUITTO_VERSION}.tar.gz \
    && echo "${MOSQUITTO_SHA256}  /tmp/mosquitto.tar.gz" | sha256sum -c - \
    && mkdir -p /usr/local/include/mosquitto-src \
    && tar xzf /tmp/mosquitto.tar.gz -C /usr/local/include/mosquitto-src --strip-components=2 mosquitto-${MOSQUITTO_VERSION}/include
COPY token-auth /src/token-auth
RUN make -C /src/token-auth
COPY inspect /src/inspect
RUN make -C /src/inspect

FROM eclipse-mosquitto:2.1.2-alpine

COPY --from=token-auth /src/token-auth/mosquitto_token_auth.so /usr/lib/mosquitto_token_auth.so
COPY --from=token-auth /src/inspect/mosquitto_inspect.so /usr/lib/mosquitto_inspect.so
COPY mosquitto.conf /mosquitto/config/mosquitto.conf
COPY agent.sh /mosquitto/agent.sh

CMD ["sh", "/mosquitto/agent.sh"]
