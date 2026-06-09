#!/bin/sh

# Brewery demo server startup script.
#
# Mirrors how the Multipaz server bundle launches the brewery service, but for a
# single standalone container: there is no nginx in front, so base_url points at
# the service root rather than a /brewery/ sub-path.

set -e

# Base URL advertised in protocol messages. For a real deployment set this to the
# externally reachable URL (e.g. https://brewery.example.com).
BASE_URL="${BASE_URL:-http://localhost:8010}"

# Derive the bare host for ca_trust_servers.
no_protocol="${BASE_URL#*://}"   # strip protocol
host_port="${no_protocol%%/*}"   # strip path
host="${host_port%:*}"           # strip port

PORT="${PORT:-8010}"
EXTRA_PARAMS="${EXTRA_PARAMS:-}"

echo "=========================================="
echo "Brewery demo server"
echo "Base URL: ${BASE_URL}"
echo "Port:     ${PORT}"
echo "=========================================="

exec java -jar /app/brewery.jar \
  -param server_port="${PORT}" \
  -param base_url="${BASE_URL}" \
  -param ca_trust_servers="[\"${host}\"]" \
  -param server_trace_file="/app/logs/brewery-trace.log" \
  -param database_connection="jdbc:sqlite:/app/data/brewery.db" \
  ${EXTRA_PARAMS} \
  "$@"
