# SmartLoad Optimization API

A stateless Spring Boot REST service that finds the optimal combination of freight orders for a truck — maximising carrier payout while respecting weight, volume, hazmat, and route constraints.

## How to run

```bash
git clone <your-repo>
cd smartload
docker compose up --build
# → Service available at http://localhost:8080
```

The multi-stage Docker build runs all tests before packaging; a broken build will never produce an image.

---

## Health check

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## Example request

```bash
curl -X POST http://localhost:8080/api/v1/load-optimizer/optimize \
  -H "Content-Type: application/json" \
  -d @sample-request.json
```

Expected response:

```json
{
  "truck_id": "truck-123",
  "selected_order_ids": ["ord-001", "ord-002"],
  "total_payout_cents": 430000,
  "total_weight_lbs": 30000,
  "total_volume_cuft": 2100,
  "utilization_weight_percent": 68.18,
  "utilization_volume_percent": 70.0
}
```

---

## Run tests locally (no Docker required)

```bash
./mvnw test          # or: mvn test
```

---

## Algorithm

### Problem class
2-dimensional 0/1 knapsack (weight + volume) with additional hard constraints: lane compatibility and hazmat isolation.

### Approach — Recursive backtracking with upper-bound pruning

**Step 1 – Individual feasibility filter**  
Any order whose weight or volume alone exceeds truck capacity is discarded immediately.

**Step 2 – Lane grouping**  
Orders are grouped by normalised `(origin, destination)`. A truck serves exactly one lane per trip; cross-lane combinations are never considered.

**Step 3 – Hazmat isolation**  
Within a lane, hazmat orders **must not** ride with non-hazmat orders. Each group is solved independently and the higher-payout group wins.

**Step 4 – Backtracking with suffix-sum pruning**  
Orders are sorted descending by `payout_cents`. A suffix-sum array gives a tight upper bound at every node:

```
if (currentPayout + suffixPayout[idx] ≤ bestFoundSoFar) → prune
```

This prunes the vast majority of branches for realistic logistics inputs (high-value items tend to fill capacity quickly), finishing well under 800 ms for n = 22.

### Complexity
- Worst-case: O(2ⁿ) — unavoidable for exact 0/1 knapsack
- Average-case: far below 2²² due to pruning
- Space: O(n) recursion stack

---

## Constraints enforced

| Constraint | How |
|---|---|
| Weight limit | Hard check before each "take" branch |
| Volume limit | Hard check before each "take" branch |
| Hazmat isolation | Separate solve per hazmat / non-hazmat group per lane |
| Lane compatibility | Orders grouped by normalised origin→destination |
| pickup_date ≤ delivery_date | Validated in service before solving |
| Max 22 orders | `@Size(max=22)` on request → **HTTP 413** |
| Money as integer | All monetary values are `long` (cents), zero floats |

---

## HTTP status codes

| Code | Condition |
|---|---|
| 200 | Success (even if no orders can be selected) |
| 400 | Missing/invalid fields, malformed JSON, pickup > delivery |
| 413 | More than 22 orders in the payload |
| 500 | Unexpected server error |

---

## Tech stack

| Component | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| Validation | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| Build | Maven (multi-stage Docker caches the dependency layer) |
| Base image | `eclipse-temurin:21-jre-alpine` (~80 MB runtime) |
| Database | None — fully stateless |

---

## Caching / memoisation note

For production use the knapsack solver could be wrapped in a Caffeine cache keyed on a hash of `(truck, sorted order IDs)`. This trades memory for speed on repeated identical requests. It is intentionally not included here to keep the service purely stateless and zero-dependency at runtime.
