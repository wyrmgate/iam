# syntax=docker/dockerfile:1.7

FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY apps/server/pom.xml /workspace/apps/server/pom.xml
RUN mvn -f /workspace/apps/server/pom.xml dependency:go-offline

COPY apps/server /workspace/apps/server
RUN mvn -f /workspace/apps/server/pom.xml clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --uid 10001 --create-home wyrmgate
COPY --from=build /workspace/apps/server/target/wyrmgate-iam-server-0.1.0-SNAPSHOT.jar /app/wyrmgate-iam-server.jar

USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/wyrmgate-iam-server.jar"]
