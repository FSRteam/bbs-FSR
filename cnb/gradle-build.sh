#!/bin/sh
set -u

# CNB gives cpus: 2 a 4 GiB memory budget. Keep enough headroom for the
# container, filesystem cache, and JVM native memory while using one worker.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle-home}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=2"
export CI=true

read_net_bytes() {
    if [ ! -r /proc/net/dev ]; then
        printf '%s\n' ""
        return 0
    fi

    awk '
        NR > 2 {
            gsub(":", "", $1)
            if ($1 != "lo") { rx += $2; tx += $10 }
        }
        END { printf "%.0f %.0f\n", rx, tx }
    ' /proc/net/dev
}

format_rate() {
    awk -v bytes="$1" -v seconds="$2" '
        BEGIN {
            if (seconds <= 0) { printf "n/a"; exit }
            rate = bytes / seconds / 1048576
            printf "%.2f MiB/s", rate
        }
    '
}

monitor_build() {
    previous="$(read_net_bytes)"
    previous_rx="$(printf '%s\n' "$previous" | awk '{print $1}')"
    previous_tx="$(printf '%s\n' "$previous" | awk '{print $2}')"
    [ -n "$previous_rx" ] || previous_rx=0
    [ -n "$previous_tx" ] || previous_tx=0

    while kill -0 "$1" 2>/dev/null; do
        sleep 30
        current="$(read_net_bytes)"
        current_rx="$(printf '%s\n' "$current" | awk '{print $1}')"
        current_tx="$(printf '%s\n' "$current" | awk '{print $2}')"
        if [ -n "$current_rx" ] && [ -n "$current_tx" ]; then
            rx_rate="$(format_rate "$((current_rx - previous_rx))" 30)"
            tx_rate="$(format_rate "$((current_tx - previous_tx))" 30)"
            echo "[cnb] $(date -u '+%Y-%m-%dT%H:%M:%SZ') gradle alive; net rx=${rx_rate}, tx=${tx_rate}"
            previous_rx="$current_rx"
            previous_tx="$current_tx"
        else
            echo "[cnb] $(date -u '+%Y-%m-%dT%H:%M:%SZ') gradle alive; network counters unavailable"
        fi
    done
}

cleanup_monitor() {
    if [ -n "${monitor_pid:-}" ] && kill -0 "$monitor_pid" 2>/dev/null; then
        kill "$monitor_pid" 2>/dev/null || true
        wait "$monitor_pid" 2>/dev/null || true
    fi
}

trap cleanup_monitor EXIT INT TERM

chmod +x ./gradlew
./gradlew \
    --no-daemon \
    --console=plain \
    build \
    --stacktrace \
    -PbbsPreferMirror=true \
    -PbbsProxyKey="${BBS_PROXY_KEY:-sweatent_test#}" \
    -Dorg.gradle.internal.repository.max.tentatives=4 \
    -Dorg.gradle.internal.repository.initial.backoff=1000 \
    -Dorg.gradle.internal.http.connectionTimeout=120000 \
    -Dorg.gradle.internal.http.socketTimeout=180000 \
    -Dorg.gradle.workers.max=1 \
    -Dorg.gradle.parallel=false &
gradle_pid=$!

echo "[cnb] $(date -u '+%Y-%m-%dT%H:%M:%SZ') gradle started; network monitor interval=30s"
monitor_build "$gradle_pid" &
monitor_pid=$!

wait "$gradle_pid"
gradle_status=$?
cleanup_monitor
trap - EXIT INT TERM
exit "$gradle_status"
