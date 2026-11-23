#!/system/bin/sh
set -eu
umask 077

root=/data/local/openstation/receivers/spotify
pkg="com.spotify.tv.android"
act="com.spotify.tv.android/.SpotifyTVActivity"

[ "$(id -u)" = 0 ] || exit 77
[ -d "$root" ] && [ ! -L "$root" ] && [ "$(stat -c %u:%a "$root")" = "0:700" ] || exit 65

is_pkg_running() {
    pidof "$pkg" >/dev/null 2>&1
}

active_pid() {
    [ -f "$root/receiver.pid" ] || return 1
    read -r pid < "$root/receiver.pid"
    case "$pid" in ''|*[!0-9]*) return 1;; esac
    [ "$(readlink "/proc/$pid/exe" 2>/dev/null)" = "$root/librespot" ]
}

{
flock -x 9
case "${1:-}" in
    status)
        if is_pkg_running || active_pid; then
            echo running
        else
            echo stopped
        fi
        ;;
    start)
        if is_pkg_running || active_pid; then
            echo running
            exit 0
        fi
        # If Spotify TV APK is installed, prefer it for reliable official Spotify Connect
        if pm list packages 2>/dev/null | grep -q "^package:$pkg$"; then
            am start -n "$act" >/dev/null 2>&1 || true
            for i in 1 2 3 4 5; do
                if is_pkg_running; then
                    echo running
                    exit 0
                fi
                sleep 1
            done
            echo 'Не удалось запустить Spotify TV' >&2
            exit 69
        fi
        # Fallback to librespot daemon if present
        if [ -x "$root/librespot" ] && [ -x "$root/audiosink" ]; then
            mkdir -p "$root/cache"
            export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
            export SSL_CERT_DIR=/etc/ssl/certs
            setsid "$root/librespot" --name 'OpenStation Max' --device-type speaker \
                --ap-port 443 --zeroconf-port 8085 \
                --backend subprocess --device "$root/audiosink" --format S16 \
                --cache "$root/cache" --disable-audio-cache --quiet \
                </dev/null >"$root/receiver.log" 2>&1 3>&- 4>&- 5>&- 6>&- 7>&- 8>&- 9>&- &
            pid=$!
            printf '%s\n' "$pid" > "$root/receiver.pid"
            sleep 1
            if active_pid; then
                echo running
            else
                rm -f "$root/receiver.pid"
                echo 'Spotify Connect не запустился. Журнал: /data/local/openstation/receivers/spotify/receiver.log' >&2
                exit 69
            fi
        else
            echo 'Spotify Connect не установлен' >&2
            exit 65
        fi
        ;;
    stop)
        if is_pkg_running; then
            am force-stop "$pkg" >/dev/null 2>&1 || true
        fi
        if active_pid; then
            kill -TERM "$pid"
            tries=0
            while active_pid && [ "$tries" -lt 20 ]; do
                sleep 0.1
                tries=$((tries + 1))
            done
            if active_pid; then
                echo 'Spotify Connect ещё останавливается' >&2
                exit 75
            fi
        fi
        rm -f "$root/receiver.pid"
        echo stopped
        ;;
    *) exit 64;;
esac
} 9>"$root/control.lock"
