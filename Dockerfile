FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /app
RUN command -v curl >/dev/null && mkdir -p /app/.local/logs && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/target/account-service.jar ./account-service.jar
COPY docker/app-entrypoint.sh /usr/local/bin/account-service
RUN chmod 755 /usr/local/bin/account-service
USER 10001:10001
EXPOSE 8080 8443
ENTRYPOINT ["/usr/local/bin/account-service"]
