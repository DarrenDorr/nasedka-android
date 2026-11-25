#!/usr/bin/env bash
# Only a disposable CI emulator; never fill the shared Android /data partition.
set -euo pipefail
adb="$1"; serial="$2"; out="$3"
mkdir -p "$out"
target=org.nasedka.app.debug
runner="$target.test/androidx.test.runner.AndroidJUnitRunner"
test "$serial" = emulator-5554
a() { timeout 90 "$adb" -s "$serial" "$@"; }
test "$(a shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(a shell getprop ro.build.version.sdk | tr -d '\r')" = 35
a shell am force-stop "$target"
uid="$(a shell run-as "$target" id -u | tr -d '\r')"
gid="$(a shell run-as "$target" id -g | tr -d '\r')"
[[ "$uid" =~ ^[0-9]+$ && "$gid" =~ ^[0-9]+$ && "$uid" -ge 10000 ]]
base="$(a shell run-as "$target" pwd | tr -d '\r')"
case "$base" in /data/user/0/org.nasedka.app.debug|/data/data/org.nasedka.app.debug) ;; *) echo "Unexpected app data path"; exit 1;; esac
a shell run-as "$target" mkdir -p files
a shell run-as "$target" mkdir files/r1-bounded-enospc
mount="$base/files/r1-bounded-enospc"
mounted=0
cleanup() {
  rc=$?
  if [ "$mounted" = 1 ]; then
    a shell am force-stop "$target" || rc=1
    a shell umount "$mount" || rc=1
  fi
  exit "$rc"
}
trap cleanup EXIT
# No disabling SELinux; root is only for provisioning a tiny isolated mount.
a root
a wait-for-device
test "$(a shell id -u | tr -d '\r')" = 0
before="$(a shell getenforce | tr -d '\r')"
test "$before" = Enforcing
label="$(a shell ls -Zd "$mount" | tr -d '\r' | awk '{print $1}')"
[[ "$label" =~ ^u:object_r:app_data_file:s0(:c[0-9]+(,c[0-9]+)*)?$ ]]
a shell mount -t tmpfs -o "size=8m,uid=$uid,gid=$gid,mode=0700" tmpfs "$mount"
mounted=1
a shell chcon "$label" "$mount"
test "$(a shell getenforce | tr -d '\r')" = Enforcing
a shell cat /proc/mounts | grep -F " $mount tmpfs " > "$out/mount.txt"
a shell df -k "$mount" | tee "$out/df.txt"
a shell am instrument -w -r -e class org.nasedka.app.RestoreProcessDeathTest#boundedAndroidEnospc -e r1_enospc 1 "$runner" | tee "$out/instrumentation.log"
grep -E 'OK[[:space:]]*\(1 test\)' "$out/instrumentation.log"
grep -Fx 'INSTRUMENTATION_CODE: -1' "$out/instrumentation.log"
a shell cat "$mount/enospc-verified.txt" | tr -d '\r' | tee "$out/verified.txt"
grep -E '^ANDROID_ENOSPC_PASS [0-9]+ [0-9]+$' "$out/verified.txt"
test "$(a shell getenforce | tr -d '\r')" = Enforcing
echo 'R1 Android bounded ENOSPC: PASS'
