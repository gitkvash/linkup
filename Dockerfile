# Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Ensure curl and unzip are present for the maven wrapper
RUN apt-get update && apt-get install -y curl unzip && rm -rf /var/lib/apt/lists/*

# Copy maven wrapper and pom.xml first to cache dependency downloads
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B || true

# Copy source and build jar
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app

# Nothing in the app needs root; a compromise of the JVM shouldn't hand over the container.
RUN groupadd --system linkup && useradd --system --gid linkup --no-create-home linkup

# Copy the built jar from the build stage
COPY --from=build --chown=linkup:linkup /app/target/linkup-*.jar app.jar

ENV PORT=8080
EXPOSE 8080

# Without this the image falls back to spring.profiles.default (dev), which loads
# application-dev.yaml: a JWT key published in this repository and the dev database
# passwords - and every "refuse to start outside dev" check treats that as dev and passes.
# An environment variable set on the host still overrides it.
ENV SPRING_PROFILES_ACTIVE=prod

# Tune memory for 512MB RAM constraint on free tiers. Exit on OOM so the platform
# restarts the container instead of it limping on with a half-dead heap.
ENV JAVA_TOOL_OPTIONS="-Xmx350m -Xss256k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

USER linkup

ENTRYPOINT ["java", "-jar", "app.jar"]
