# Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

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

# Copy the built jar from the build stage
COPY --from=build /app/target/linkup-*.jar app.jar

ENV PORT=8080
EXPOSE 8080

# Tune memory for 512MB RAM constraint on free tiers
ENV JAVA_TOOL_OPTIONS="-Xmx350m -Xss256k -XX:+UseSerialGC"

ENTRYPOINT ["java", "-jar", "app.jar"]
