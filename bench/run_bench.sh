#!/bin/bash
# Performance benchmark: Stock Pedestal vs ti-yong-http
# Inspired by TechEmpower (plaintext/json) and BrunoBonacci (tail latency)
#
# Uses wrk for load testing.

set -e

DURATION="${DURATION:-10s}"
THREADS="${THREADS:-2}"
CONNECTIONS="${CONNECTIONS:-50}"
WARMUP="${WARMUP:-3s}"
PORT=8080
BASE_URL="http://127.0.0.1:${PORT}"
RESULTS_DIR="$(dirname $0)/results"

mkdir -p "$RESULTS_DIR"

echo "=========================================="
echo " Benchmark Configuration"
echo "=========================================="
echo " Duration:    $DURATION"
echo " Threads:     $THREADS"
echo " Connections: $CONNECTIONS"
echo " Warmup:      $WARMUP"
echo "=========================================="
echo ""

run_wrk() {
    local label="$1"
    local url="$2"
    local output_file="$3"

    echo "  Warming up: $label ($WARMUP)..."
    wrk -t"$THREADS" -c"$CONNECTIONS" -d"$WARMUP" "$url" > /dev/null 2>&1

    echo "  Benchmarking: $label ($DURATION)..."
    wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency "$url" 2>&1 | tee "$output_file"
    echo ""
}

wait_for_server() {
    local max_wait=30
    local waited=0
    while ! curl -s "$BASE_URL/plaintext" > /dev/null 2>&1; do
        sleep 1
        waited=$((waited + 1))
        if [ $waited -ge $max_wait ]; then
            echo "ERROR: Server failed to start within ${max_wait}s"
            exit 1
        fi
    done
    echo "  Server is ready (took ${waited}s)"
}

kill_server() {
    # Kill any java process on port 8080
    local pid=$(lsof -ti:${PORT} 2>/dev/null || true)
    if [ -n "$pid" ]; then
        kill $pid 2>/dev/null || true
        sleep 2
        kill -9 $pid 2>/dev/null || true
    fi
}

benchmark_server() {
    local name="$1"
    local prefix="$RESULTS_DIR/${name}"

    echo "=========================================="
    echo " Benchmarking: $name"
    echo "=========================================="
    echo ""

    # Verify endpoints work
    echo "  Verifying endpoints..."
    curl -s "$BASE_URL/plaintext" && echo ""
    curl -s "$BASE_URL/json" && echo ""
    curl -s "$BASE_URL/items" && echo ""
    curl -s "$BASE_URL/items/42" && echo ""
    echo ""

    # Test 1: Plaintext (TechEmpower style)
    run_wrk "Plaintext (GET /plaintext)" "$BASE_URL/plaintext" "${prefix}_plaintext.txt"

    # Test 2: JSON serialization (TechEmpower style)
    run_wrk "JSON (GET /json)" "$BASE_URL/json" "${prefix}_json.txt"

    # Test 3: Routing with path params
    run_wrk "Routing (GET /items/42)" "$BASE_URL/items/42" "${prefix}_routing.txt"

    # Test 4: List endpoint (larger JSON response)
    run_wrk "List (GET /items)" "$BASE_URL/items" "${prefix}_list.txt"
}

# --- Run Pedestal Benchmark ---
echo ""
echo "########################################"
echo " Starting Stock Pedestal Server"
echo "########################################"
echo ""

kill_server

cd "$(dirname $0)/.."
clojure -M:bench -e "(require 'pedestal.bench-pedestal) ((resolve 'pedestal.bench-pedestal/-main))" &
PEDESTAL_PID=$!

wait_for_server
benchmark_server "pedestal"

kill_server
wait

# --- Run ti-yong Benchmark ---
echo ""
echo "########################################"
echo " Starting ti-yong-http Server"
echo "########################################"
echo ""

kill_server

clojure -M:bench -e "(require 'tiyong.bench-tiyong) ((resolve 'tiyong.bench-tiyong/-main))" &
TIYONG_PID=$!

wait_for_server
benchmark_server "tiyong"

kill_server
wait

# --- Summary ---
echo ""
echo "=========================================="
echo " RESULTS SUMMARY"
echo "=========================================="
echo ""

parse_wrk() {
    local file="$1"
    local rps=$(grep "Requests/sec:" "$file" | awk '{print $2}')
    local latency_avg=$(grep "Latency" "$file" | head -1 | awk '{print $2}')
    local latency_99=$(grep "99%" "$file" | awk '{print $2}')
    echo "${rps:-N/A} req/s | avg ${latency_avg:-N/A} | p99 ${latency_99:-N/A}"
}

printf "%-12s %-20s %s\n" "Test" "Pedestal" "ti-yong-http"
printf "%-12s %-20s %s\n" "----" "--------" "------------"

for test in plaintext json routing list; do
    ped_file="$RESULTS_DIR/pedestal_${test}.txt"
    ty_file="$RESULTS_DIR/tiyong_${test}.txt"
    printf "%-12s %-40s %s\n" "$test" "$(parse_wrk $ped_file)" "$(parse_wrk $ty_file)"
done

echo ""
echo "Full results in: $RESULTS_DIR/"
