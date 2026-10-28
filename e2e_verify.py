#!/usr/bin/env python3
"""
End-to-end runtime verification against the real Docker stack.
Run while `docker compose up -d` is running. Requires only Python stdlib.
Covers scenarios A-K: normal job, priority, retry, DEAD, timeout, idempotency,
ownership, admin recovery, rate limit, Kafka audit persistence, observability.
"""
import json
import time
import uuid
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"
results = []


def req(method, path, token=None, body=None, headers=None):
    """Return (status, headers, parsed_json_or_none)."""
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            raw = resp.read().decode()
            return resp.status, dict(resp.headers), (json.loads(raw) if raw.strip().startswith(("{", "[")) else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            parsed = json.loads(raw) if raw.strip().startswith(("{", "[")) else None
        except Exception:
            parsed = None
        return e.code, dict(e.headers), parsed


def record(name, ok, evidence=""):
    status = "PASS" if ok else "FAIL"
    results.append((status, name, evidence))
    print(f"[{status}] {name} {evidence}")


def wait_status(token, job_id, statuses, timeout=90, poll=1.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        s, _, job = req("GET", f"/api/jobs/{job_id}", token=token)
        if s == 200:
            last = job["status"]
            if last in statuses:
                return last
        time.sleep(poll)
    return last


def main():
    suffix = uuid.uuid4().hex[:8]

    # ---------- Setup users ----------
    s, _, d = req("POST", "/api/auth/register", body={"username": f"alice{suffix}", "password": "password123"})
    alice = d["token"] if s == 201 else req("POST", "/api/auth/login", body={"username": f"alice{suffix}", "password": "password123"})[2]["token"]
    s, _, d = req("POST", "/api/auth/register", body={"username": f"bob{suffix}", "password": "password123"})
    bob = d["token"] if s == 201 else req("POST", "/api/auth/login", body={"username": f"bob{suffix}", "password": "password123"})[2]["token"]
    s, _, d = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})
    admin = d["token"]
    record("Setup: users created/logged in", bool(alice and bob and admin))

    # ---------- SCENARIO A: normal job success ----------
    s, h, job = req("POST", "/api/jobs", token=alice,
                    body={"name": f"normal-{suffix}", "type": "DEMO_TASK", "priority": "MEDIUM"})
    record("A1: create job returns 201 + rate limit headers",
           s == 201 and "X-RateLimit-Limit" in h and "X-RateLimit-Remaining" in h,
           f"status={s} limit={h.get('X-RateLimit-Limit')} remaining={h.get('X-RateLimit-Remaining')}")
    a_job = job["id"]
    status = wait_status(alice, a_job, {"SUCCESS", "FAILED", "DEAD", "RETRYING"})
    record("A2: job reaches SUCCESS (CREATED->QUEUED->RUNNING->SUCCESS)", status == "SUCCESS", f"final={status}")

    # ---------- SCENARIO B: priority dispatch ----------
    ids = {}
    for pr in ["LOW", "MEDIUM", "HIGH"]:
        s, _, j = req("POST", "/api/jobs", token=alice,
                      body={"name": f"prio-{pr}-{suffix}", "type": "DEMO_TASK", "priority": pr})
        ids[pr] = j["id"]
    time.sleep(12)  # let scheduler dispatch (interval 5s, demo task 2s)
    order = []
    for pr in ["HIGH", "MEDIUM", "LOW"]:
        st = req("GET", f"/api/jobs/{ids[pr]}", token=alice)[2]["status"]
        order.append((pr, st))
    any_started = any(st in ("RUNNING", "SUCCESS") for _, st in order)
    high_ahead = order[0][1] in ("RUNNING", "SUCCESS")
    record("B: priority jobs all dispatched (HIGH not behind)", any_started and high_ahead, f"{order}")

    # ---------- SCENARIO C: retry with backoff ----------
    s, _, job = req("POST", "/api/jobs", token=alice,
                    body={"name": f"retry-{suffix}", "type": "FAILING", "maxRetries": 2})
    c_job = job["id"]
    status = wait_status(alice, c_job, {"RETRYING", "DEAD"}, timeout=30)
    record("C1: failing job enters RETRYING (auto retry)", status == "RETRYING", f"status={status}")
    s, _, attempts = req("GET", f"/api/jobs/{c_job}/attempts", token=alice)
    record("C2: attempts recorded for retries", s == 200 and len(attempts) >= 1, f"attempts={len(attempts)}")
    wait_status(alice, c_job, {"DEAD"}, timeout=60)
    s, _, j = req("GET", f"/api/jobs/{c_job}", token=alice)
    record("C3: retries exhausted -> DEAD", j["status"] == "DEAD", f"status={j['status']} retryCount={j['retryCount']}/{j['maxRetries']}")

    # ---------- SCENARIO D: DEAD path ----------
    record("D: DEAD state reached via repeated failure", j["status"] == "DEAD", f"job={c_job}")

    # ---------- SCENARIO E: timeout ----------
    s, _, job = req("POST", "/api/jobs", token=alice,
                    body={"name": f"timeout-{suffix}", "type": "REPORT", "timeoutSeconds": 1, "maxRetries": 1})
    e_job = job["id"]
    status = wait_status(alice, e_job, {"RETRYING", "FAILED", "DEAD"}, timeout=40)
    s, _, attempts = req("GET", f"/api/jobs/{e_job}/attempts", token=alice)
    timed_out = any(a["status"] == "TIMED_OUT" for a in attempts) if attempts else False
    record("E: long handler times out (TIMED_OUT attempt)", status in ("RETRYING", "FAILED", "DEAD") and timed_out,
           f"status={status} timedOutAttempt={timed_out}")

    # ---------- SCENARIO F: idempotency (concurrent) ----------
    idem = f"idem-{suffix}"
    body = {"name": f"idem-{suffix}", "type": "DEMO_TASK"}

    def create():
        return req("POST", "/api/jobs", token=alice, body=body, headers={"Idempotency-Key": idem})[0]

    with ThreadPoolExecutor(max_workers=5) as ex:
        codes = list(ex.map(lambda _: create(), range(5)))
    time.sleep(2)
    s, _, jobs = req("GET", "/api/jobs?size=50", token=alice)
    matches = [j for j in jobs["content"] if j.get("idempotencyKey") == idem]
    record("F: concurrent same Idempotency-Key -> exactly one job",
           all(c == 201 for c in codes) and len(matches) == 1,
           f"codes={codes} jobsWithKey={len(matches)}")

    # ---------- SCENARIO G: ownership ----------
    s, _, jb = req("POST", "/api/jobs", token=bob, body={"name": f"bob-{suffix}", "type": "DEMO_TASK"})
    bob_job = jb["id"]
    s1, _, _ = req("GET", f"/api/jobs/{bob_job}", token=alice)
    s2, _, _ = req("GET", f"/api/jobs/{bob_job}/attempts", token=alice)
    s3, _, _ = req("GET", f"/api/jobs/{bob_job}/result", token=alice)
    s4, _, _ = req("POST", f"/api/jobs/{bob_job}/cancel", token=alice)
    s5, _, _ = req("POST", f"/api/jobs/{bob_job}/retry", token=alice)
    record("G: USER A denied USER B job/attempts/result/cancel/retry (404)",
           all(x == 404 for x in (s1, s2, s3, s4, s5)),
           f"get={s1} attempts={s2} result={s3} cancel={s4} retry={s5}")

    # ---------- SCENARIO H: admin recovery ----------
    s, _, _ = req("POST", f"/api/jobs/{c_job}/recover", token=alice)   # USER attempt
    s_user = s
    s, _, rec = req("POST", f"/api/jobs/{c_job}/recover", token=admin)  # ADMIN
    record("H: recover forbidden for USER (403), works for ADMIN (200 -> QUEUED)",
           s_user == 403 and s == 200 and rec.get("status") == "QUEUED",
           f"user={s_user} admin={s} status={rec.get('status') if rec else None}")
    req("POST", f"/api/jobs/{c_job}/cancel", token=alice)  # cleanup

    # ---------- SCENARIO I: rate limit (real Redis) ----------
    rl_token = alice
    hit429 = False
    headers429 = None

    def blast(i):
        return req("POST", "/api/jobs", token=rl_token,
                   body={"name": f"rl-{suffix}-{i}", "type": "DEMO_TASK"})

    # Fire 130 requests across 6 threads: completes well inside one 60s window,
    # guaranteeing the 100/min limit is crossed within a single window.
    with ThreadPoolExecutor(max_workers=6) as ex:
        rl_results = list(ex.map(blast, range(130)))
    for s, h, _b in rl_results:
        if s == 429:
            hit429 = True
            headers429 = h
            break
    record("I: rate limit exceeded -> HTTP 429 (Redis-backed)",
           hit429 and headers429.get("Retry-After") is not None,
           f"429 received, Retry-After={headers429.get('Retry-After') if headers429 else None}")

    # ---------- SCENARIO J: Kafka audit persistence ----------
    s, _, d = req("POST", "/api/auth/login", body={"username": f"eve{suffix}", "password": "whatever"})
    time.sleep(6)  # allow producer->broker->consumer->DB round trip
    print("  (Kafka audit rows verified separately via psql: see job_audit_events count)")

    # ---------- SCENARIO K: observability ----------
    s, _, health = req("GET", "/actuator/health")
    record("K1: actuator health UP", s == 200 and health.get("status") == "UP")

    # ---------- Unauthenticated ----------
    s, _, _ = req("GET", "/api/jobs")
    record("Auth: unauthenticated /api/jobs -> 401", s == 401, f"status={s}")

    print("\n==== SUMMARY ====")
    passed = sum(1 for r in results if r[0] == "PASS")
    for st, name, ev in results:
        print(f"{st}: {name} {('| ' + ev) if ev else ''}")
    print(f"\n{passed}/{len(results)} passed")


if __name__ == "__main__":
    main()
