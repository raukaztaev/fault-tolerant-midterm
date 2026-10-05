# Fault-Tolerant University Information System (MIDTERM FTR)

Fault-tolerant university platform for the course "Fault Tolerance and Dependable Computing"
(AITU, 2026-2027). Five Spring Boot services behind an API gateway, PostgreSQL with an
application-level standby, chaos injection endpoints, and a full experiment pipeline
comparing a baseline (no fault tolerance) and a fault-tolerant configuration.

Author: Kaztayev Rauan Daurenuly, 255387, CSE-2507M

## Quick start

```bash
mvn package -DskipTests
docker compose build
RESILIENCE_MODE=ft docker compose up -d
# gateway on http://localhost:8080
curl -X POST http://localhost:8080/api/payments -H "Content-Type: application/json" \
     -d '{"paymentId":"P1","idempotencyKey":"IK1","accountId":"ACC-1","amountKzt":5000}'
```

Baseline configuration (single instances, no replication, chaos disabled):

```bash
RESILIENCE_MODE=baseline DB_REPLICATION=false \
GATEWAY_ROUTES_STUDENT=http://student-1:8081 \
GATEWAY_ROUTES_PAYMENT=http://payment-1:8082 \
GATEWAY_ROUTES_RECORDS=http://records-1:8083 \
GATEWAY_ROUTES_TIMETABLE=http://timetable-1:8084 \
docker compose up -d
```

## Experiments

```bash
python3 scripts/run_experiments.py experiments/ft ft            # 6 failure scenarios (FT)
python3 scripts/run_experiments.py experiments/baseline baseline
python3 scripts/analyze.py                                      # MTTF/MTBF/MTTR/availability
python3 scripts/diagrams.py                                     # regenerate figures
python3 scripts/gen_report.py                                   # regenerate the docx report
```

## Failure scenarios demonstrated

| Scenario | Injection | Fault-tolerant result |
|---|---|---|
| Application crash | `docker kill student-1` | 100% success via replica failover |
| Database failure | `docker stop postgres-primary` | 99.37% success via standby promotion |
| Service timeout | `/chaos/delay?delayMillis=2500` | 100% success via timeout + retry |
| Node failure | `docker pause records-1` | 100% success, round-robin skip |
| Interrupted payment | kill payment mid-transaction + replay | 99.51%, idempotent DUPLICATE, no double charge |
| High load | 24 concurrent workers, 30 s | 98.96% success |

## Software fault-tolerance mechanisms (6)

1. Retry with exponential backoff (gateway, DbRetry, payment processing)
2. Timeouts (gateway 6 s, inter-service 2 s)
3. Circuit breaker (Resilience4j, per downstream service)
4. Idempotent payment processing (UNIQUE idempotency key)
5. Checkpointing + journal recovery (payment_journal, payment_checkpoints, /payments/recovery)
6. Graceful degradation (timetable service returns DEGRADED instead of failing)

## Hardware fault-tolerance mechanisms (3+)

- Service replication (2 containers per service) with client-side load balancing
- Database replication (dual-write primary + standby) with automatic failover/promotion
- Docker health checks + `restart: unless-stopped`
- Documented analogues: ECC RAM and RAID-1 mirroring (see report section 6)

## Layout

```
common/            chaos injection, replicated DB, retry, backoff, health error mapping
student-service/   registration + lookup
payment-service/   payments: idempotency, journal, checkpoints, DB failover
records-service/   grades + transcripts
timetable-service/ schedule with graceful degradation
gateway/           routing, retry, circuit breaker, stats
scripts/           workload, experiments, analysis, diagrams, report generator
experiments/       raw request logs + summaries for both modes, analysis.json
docs/              architecture.png, rbd.png, fta.png, results.png
```

Report: `../MIDTERM_FTR_Report_Kaztayev_Rauan.docx`
