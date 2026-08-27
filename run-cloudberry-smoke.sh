#!/usr/bin/env bash
#
# Smoke-test the SQLancer `cloudberry` provider against a running Apache Cloudberry
# cluster (connects to the coordinator only).
#
# Usage:
#   ./run-cloudberry-smoke.sh [ORACLE] [SECONDS] [THREADS] [PORT] [HOST] [USER]
# Defaults:
#   ORACLE=QUERY_PARTITIONING  SECONDS=60  THREADS=2  PORT=7000  HOST=localhost  USER=gpadmin
# Valid ORACLE values: NOREC | WHERE | HAVING | QUERY_PARTITIONING
#
# Requires Java 11+ (SQLancer does not build/run on Java 8). Set JAVA_HOME to a
# JDK 11+ if the default `java` is older.

set -euo pipefail

ORACLE="${1:-QUERY_PARTITIONING}"
SECONDS_TO_RUN="${2:-60}"
THREADS="${3:-2}"
PORT="${4:-7000}"
HOST="${5:-localhost}"
DBUSER="${6:-gpadmin}"

cd "$(dirname "$0")"

# --- locate a Java 11+ runtime ---------------------------------------------
JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
fi
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [[ "$JAVA_MAJOR" -lt 11 ]]; then
    echo "ERROR: Java 11+ required (found $JAVA_MAJOR). Set JAVA_HOME to a JDK 11+." >&2
    exit 1
fi

# --- build if needed --------------------------------------------------------
JAR="$(ls -t target/sqlancer-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR" ]]; then
    echo "Building SQLancer jar ..."
    mvn -q package -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true \
        -Dpmd.skip=true -Dformatter.skip=true
    JAR="$(ls -t target/sqlancer-*.jar | head -1)"
fi

echo "Java:   $("$JAVA_BIN" -version 2>&1 | head -1)"
echo "Jar:    $JAR"
echo "Target: $HOST:$PORT (user=$DBUSER)  oracle=$ORACLE  ${SECONDS_TO_RUN}s x ${THREADS} threads"
echo "NOTE: a database named 'test' must exist on the coordinator."
echo

exec "$JAVA_BIN" -jar "$JAR" \
    --num-threads "$THREADS" --timeout-seconds "$SECONDS_TO_RUN" --username "$DBUSER" \
    cloudberry --connection-url "postgresql://$HOST:$PORT/test" \
    --cloudberry-oracle "$ORACLE"
