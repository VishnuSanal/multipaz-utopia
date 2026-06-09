# Brewery demo - standalone server image
# Runs the self-contained brewery fat jar directly (no nginx; a single service
# sits at the root of its own host, so path-based multiplexing is unnecessary).

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    sqlite3 libsqlite3-dev curl && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN groupadd -r brewery && useradd -r -g brewery brewery

WORKDIR /app

# Fat (shadow) jar built by :organizations:brewery:backend:shadowJar
COPY organizations/brewery/backend/build/libs/backend-all.jar /app/brewery.jar

COPY deployment/start-brewery.sh /app/start-brewery.sh
RUN chmod +x /app/start-brewery.sh

# Data (sqlite db) and logs; mount these as volumes to persist across runs
RUN mkdir -p /app/data /app/logs && chown -R brewery:brewery /app

USER brewery

EXPOSE 8010

ENTRYPOINT ["/app/start-brewery.sh"]
