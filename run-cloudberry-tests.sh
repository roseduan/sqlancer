#!/usr/bin/env bash
#
# Run the six Cloudberry JUnit test classes (test/sqlancer/dbms/TestCloudberry*.java) with
# true per-oracle JVM isolation, without relying on any pom.xml fork configuration.
#
# Why this exists: Main.executeMain() never calls shutdownNow() on its ExecutorService when
# a run's --timeout-seconds elapses without every worker thread finishing -- and since JUnit
# calls executeMain() directly, it bypasses the System.exit() that Main.main() would
# otherwise use to kill them. Any worker thread still mid-query at the timeout boundary is
# simply abandoned, not killed, and keeps running in the background (DB connection and all)
# for as long as the JVM stays alive. A single `mvn test` invocation reuses ONE JVM across
# every test class by default (Surefire's reuseForks=true default), so a straggler from one
# oracle's class can leak into every class that runs afterward in that same invocation.
# Observed in practice: an abandoned mid-DROP-DATABASE thread held a catalog lock other
# sessions needed, and a run that should take ~30 minutes took over 2.5 hours instead.
#
# This script sidesteps that by invoking each of the six TestCloudberry* classes as its OWN
# `mvn test` process. Each gets a genuinely fresh JVM; when it exits, the OS reclaims any
# straggler threads and their sockets, so nothing leaks into the next oracle's class.
#
# Each class's mvn output streams directly to the screen (not to a log file).
#
# Usage:
#   ./run-cloudberry-tests.sh                    # run all 6 oracle classes
#   ./run-cloudberry-tests.sh NoREC PQS          # run only the named oracle(s)
#
# Requires: Java 11+ (set JAVA_HOME if the default `java` is older), CLOUDBERRY_AVAILABLE=true
# (TestConfig.CLOUDBERRY_ENV) to un-skip the tests, and a Cloudberry cluster reachable at
# postgresql://localhost:7000/test as configured in each TestCloudberry* class.

set -uo pipefail

cd "$(dirname "$0")"

ALL_ORACLES=(NoREC PQS TLP Optimizer CERT Fuzzer)
ORACLES=("${@:-${ALL_ORACLES[@]}}")

export CLOUDBERRY_AVAILABLE="${CLOUDBERRY_AVAILABLE:-true}"

JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
fi
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [[ "$JAVA_MAJOR" -lt 11 ]]; then
    echo "ERROR: Java 11+ required (found $JAVA_MAJOR). Set JAVA_HOME to a JDK 11+." >&2
    exit 1
fi

echo "Java: $("$JAVA_BIN" -version 2>&1 | head -1)"
echo "Oracle classes to run (one mvn invocation each): ${ORACLES[*]}"
echo

declare -a RESULTS=()
OVERALL_STATUS=0

for oracle in "${ORACLES[@]}"; do
    class="TestCloudberry${oracle}"
    echo "=== $class ==="
    start=$(date +%s)
    if mvn test -Dtest="$class" \
        -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Dformatter.skip=true; then
        status="PASS"
    else
        status="FAIL"
        OVERALL_STATUS=1
    fi
    elapsed=$(( $(date +%s) - start ))
    echo "  -> $status (${elapsed}s)"
    RESULTS+=("$oracle: $status (${elapsed}s)")
done

echo
echo "=== Summary ==="
printf '%s\n' "${RESULTS[@]}"
echo
if [[ $OVERALL_STATUS -ne 0 ]]; then
    echo "One or more oracles found a finding or failed to run cleanly."
    echo "For a failing oracle, check the mvn output above and:"
    echo "  logs/cloudberry/*.log (excluding *-cur.log) for the reproduction + AssertionError."
fi
exit $OVERALL_STATUS
