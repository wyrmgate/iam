# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY apps/server /workspace/apps/server
RUN sh /workspace/apps/server/mvnw -f /workspace/apps/server/pom.xml clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --uid 10001 --create-home wyrmgate
COPY --from=build /workspace/apps/server/target/wyrmgate-iam-server-0.1.0-SNAPSHOT.jar /app/wyrmgate-iam-server.jar

USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/wyrmgate-iam-server.jar"]
