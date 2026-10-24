# Build: docker build -t fairseat-api .
# Run:   docker run -p 8080:8080 -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/fairseat fairseat-api

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Dependencies first, so code changes reuse the cached layer.
COPY pom.xml .
COPY config ./config
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI before any deploy; the image build only packages.
RUN mvn -B -q package -DskipTests -Dcheckstyle.skip

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/target/app.jar app.jar
USER app

# Sized for a 512 MB free container: 350 MB heap leaves room for metaspace, thread stacks and the JVM itself.
# SerialGC uses the least memory; TieredStopAtLevel=1 shortens cold starts after the host puts the service to sleep.
ENV JAVA_TOOL_OPTIONS="-Xmx350m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
