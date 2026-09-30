# syntax=docker/dockerfile:1.7
# One image recipe for the four Java services. Build from the repository root:
#   docker build -f services/Dockerfile.java --build-arg SERVICE=normalizer -t fleetpulse/normalizer .
ARG SERVICE

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
# The reactor needs every module's pom; only the service and the shared library are compiled.
COPY services/common services/common
COPY services/simulator/pom.xml services/simulator/pom.xml
COPY services/ingest-gateway/pom.xml services/ingest-gateway/pom.xml
COPY services/normalizer/pom.xml services/normalizer/pom.xml
COPY services/stream-processor/pom.xml services/stream-processor/pom.xml
COPY services/${SERVICE}/src services/${SERVICE}/src
# Tests run in CI; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/${SERVICE} -am package -DskipTests \
 && cp services/${SERVICE}/target/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --home /app app && mkdir -p /app /state && chown app /state
COPY --from=build /app.jar /app/app.jar
USER 10001
WORKDIR /app
ENV STATE_DIR=/state
# Size the heap from the container's memory limit rather than the host's. Services with Kafka
# Streams state lower the share (JAVA_TOOL_OPTIONS in compose): RocksDB lives outside the heap.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
