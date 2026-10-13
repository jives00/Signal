# The NAS companion and web admin (PLAN §5.2). CI builds both first (deploy.yml): the server needs
# shared/, which Gradle can't configure without the Android SDK, so the image only packages
# server/build/install/server and web/dist.
FROM eclipse-temurin:17-jre

# wget for the health check.
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server/build/install/server /app/server
COPY web/dist /app/web

ENV PORT=3013 \
    DATA_DIR=/data \
    WEB_DIR=/app/web \
    TZ=America/Chicago \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -Djava.awt.headless=true"

VOLUME /data
EXPOSE 3013
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s CMD wget -qO- http://127.0.0.1:3013/health > /dev/null || exit 1
CMD ["/app/server/bin/server"]
