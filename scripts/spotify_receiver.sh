#!/system/bin/sh
set -eu
umask 077

root=/data/local/openstation/receivers/spotify
[ "$(id -u)" = 0 ] || exit 77
[ -d "$root" ] && [ ! -L "$root" ] && [ "$(stat -c %u:%a "$root")" = "0:700" ] || exit 65
[ -x "$root/librespot" ] && [ -x "$root/audiosink" ] || exit 65

# A short kernel lock serializes starts and stops. The receiver closes it at launch.
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
        if active_pid; then echo running; else echo stopped; fi
        ;;
    start)
        if active_pid; then echo running; exit 0; fi
        mkdir -p "$root/cache"
        setsid "$root/librespot" --name 'OpenStation Max' --device-type speaker \
            --zeroconf-port 8085 \
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
        ;;
    stop)
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
