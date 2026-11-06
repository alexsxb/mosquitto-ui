# syntax=docker/dockerfile:1
# mosquitto-ui: build and runtime on Alpine with Azul Zulu.
#   docker build -t mosquitto-ui .
# With an application built beforehand (release.yml builds it in the CI job), the build stage is skipped:
#   docker build --build-context quarkus-app=target/quarkus-app -t mosquitto-ui .

# Pinned by digest, so that a changed image under the same tag never ends up in a release. To take JDK updates:
#   docker buildx imagetools inspect azul/zulu-openjdk-alpine:21-jre-headless-latest   (and 21-latest)
# and put the new digests here.
ARG ZULU_JDK=azul/zulu-openjdk-alpine:21-latest@sha256:79fcb5d91dc3864f72739c3861a057ff51289c84c92b2b2a6ea056673200db8e
ARG ZULU_JRE=azul/zulu-openjdk-alpine:21-jre-headless-latest@sha256:528cbafe8bea9f600b7a3ec27e76b06549b274cd38ba36e84c74f46eeeefb7a4

# --- build -------------------------------------------------------------------------------------------------------
FROM ${ZULU_JDK} AS build
# Application version, set by the release workflow from the tag (default: the Maven version)
ARG VERSION=
# Node from Alpine: Quinoa builds the React frontend with the npm on the PATH (downloaded Node builds need glibc)
RUN apk add --no-cache bash nodejs npm
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.npm \
    ./mvnw -B -ntp package -DskipTests -Dquarkus.quinoa.package-manager-install=false \
       ${VERSION:+-Dquarkus.application.version=$VERSION}

# The built application (fast-jar); replaced by "--build-context quarkus-app=<dir>"
FROM scratch AS quarkus-app
COPY --from=build /src/target/quarkus-app/ /

# --- runtime -----------------------------------------------------------------------------------------------------
FROM ${ZULU_JRE}
# The directories exist with this owner, so that new volumes get it; /mosquitto/ui is opened up by the agent
RUN addgroup -S -g 1001 mosquitto-ui \
    && adduser -S -D -H -u 1001 -G mosquitto-ui mosquitto-ui \
    && mkdir -p /var/lib/mosquitto-ui /mosquitto/ui /backups \
    && chown -R mosquitto-ui:mosquitto-ui /var/lib/mosquitto-ui /mosquitto/ui /backups
WORKDIR /app
# Fast-jar layout, dependencies first for better layer reuse
COPY --from=quarkus-app --chown=1001:1001 /lib/ /app/lib/
COPY --from=quarkus-app --chown=1001:1001 /*.jar /app/
COPY --from=quarkus-app --chown=1001:1001 /app/ /app/app/
COPY --from=quarkus-app --chown=1001:1001 /quarkus/ /app/quarkus/
USER 1001

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
EXPOSE 8080
VOLUME /var/lib/mosquitto-ui /backups
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/q/health/ready || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
