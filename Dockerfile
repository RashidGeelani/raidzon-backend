FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml maven-settings.xml ./
# Download everything the package step needs (from Google's Maven Central mirror, with retries),
# by packaging an empty project, so the package step below runs offline and this layer stays cached
# until pom.xml changes.
RUN mkdir -p src/main/java && mvn -B -ntp -s maven-settings.xml -Daether.connector.http.retryHandler.count=5 \
    -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true dependency:go-offline package && rm -rf target src
COPY src ./src
# Run the shared-fixture test suite from the repository before building this image.
# A standalone deployment context intentionally excludes repository test fixtures.
RUN mvn -B -ntp -o -s maven-settings.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S raidzon && adduser -S raidzon -G raidzon
WORKDIR /app
COPY --from=build --chown=raidzon:raidzon /app/target/raidzonbackend-0.1.0-SNAPSHOT.jar app.jar
USER raidzon
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
