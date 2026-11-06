#!/system/bin/sh
# A sampled watchdog, not a cgroup hard limit. Invoke through `sh` from ADB.
# Arguments: cwd output-prefix budget-bytes reserve-bytes sample-ms timeout -- argv...
set -u
root=$1; prefix=$2; budget=$3; reserve=$4; sample_ms=$5; timeout=$6
shift 6
[ "$1" = -- ] || exit 64
shift
[ "$#" -gt 0 ] || exit 64
cd "$root" || exit 72
limit=$((budget - reserve))
[ "$limit" -gt 0 ] || exit 64
interval="0.$sample_ms"
owned_file="$prefix.owned"
: > "$owned_file"
printf 'uptime_s\trss_kib\tpss_kib\tsum_vmhwm_kib\tsupervisor_vmhwm_kib\tprocesses\tpss_complete\n' > "$prefix.samples.tsv"
samples=0; peak_rss=0; peak_pss=0; peak_hwm=0; supervisor_hwm=0
pss_complete_samples=0; rejected=0; timed_out=0; exit_code=125
remaining=0; owned_pids=""; launcher=""

identity() {
    # starttime prevents signalling a recycled PID. Z means already terminated.
    [ -r "/proc/$1/stat" ] || return 1
    IFS= read -r process_stat < "/proc/$1/stat" || return 1
    stat_tail=${process_stat##*) }
    set -- $stat_tail
    process_state=$1
    shift 19
    printf '%s %s\n' "$1" "$process_state"
}

remember_tree() {
    frontier=$launcher
    tree_pids=""
    while [ -n "$frontier" ]; do
        next_frontier=""
        for process_pid in $frontier; do
            case " $tree_pids " in *" $process_pid "*) continue ;; esac
            tree_pids="$tree_pids $process_pid"
            case " $owned_pids " in
                *" $process_pid "*) ;;
                *)
                    token=$(identity "$process_pid") || token=""
                    if [ -n "$token" ]; then
                        set -- $token
                        printf '%s %s\n' "$process_pid" "$1" >> "$owned_file"
                        owned_pids="$owned_pids $process_pid"
                    fi
                    ;;
            esac
            children=""
            children_path="/proc/$process_pid/task/$process_pid/children"
            if [ -r "$children_path" ]; then
                IFS= read -r children < "$children_path" || :
            else
                # Some Android kernels omit CONFIG_CHECKPOINT_RESTORE and
                # therefore /proc/.../children. PPid provides a slower fallback.
                for scan_status in /proc/[0-9]*/status; do
                    scan_ppid=""
                    while read -r scan_key scan_value scan_unit; do
                        if [ "$scan_key" = PPid: ]; then scan_ppid=$scan_value; break; fi
                    done < "$scan_status" 2>/dev/null
                    if [ "$scan_ppid" = "$process_pid" ]; then
                        scan_pid=${scan_status#/proc/}; scan_pid=${scan_pid%/status}
                        children="$children $scan_pid"
                    fi
                done
            fi
            next_frontier="$next_frontier $children"
        done
        set -- $next_frontier
        frontier="$*"
    done
}

read_memory() {
    mem_rss=0; mem_hwm=0; mem_pss=0; has_pss=0
    [ -r "/proc/$1/status" ] || return 1
    while read -r key value unit; do
        case "$key" in
            VmRSS:) mem_rss=$value ;;
            VmHWM:) mem_hwm=$value ;;
        esac
    done < "/proc/$1/status"
    if [ -r "/proc/$1/smaps_rollup" ]; then
        while read -r key value unit; do
            if [ "$key" = Pss: ]; then mem_pss=$value; has_pss=1; break; fi
        done < "/proc/$1/smaps_rollup"
    fi
    return 0
}

