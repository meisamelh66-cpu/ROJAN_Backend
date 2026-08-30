# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl only: needed by both this image's own HEALTHCHECK and
# docker-compose.prod.yml's container healthcheck
# (`curl -f http://localhost:8080/actuator/health`) — no other change to
# this stage.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --uid 10001 --shell /usr/sbin/nologin --no-create-home rojan

# JAR is built on the host (gradlew.bat :bootstrap:bootJar) and copied in
# as-is — Gradle no longer runs inside Docker. See .dockerignore for the
# matching exception that lets this path through the build context.
COPY bootstrap/build/libs/bootstrap-1.0.0.jar app.jar
RUN chown rojan:rojan app.jar

# Bind-mounted to /opt/rojan/logs by docker-compose.prod.yml — created and
# owned by the runtime user up front so the mount target is writable
# without requiring the container to ever run as root at boot.
RUN mkdir -p /app/logs && chown rojan:rojan /app/logs

# LocalMediaStorageProperties' real default (MEDIA_STORAGE_ROOT, application.yml)
# is /app/uploads - same reasoning as /app/logs above: without this, the
# non-root rojan user hits java.nio.file.AccessDeniedException the first
# time any real media upload runs, since /app itself is root-owned and a
# non-root user can't create a new directory under it at runtime.
RUN mkdir -p /app/uploads && chown rojan:rojan /app/uploads

USER rojan
EXPOSE 8080

# Image-level healthcheck, independent of whatever orchestrator runs this
# (docker-compose.prod.yml also defines its own — that one wins when
# compose is used, but this keeps the image itself honest under `docker
# run` or a non-compose orchestrator too).
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=12 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
