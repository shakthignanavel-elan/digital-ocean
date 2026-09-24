# Multi-module Spring Boot image.
# Build: docker build --build-arg MODULE=rate-limiter-dataplane -t rate-limiter-dataplane .
#        docker build --build-arg MODULE=rate-limiter-controlplane -t rate-limiter-controlplane .
ARG MODULE=rate-limiter-dataplane

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY rate-limiter-common/pom.xml rate-limiter-common/
COPY rate-limiter-dataplane/pom.xml rate-limiter-dataplane/
COPY rate-limiter-controlplane/pom.xml rate-limiter-controlplane/

# Warm the dependency cache before copying sources (faster rebuilds).
RUN mvn -B -q -pl rate-limiter-common,rate-limiter-dataplane,rate-limiter-controlplane -am dependency:go-offline || true

COPY rate-limiter-common rate-limiter-common
COPY rate-limiter-dataplane rate-limiter-dataplane
COPY rate-limiter-controlplane rate-limiter-controlplane

ARG MODULE
RUN mvn -B -pl "${MODULE}" -am -DskipTests package \
    && cp "${MODULE}/target/${MODULE}-"*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S app && adduser -S app -G app

COPY --from=build /workspace/app.jar /app/app.jar
USER app

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080 8081

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