sample_memory() {
    remember_tree
    tree_rss=0; tree_pss=0; tree_hwm=0; process_count=0; complete=1
    # Include the supervisor, but not adbd or unrelated Android processes.
    for memory_pid in $$ $tree_pids; do
        if read_memory "$memory_pid"; then
            tree_rss=$((tree_rss + mem_rss))
            tree_pss=$((tree_pss + mem_pss))
            tree_hwm=$((tree_hwm + mem_hwm))
            process_count=$((process_count + 1))
            [ "$has_pss" -eq 1 ] || complete=0
            if [ "$memory_pid" = $$ ] && [ "$mem_hwm" -gt "$supervisor_hwm" ]; then supervisor_hwm=$mem_hwm; fi
        fi
    done
    samples=$((samples + 1))
    [ "$tree_rss" -le "$peak_rss" ] || peak_rss=$tree_rss
    [ "$tree_hwm" -le "$peak_hwm" ] || peak_hwm=$tree_hwm
    if [ "$complete" -eq 1 ]; then
        pss_complete_samples=$((pss_complete_samples + 1))
        [ "$tree_pss" -le "$peak_pss" ] || peak_pss=$tree_pss
    fi
    IFS=' ' read -r uptime_value ignored < /proc/uptime
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$uptime_value" "$tree_rss" "$tree_pss" "$tree_hwm" "$supervisor_hwm" "$process_count" "$complete" >> "$prefix.samples.tsv"
}

signal_owned() {
    while read -r signal_pid expected_start; do
        # Keep toybox time alive to reap the model and report its actual HWM.
        [ "$signal_pid" != "$launcher" ] || continue
        token=$(identity "$signal_pid") || continue
        set -- $token
        [ "$1" = "$expected_start" ] && [ "$2" != Z ] || continue
        kill -"$signal_name" "$signal_pid" 2>/dev/null || :
    done < "$owned_file"
}

stop_owned() {
    [ -n "$launcher" ] && remember_tree
    signal_name=TERM; signal_owned
    sleep 0.2
    signal_name=KILL; signal_owned
}

count_remaining() {
    remaining=0
    while read -r remaining_pid expected_start; do
        token=$(identity "$remaining_pid") || continue
        set -- $token
        if [ "$1" = "$expected_start" ] && [ "$2" != Z ]; then remaining=$((remaining + 1)); fi
    done < "$owned_file"
}

write_summary() {
    {
        printf 'model_exit_code=%s\nmemory_rejected=%s\ntimed_out=%s\nsamples=%s\n' "$exit_code" "$rejected" "$timed_out" "$samples"
        printf 'sampled_peak_tree_rss_kib=%s\nsampled_peak_tree_pss_kib=%s\n' "$peak_rss" "$peak_pss"
        printf 'peak_tree_sum_vmhwm_kib=%s\nsupervisor_peak_vmhwm_kib=%s\n' "$peak_hwm" "$supervisor_hwm"
        printf 'pss_complete_samples=%s\nremaining_owned_processes=%s\n' "$pss_complete_samples" "$remaining"
    } > "$prefix.guard.txt"
}

cancel() {
    trap - HUP INT TERM
    stop_owned
    [ -z "$launcher" ] || wait "$launcher" 2>/dev/null
    exit_code=130
    count_remaining
    write_summary
    exit 130
}
trap cancel HUP INT TERM
IFS=' ' read -r started_value ignored < /proc/uptime
started_seconds=${started_value%.*}
toybox time -v "$@" > "$prefix.stdout.log" 2> "$prefix.stderr.log" &
launcher=$!
while kill -0 "$launcher" 2>/dev/null; do
    sample_memory
    if [ "$((tree_rss * 1024))" -gt "$limit" ]; then
        rejected=1
        stop_owned
        break
    fi
    now_seconds=${uptime_value%.*}
    if [ "$((now_seconds - started_seconds))" -ge "$timeout" ]; then
        timed_out=1
        stop_owned
        break
    fi
    sleep "$interval"
done
wait "$launcher"
exit_code=$?
count_remaining
if [ "$remaining" -gt 0 ]; then stop_owned; count_remaining; fi
write_summary
trap - HUP INT TERM
[ "$rejected" -eq 0 ] && [ "$timed_out" -eq 0 ] && [ "$remaining" -eq 0 ] || exit 137
exit "$exit_code"
