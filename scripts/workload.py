#!/usr/bin/env python3
"""
Workload generator for the fault-tolerant university system.
Sends concurrent requests through the gateway and records per-request results
to a CSV log with timestamps, latency and outcome.
"""
import json
import sys
import time
import uuid
import threading
import urllib.request
import urllib.error

GATEWAY = "http://localhost:8080"


def post(path, body, timeout):
    req = urllib.request.Request(
        GATEWAY + path, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            r.read()
            return time.time() - started, r.status, "OK"
    except urllib.error.HTTPError as e:
        try:
            detail = json.loads(e.read().decode()).get("error", "http_" + str(e.code))
        except Exception:
            detail = "http_" + str(e.code)
        return time.time() - started, e.code, detail
    except Exception as e:
        return time.time() - started, 0, type(e).__name__


def get(path, timeout):
    started = time.time()
    try:
        with urllib.request.urlopen(GATEWAY + path, timeout=timeout) as r:
            r.read()
            return time.time() - started, r.status, "OK"
    except urllib.error.HTTPError as e:
        try:
            detail = json.loads(e.read().decode()).get("error", "http_" + str(e.code))
        except Exception:
            detail = "http_" + str(e.code)
        return time.time() - started, e.code, detail
    except Exception as e:
        return time.time() - started, 0, type(e).__name__


class Worker(threading.Thread):
    def __init__(self, worker_id, duration, mode, results, timeout):
        super().__init__()
        self.worker_id = worker_id
        self.duration = duration
        self.mode = mode
        self.results = results
        self.timeout = timeout
        self.stop_flag = False

    def run(self):
        deadline = time.time() + self.duration
        i = 0
        while time.time() < deadline and not self.stop_flag:
            i += 1
            rid = f"W{self.worker_id}-{i:04d}"
            if self.mode == "payments":
                body = {"paymentId": f"P-{rid}",
                        "idempotencyKey": f"IK-{rid}",
                        "accountId": f"ACC-W{self.worker_id}",
                        "amountKzt": 1000 + (i * 7) % 9000}
                latency, status, outcome = post("/api/payments", body, self.timeout)
            elif self.mode == "transcripts":
                latency, status, outcome = get(f"/api/records/transcript/W{self.worker_id}", self.timeout)
            else:  # registrations
                body = {"id": f"S-{rid}", "name": f"Student {rid}",
                        "program": "BSc CS", "semester": 1 + i % 8}
                latency, status, outcome = post("/api/students", body, self.timeout)
            self.results.append({
                "ts": round(time.time(), 3), "id": rid, "op": self.mode,
                "latency_s": round(latency, 3), "http": status, "outcome": outcome})


def run_workload(mode, workers, duration, timeout):
    results = []
    threads = [Worker(w, duration, mode, results, timeout) for w in range(workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return results


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "payments"
    n_workers = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    duration = float(sys.argv[3]) if len(sys.argv) > 3 else 30
    timeout = float(sys.argv[4]) if len(sys.argv) > 4 else 12
    out_file = sys.argv[5] if len(sys.argv) > 5 else None
    res = run_workload(mode, n_workers, duration, timeout)
    lines = [json.dumps(r) for r in res]
    text = "\n".join(lines)
    if out_file:
        with open(out_file, "w") as f:
            f.write(text + "\n")
    ok = sum(1 for r in res if r["outcome"] == "OK")
    print(f"mode={mode} workers={n_workers} duration={duration}s requests={len(res)} ok={ok} failed={len(res)-ok}")
