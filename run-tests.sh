#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" app/src/main/java/com/privategolf/r10range/R10Protocol.java tests/R10ProtocolTest.java
java -cp "$classes" com.privategolf.r10range.R10ProtocolTest
