FROM maven:3.9.16-eclipse-temurin-21@sha256:c2a2c58516d160f43b50f12baa427ca86989e0bc942609e04aff61da5d9a7d74 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode -DskipTests package

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.23@sha256:1f165f3a75613b80cc803a2ccc4d865e5c8b845b97f692988d75c4a026609e61
WORKDIR /app
COPY --from=build /app/target/bili-charge-archive-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
