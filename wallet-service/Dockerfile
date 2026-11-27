# Build the jar on the host first (mvn package); no Maven runs inside Docker.
# Base image pinned to an exact tag (never latest). Not pulled until the first docker build.

# Stage 1: split the Boot jar into layers, so a code change rebuilds only the small "application" layer.
FROM eclipse-temurin:21.0.9_10-jre-noble AS builder
WORKDIR /build
COPY target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Stage 2: runtime image. Layers are copied from least to most often changing.
FROM eclipse-temurin:21.0.9_10-jre-noble
WORKDIR /app
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./
# Invariant: the process never runs as root. A numeric id works whatever /etc/passwd holds; files stay root-owned
# and read-only to it. The heap is a share of the container memory limit (compose mem_limit), not of the host.
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "org.springframework.boot.loader.launch.JarLauncher"]
