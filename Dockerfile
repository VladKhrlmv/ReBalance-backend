# ReBalance backend runtime image.
#
# The jar is built OUTSIDE Docker (./mvnw -B package) and only copied in here.
# There is deliberately no RUN instruction, so `docker buildx build --platform linux/arm64`
# works on x86_64 CI runners without QEMU emulation.
#
# Base: Ubuntu-based Temurin JRE (not Alpine) - receipt image processing needs the
# AWT/fontconfig native libraries that glibc-based images ship. It also provides bash,
# which the compose healthcheck uses (/dev/tcp, no curl needed).
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.source="https://github.com/VladKhrlmv/ReBalance-backend" \
      org.opencontainers.image.description="ReBalance backend (Spring Boot)"

# HOME: the runtime uid has no /etc/passwd entry; give the JVM/fontconfig a writable home.
# JAVA_OPTS: sane default for plain `docker run`; docker-compose overrides it from .env.
ENV HOME=/tmp \
    APP_STORAGE_PATH=/data/images \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# /app and the jar stay root-owned (read-only for the app user).
WORKDIR /app
# .dockerignore lets exactly one jar through: target/ReBalance-0.0.1-SNAPSHOT.jar
# (sources/javadoc/plain jars and *.jar.original are excluded).
COPY target/*.jar /app/app.jar

# Numeric non-root user (no RUN useradd needed).
USER 10001:10001
# BuildKit creates a WORKDIR that does not exist yet with the ownership of the current USER,
# so this gives us a /data/images owned by 10001:10001 without a RUN step. It matters when
# /data/images is a fresh named volume (compose.local.yml); in production it is a bind mount
# chowned by setup-vm.sh.
WORKDIR /data/images
WORKDIR /app

EXPOSE 8080

# Extra arguments are passed through to the application, e.g.
#   docker compose run --rm backend --spring.main.web-application-type=none --reset-password=user@example.com
# `exec` makes java PID 1 so it receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
