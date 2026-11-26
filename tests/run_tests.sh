#!/usr/bin/env bash
# Pre-build gate: JVM unit tests for the pure logic, then the
# source parity checklist. Both must pass before pushing a build.
set -e
cd "$(dirname "$0")/.."
OUT=/tmp/appslayer-test-classes
rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" src/main/java/com/appslayer/official/PureCalc.java tests/PureCalcTest.java tests/FpsStatsTest.java tests/ThermalParseTest.java tests/StackOffsetsTest.java tests/ChartAxisTest.java tests/ChargeSessionTest.java
java -cp "$OUT" PureCalcTest
java -cp "$OUT" FpsStatsTest
java -cp "$OUT" ThermalParseTest
java -cp "$OUT" StackOffsetsTest
java -cp "$OUT" ChartAxisTest
java -cp "$OUT" ChargeSessionTest
bash tests/parity_check.sh
