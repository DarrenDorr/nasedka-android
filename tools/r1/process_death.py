#!/usr/bin/env python3
"""Execute real process death and independent instrumentation recovery on a fresh CI emulator."""
import json, os, pathlib, re, subprocess, sys, time

adb, serial, output = sys.argv[1:]
target = "org.nasedka.app.debug"
runner = target + ".test/androidx.test.runner.AndroidJUnitRunner"
klass = "org.nasedka.app.RestoreProcessDeathTest"
cases = {
    "prepared": ("NONE", "NO_PENDING"),
    "first-db": ("PENDING", "OLD_GENERATION"),
    "settings": ("PENDING", "OLD_GENERATION"),
    "committed": ("COMMITTED", "NEW_GENERATION"),
    "rollback": ("PENDING", "OLD_GENERATION"),
    "rolled-back-cleanup": ("ROLLED_BACK", "OLD_GENERATION"),
}
out = pathlib.Path(output)
out.mkdir(parents=True, exist_ok=True)

def run(*args, timeout=90, check=True):
    p = subprocess.run([adb, "-s", serial, *args], text=True, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError(f"adb command failed: {args}: {p.stdout}")
    return p

def instrument(case, method):
    p = run("shell", "am", "instrument", "-w", "-r", "-e", "class", klass + "#" + method,
            "-e", "r1_case", case, runner, check=False)
    (out / (case + "-" + method + ".log")).write_text(p.stdout, encoding="utf-8")
    return p

def witness(case, filename):
    path = "files/r1-crash-fixtures/" + case + "/" + filename
    return run("shell", "run-as", target, "cat", path).stdout.strip().split()

def gone(pid):
    # ADB shell observes the kernel PID after the killed instrumentation exits.
    for _ in range(20):
        p = run("shell", "test", "!", "-d", "/proc/" + str(pid), check=False)
        if p.returncode == 0:
            return True
        time.sleep(.25)
    return False

results = []
assert runner in run("shell", "pm", "list", "instrumentation").stdout, "Test runner not installed"
for case, (phase, outcome) in cases.items():
    first = instrument(case, "interruptAtBoundary")
    ready = witness(case, "ready.txt")
    assert len(ready) == 3 and ready[0] == case and ready[2] == phase, ready
    pid = int(ready[1])
    assert pid > 0 and gone(pid), f"{case}: original PID {pid} still exists"
    assert not re.search(r"OK\s*\(1 test\)", first.stdout), "Crash invocation unexpectedly passed"
    second = instrument(case, "recoverInIndependentProcess")
    assert second.returncode == 0 and re.search(r"OK\s*\(1 test\)", second.stdout), second.stdout
    assert "INSTRUMENTATION_CODE: -1" in second.stdout, second.stdout
    verified = witness(case, "verified.txt")
    assert len(verified) == 3 and verified[0] == case and verified[2] == outcome, verified
    new_pid = int(verified[1])
    assert new_pid > 0 and new_pid != pid, "Recovery reused old PID"
    results.append({"case": case, "phase": phase, "killed_pid": pid,
                    "recovery_pid": new_pid, "outcome": outcome, "result": "PASS"})
    print(f"{case}: killed PID {pid}, independent PID {new_pid}, {outcome}", flush=True)
    (out / "process-death.json").write_text(json.dumps({"api": 35, "cases": results}, indent=2) + "\n")
assert len(results) == len(cases)
print("R1 process death: 6/6 actual process exits and independent recoveries PASS", flush=True)
