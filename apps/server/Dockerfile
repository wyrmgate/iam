# syntax=docker/dockerfile:1.7

FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY apps/server/pom.xml /workspace/apps/server/pom.xml
RUN mvn -f /workspace/apps/server/pom.xml dependency:go-offline

COPY apps/server /workspace/apps/server
RUN mvn -f /workspace/apps/server/pom.xml clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine-3.23
WORKDIR /app

RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 wyrmgate \
    && adduser -S -D -H -u 10001 -G wyrmgate wyrmgate
COPY --from=build --chown=10001:10001 /workspace/apps/server/target/wyrmgate-iam-server-0.1.0-SNAPSHOT.jar /app/wyrmgate-iam-server.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/wyrmgate-iam-server.jar"]
