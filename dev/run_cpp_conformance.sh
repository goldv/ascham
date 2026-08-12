#!/usr/bin/env bash
# Build and run the C++ conformance runner against this repo's golden corpus — the C++ half of the
# cross-language matrix, run at the source of truth. Wired into `gradlew check`.
set -euo pipefail
cd "$(dirname "$0")/.."

cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build cpp/build --parallel >/dev/null
./cpp/build/ascham_conformance_test conformance
