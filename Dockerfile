# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY domain domain
COPY application application
COPY infrastructure infrastructure
COPY api api
COPY bootstrap bootstrap

RUN chmod +x gradlew && ./gradlew :bootstrap:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl only: needed by docker-compose.prod.yml's container healthcheck
# (`curl -f http://localhost:8080/actuator/health`) — no other change to
# this stage.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --uid 10001 --shell /usr/sbin/nologin --no-create-home rojan
COPY --from=build /workspace/bootstrap/build/libs/*.jar app.jar
RUN chown rojan:rojan app.jar

USER rojan
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
