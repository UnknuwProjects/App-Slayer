#!/system/bin/sh

MODDIR=${0%/*}
CONFIG_DIR="$MODDIR/list"
MODE_FILE="$MODDIR/config_mode/mode.txt"
LOG_DIR="/data/adb/modules/appslayer/log"
LOG_FILE="$LOG_DIR/appslayer_log.txt"
LAST_LOG="$LOG_DIR/.appslayer_lastlog"

# Menggunakan RAM disk (/dev) agar hemat I/O storage
TMP_DIR="/dev/appslayer_tmp"
LAST_FG="$TMP_DIR/lastfg"
EXCLUDE_LIST="$TMP_DIR/exclude.txt"

mkdir -p "$LOG_DIR" "$TMP_DIR"

# Inisialisasi log awal
[ ! -f "$LOG_FILE" ] && echo "[AppSlayer] Service started" > "$LOG_FILE"
[ ! -f "$LAST_LOG" ] && date +%s > "$LAST_LOG"

# Clean DOS newline sekali di awal
for f in gamelist.txt Extreme.txt whitelist.txt listkill.txt; do
    [ -f "$CONFIG_DIR/$f" ] && dos2unix "$CONFIG_DIR/$f" 2>/dev/null
done

# Ambil foreground app (ringan & cepat)
get_current_app() {
    pkg=$(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | grep -oE '[a-zA-Z0-9._]+/[a-zA-Z0-9._]+' | cut -d/ -f1)
    if [ -z "$pkg" ]; then
        pkg=$(dumpsys activity activities 2>/dev/null | grep "ResumedActivity" | head -n 1 | grep -oE "[a-zA-Z0-9._]+/[a-zA-Z0-9._]+" | cut -d/ -f1)
    fi
    echo "$pkg"
}

set_governor_all_cores() {
    gov="$1"
    for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
        [ -e "$cpu/online" ] && echo 1 > "$cpu/online" 2>/dev/null
        gov_path="$cpu/cpufreq/scaling_governor"
        avail_path="$cpu/cpufreq/scaling_available_governors"
        if [ -w "$gov_path" ] && grep -qw "$gov" "$avail_path"; then
            echo "$gov" > "$gov_path"
        fi
    done
    echo "[CPU] Governor diset ke '$gov'" >> "$LOG_FILE"
}

set_userspace_75_percent_freq() {
    freq_file="/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies"
    if [ -f "$freq_file" ]; then
        freqs=($(cat "$freq_file"))
        total=${#freqs[@]}
        if [ "$total" -gt 0 ]; then
            idx=$(( (total * 75) / 100 ))
            [ "$idx" -ge "$total" ] && idx=$((total - 1))
            target_freq="${freqs[$idx]}"
            
            for core in /sys/devices/system/cpu/cpu[0-9]*; do
                echo 1 > "$core/online" 2>/dev/null
                echo "$target_freq" > "$core/cpufreq/scaling_setspeed" 2>/dev/null
            done
            echo "[CPU] Userspace target 75%: $target_freq Hz" >> "$LOG_FILE"
            return
        fi
    fi
    echo "[WARN] Gagal set frekuensi userspace" >> "$LOG_FILE"
}

# App Sistem Vital yang TIDAK BOLEH di-kill di Mode Extreme
SYS_IGNORE_REGEX="sound|dolby|miui|aosp|systemui|inputmethod|keyboard|telephony|ims|settings|nfc|bluetooth|media|icon|font|clockfont|overlay|documentsui|externalstorage|shell|packageinstaller|telecom|wifi|calllog|downloads|frameworks|contact|calendar|location|camera|permissioncontroller|qti|qualcomm|maps|vendor|lineageos|omnirom|libremobileos"

# Loop Utama
while true; do

    current_foreground=$(get_current_app)
    last_foreground=$(cat "$LAST_FG" 2>/dev/null)
        
    if [ -z "$current_foreground" ]; then
        current_foreground="$last_foreground"
        if [ -z "$current_foreground" ]; then
            sleep 15
            continue
        fi
    fi

    # Skip jika aplikasi tidak berubah (hemat baterai)
    if [ "$current_foreground" = "$last_foreground" ]; then
        sleep 10
        continue
    fi
    
    echo "$current_foreground" > "$LAST_FG"

    # Reset log tiap 10 menit
    current_time=$(date +%s)
    last_logged=$(cat "$LAST_LOG" 2>/dev/null || echo 0)
    if [ $((current_time - last_logged)) -ge 600 ]; then
        echo "[AppSlayer] Log auto-cleared at $(date '+%H:%M:%S')" > "$LOG_FILE"
        date +%s > "$LAST_LOG"
    fi

    MODE=1
    [ -f "$MODE_FILE" ] && MODE=$(tr -d '\r\n' < "$MODE_FILE")
    
    case "$MODE" in
        1) MODE_NAME="NORMAL" ;;
        2) MODE_NAME="AGGRESSIVE" ;;
        3) MODE_NAME="EXTREME" ;;
        *) MODE_NAME="NORMAL" ;;
    esac

    FG_PKG="$current_foreground"
    
    # Eksekusi HANYA jika Foreground App ada di gamelist.txt
    if grep -Fxq "$FG_PKG" "$CONFIG_DIR/gamelist.txt" 2>/dev/null; then
        echo "==========================================" >> "$LOG_FILE"
        echo "[+] Game Terdeteksi: $FG_PKG | Mode: $MODE_NAME" >> "$LOG_FILE"
        
        # Set CPU Governor
        case "$MODE" in
            1) set_governor_all_cores "schedutil" ;;
            2)
                if grep -qw "userspace" /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors; then
                    set_governor_all_cores "userspace"
                    set_userspace_75_percent_freq
                else
                    set_governor_all_cores "performance"
                fi
                ;;
            3) set_governor_all_cores "performance" ;;
        esac

        am broadcast -a com.appslayer.official.ACTION_TOAST_MODE \
            --es msg "Mode : $MODE_NAME AKTIF" \
            --es status "Game: $FG_PKG 🎮" >/dev/null 2>&1

        killed=0
        
        # List pengecualian: Whitelist/Extreme list + Game yang sedang dibuka
        if [ "$MODE" -eq 3 ]; then
            cat "$CONFIG_DIR/Extreme.txt" 2>/dev/null > "$EXCLUDE_LIST"
        else
            cat "$CONFIG_DIR/whitelist.txt" 2>/dev/null > "$EXCLUDE_LIST"
        fi
        echo "$FG_PKG" >> "$EXCLUDE_LIST"
        sed -i '/^$/d' "$EXCLUDE_LIST"

        # 1. Kill Aplikasi User (Pihak Ke-3) & Catat ke Log
        for pkg in $(pm list packages -3 | cut -d: -f2 | grep -vFf "$EXCLUDE_LIST"); do
            am force-stop "$pkg"
            echo "[KILL:$MODE_NAME] $pkg" >> "$LOG_FILE"
            killed=$((killed + 1))
        done

        # 2. Mode Extreme: Kill App Sistem non-vital & Catat ke Log
        if [ "$MODE" -eq 3 ]; then
            for pkg in $(pm list packages -s | cut -d: -f2 | grep -vFf "$EXCLUDE_LIST" | grep -vE -i "$SYS_IGNORE_REGEX"); do
                am force-stop "$pkg"
                echo "[KILL:SYSTEM] $pkg" >> "$LOG_FILE"
                killed=$((killed + 1))
            done
        fi

        # 3. Forced Kill List & Catat ke Log
        if [ -f "$CONFIG_DIR/listkill.txt" ]; then
            for forced_pkg in $(cat "$CONFIG_DIR/listkill.txt"); do
                if [ "$forced_pkg" != "$FG_PKG" ]; then
                    am force-stop "$forced_pkg"
                    echo "[KILL:FORCED] $forced_pkg" >> "$LOG_FILE"
                    killed=$((killed + 1))
                fi
            done
        fi
        
        # Summary Broadcast & Log Akhir
        am broadcast -a com.appslayer.official.ACTION_TOAST_MODE \
            --es msg "Mode : $MODE_NAME AKTIF" \
            --es status "Selesai: $killed Apps Dibersihkan ☠️" >/dev/null 2>&1
            
        echo "[CLEANUP] Total $killed aplikasi dibersihkan untuk $MODE_NAME" >> "$LOG_FILE"
        echo "==========================================" >> "$LOG_FILE"
        
        sleep 5
        am broadcast -a com.appslayer.official.ACTION_TOAST_MODE --es msg "Modul AKTIF | Enjoy Your Gaming 🎮" --es status "" >/dev/null 2>&1

        # Istirahat panjang selama game dimainkan
        sleep 60 
    fi
done
