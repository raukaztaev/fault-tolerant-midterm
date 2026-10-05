#!/usr/bin/env python3
"""
Experiment runner: executes the six required failure scenarios against the
running system and records a structured JSON result for each experiment.
Each experiment captures: detection time, recovery time, failed requests,
successful requests and data consistency.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from workload import run_workload

GATEWAY = "http://localhost:8080"
OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else "experiments/run1"
LABEL = sys.argv[2] if len(sys.argv) > 2 else "ft"
os.makedirs(OUT_DIR, exist_ok=True)

COMPOSE = ["docker", "compose"]


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)


def http(method, path, body=None, timeout=30):
    import urllib.request
    req = urllib.request.Request(GATEWAY + path, method=method,
                                 data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except Exception as e:
        try:
            return getattr(e, "code", 0), {"error": str(e)}
        except Exception:
            return 0, {"error": str(e)}


def chaos(container, mode, delay_ms=800, port=8081):
    """Enable chaos mode inside a service container."""
    port_map = {"student": 8081, "payment": 8082, "records": 8083, "timetable": 8084}
    p = port_map[container]
    return sh(f"docker exec university-ft-{container}-1-1 curl -s -X POST "
              f"'http://localhost:{p}/chaos/{mode}?delayMillis={delay_ms}'").stdout


def reset_chaos(container):
    port_map = {"student": 8081, "payment": 8082, "records": 8083, "timetable": 8084}
    p = port_map[container]
    return sh(f"docker exec university-ft-{container}-1-1 curl -s -X POST http://localhost:{p}/chaos/reset").stdout


def reset_stats():
    http("POST", "/api/reset-stats")


def gateway_stats():
    _, s = http("GET", "/api/stats")
    return s


def measure_window(op, workers, seconds, note=""):
    """Runs a workload in a background window and returns (results, elapsed)."""
    res = run_workload(op, workers, seconds, timeout=12)
    return res


def summarize(res):
    ok = [r for r in res if r["outcome"] == "OK"]
    failed = [r for r in res if r["outcome"] != "OK"]
    lat = [r["latency_s"] for r in ok] or [0]
    return {
        "requests": len(res),
        "ok": len(ok),
        "failed": len(failed),
        "success_rate": round(100 * len(ok) / max(1, len(res)), 2),
        "avg_latency_ok_s": round(sum(lat) / len(lat), 3),
        "max_latency_ok_s": round(max(lat), 3),
        "outcomes": {o: sum(1 for r in failed if r["outcome"] == o) for o in set(r["outcome"] for r in failed)},
    }


def save(name, data):
    with open(os.path.join(OUT_DIR, f"{name}.json"), "w") as f:
        json.dump(data, f, indent=2)
    print(f"[saved] {name}.json  ok={data.get('summary', {}).get('ok')} failed={data.get('summary', {}).get('failed')}")


def detect_service_down(path, timeout_s=30):
    """Polls a gateway path until it answers OK again; returns detection+recovery info."""
    started = time.time()
    first_ok_after = None
    while time.time() - started < timeout_s:
        _, body = http("GET", path, timeout=5)
        if body.get("status") == "OK" or body.get("id") or body.get("courses") is not None or body.get("count") is not None:
            return time.time() - started
        time.sleep(0.5)
    return None


# ---------------------------------------------------------------- experiments

def exp_app_crash():
    """E1: kill one application instance (student-1) under load."""
    reset_stats()
    baseline = measure_baseline("registrations", 4, 5)
    t0 = time.time()
    kill = subprocess.Popen(["docker", "kill", "university-ft-student-1-1"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    kill.wait()
    kill_done = time.time() - t0
    res = measure_window("registrations", 4, 25)
    rec = detect_service_down("/api/students/S001", 60)
    t1 = time.time()
    sh("docker compose up -d student-1")
    recovery = None
    started = time.time()
    while time.time() - started < 60:
        st, b = http("GET", "/api/students/S001", timeout=5)
        if st == 200:
            recovery = time.time() - started
            break
        time.sleep(1)
    return {
        "failure": "application crash (docker kill student-1)",
        "kill_time_s": round(kill_done, 2),
        "recovery_restart_s": round(recovery, 2) if recovery else None,
        "summary": summarize(res),
    }, res


def measure_baseline(op, w, s):
    return measure_window(op, w, s)


def exp_db_failure():
    """E2: stop the primary postgres and measure payment failover to standby."""
    reset_stats()
    base = measure_baseline("payments", 3, 8)
    t0 = time.time()
    sh("docker stop university-ft-postgres-primary-1")
    stop_done = time.time() - t0
    res = measure_window("payments", 3, 35)
    t1 = time.time()
    sh("docker start university-ft-postgres-primary-1")
    started = time.time()
    recovery = None
    while time.time() - started < 60:
        st, b = http("POST", "/api/payments", {"paymentId": f"PRECOV-{int(time.time())}",
                                               "idempotencyKey": f"IKREC-{int(time.time())}",
                                               "accountId": "ACC-REC", "amountKzt": 100}, timeout=8)
        if st == 200 and b.get("status") == "COMMITTED":
            recovery = time.time() - t1
            break
        time.sleep(1)
    time.sleep(5)
    return {
        "failure": "primary database stopped (docker stop postgres-primary)",
        "stop_time_s": round(stop_done, 2),
        "recovery_restart_s": round(recovery, 2) if recovery else None,
        "summary": summarize(res),
    }, res


def exp_service_timeout():
    """E3: inject a 2.5 s delay into payment-1; gateway timeout + retry must absorb it."""
    reset_stats()
    base = measure_baseline("payments", 3, 6)
    chaos("payment", "delay", 2500)
    res = measure_window("payments", 3, 20)
    reset_chaos("payment")
    after = measure_baseline("payments", 3, 6)
    return {
        "failure": "network delay 2.5 s injected into payment-1 (timeout + retry expected)",
        "baseline_avg_latency_s": summarize(base)["avg_latency_ok_s"],
        "summary": summarize(res),
        "after_reset": summarize(after),
    }, res


def exp_node_failure():
    """E4: pause a whole set of containers (simulates node failure) - records service."""
    reset_stats()
    base = measure_baseline("transcripts", 3, 6)
    t0 = time.time()
    sh("docker pause university-ft-records-1-1")
    pause_done = time.time() - t0
    res = measure_window("transcripts", 3, 20)
    sh("docker unpause university-ft-records-1-1")
    unpause = time.time() - t0
    rec = detect_service_down("/api/records/transcript/W1", 15)
    return {
        "failure": "node failure simulated by pausing records-1 container",
        "pause_time_s": round(pause_done, 2),
        "summary": summarize(res),
    }, res


def exp_corrupted_transaction():
    """E5: interrupt a payment (kill payment-1 mid-flight) and verify idempotent replay + journal recovery."""
    reset_stats()
    base = measure_baseline("payments", 2, 6)
    # Fire a payment and kill the instance handling it right after the request starts.
    import threading
    out = {}
    def fire():
        st, b = http("POST", "/api/payments", {"paymentId": "P-INTERRUPT", "idempotencyKey": "IK-INT",
                                               "accountId": "ACC-INT", "amountKzt": 7000}, timeout=25)
        out["result"] = (st, b)
    t0 = time.time()
    th = threading.Thread(target=fire)
    th.start()
    time.sleep(0.15)
    sh("docker kill university-ft-payment-1-1")
    th.join()
    kill_done = time.time() - t0
    time.sleep(3)
    # Replay with the same idempotency key: must not double-charge.
    st, b = http("POST", "/api/payments", {"paymentId": "P-INT", "idempotencyKey": "IK-INT",
                                           "accountId": "ACC-INT", "amountKzt": 7000}, timeout=25)
    replay = b
    time.sleep(2)
    st, rec = http("POST", "/api/payments/recovery", {}, timeout=20)
    sh("docker compose up -d payment-1")
    started = time.time()
    while time.time() - started < 60:
        hst, hb = http("GET", "/api/payments/journal", timeout=5)
        if hst == 200:
            break
        time.sleep(1)
    res = measure_window("payments", 3, 8)
    return {
        "failure": "payment interrupted by killing payment-1 mid-transaction",
        "kill_time_s": round(kill_done, 2),
        "replay_status": replay.get("status") if isinstance(replay, dict) else str(replay),
        "recovery_report": rec,
        "summary": summarize(res),
    }, res


def exp_high_load():
    """E6: 24 concurrent workers for 30 s; measure degradation and error rate."""
    reset_stats()
    res = measure_window("payments", 24, 30)
    stats = gateway_stats()
    return {
        "failure": "high load: 24 concurrent payment workers for 30 s",
        "summary": summarize(res),
        "gateway_stats": stats,
    }, res


def main():
    experiments = {
        "E1_app_crash": exp_app_crash,
        "E2_db_failure": exp_db_failure,
        "E3_service_timeout": exp_service_timeout,
        "E4_node_failure": exp_node_failure,
        "E5_corrupted_transaction": exp_corrupted_transaction,
        "E6_high_load": exp_high_load,
    }
    selected = sys.argv[3:] if len(sys.argv) > 3 else list(experiments)
    for name in selected:
        print(f"=== running {name} ===")
        result, raw = experiments[name]()
        result["mode"] = LABEL
        result["ts"] = time.strftime("%Y-%m-%d %H:%M:%S")
        save(name, result)
        with open(os.path.join(OUT_DIR, f"{name}_requests.jsonl"), "w") as f:
            for r in raw:
                f.write(json.dumps(r) + "\n")


if __name__ == "__main__":
    import threading
    main()
