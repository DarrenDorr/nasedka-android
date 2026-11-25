#!/usr/bin/env bash
set -euo pipefail
root="$(mktemp -d "$RUNNER_TEMP/r1-enospc.XXXXXX")"
classes="$(mktemp -d "$RUNNER_TEMP/r1-enospc-classes.XXXXXX")"
mounted=0
cleanup() {
  rc=$?
  if [ "$mounted" = 1 ]; then sudo umount "$root" || rc=1; fi
  rmdir "$root" || true
  rm -rf "$classes"
  exit "$rc"
}
trap cleanup EXIT
sudo mount -t tmpfs -o "size=8m,uid=$(id -u),gid=$(id -g),mode=0700" tmpfs "$root"
mounted=1
mountpoint -q "$root"
python3 - "$root" <<'PY'
import pathlib, sqlite3, sys
root = pathlib.Path(sys.argv[1])
work = root / "restore-v2"
work.mkdir()
for path, label in [(root/"incuba.db","old-inc"), (root/"nasedka_farm.db","old-farm"),
                    (work/"incoming-inc.db","new-inc"), (work/"incoming-farm.db","new-farm")]:
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE sample(value TEXT NOT NULL)")
        db.execute("INSERT INTO sample VALUES(?)", (label,))
PY
src=app/src/main/java/org/nasedka/app
javac -d "$classes" "$src/RestoreProtocol.java" "$src/RestoreJournal.java" "$src/RestoreFileStorage.java" tools/r1/RestoreEnospcProbe.java
LC_ALL=C java -cp "$classes" org.nasedka.app.RestoreEnospcProbe "$root"
