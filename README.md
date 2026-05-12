# Vert.x RxJava3 vs Futures — Performance Comparison

https://github.com/tir-hub/vertx-RXjava3-Futures-comparison

Benchmarks four approaches to sequential and parallel HTTP fan-out in Vert.x 4.5,
comparing plain `Future.compose` / `CompositeFuture.all` against RxJava3
`Single.flatMap` / `Single.zip`.

## Architecture

```
[Server-1: LoadGenerator] ──▶ [Server-2: one of four variants] ──▶ [Server-3: BackendServer]
                                                                  ──▶ [Server-3]
                                                                  ──▶ [Server-3]
```

- **Server-3** — trivial backend returning a random integer; supports a configurable
  delay (`-Dbackend.delayMs=250`) to simulate a real downstream (DB, external API)
- **Server-2** — receives a `/sum` request, calls Server-3 three times, returns the sum;
  four variants:
  - `FuturesServer` — sequential via `Future.compose`
  - `RxServer` — sequential via `Single.flatMap`
  - `ParallelFuturesServer` — parallel via `CompositeFuture.all`
  - `ParallelRxServer` — parallel via `Single.zip`
- **Server-1** — closed-loop load generator; keeps a fixed number of requests in flight
  and reports throughput + latency percentiles every 5 seconds

Each Server-2 variant runs as a **single Vert.x event loop thread** (`instances=1`),
matching a cheap single-core GCE VM deployment.

## Requirements

- Java 21
- Maven 3.x

## Running

Start each server in its own terminal. Wait for the "listening" message before
starting the next one.

### No delay (raw framework overhead)

```bash
mvn exec:exec -Pbackend
mvn exec:exec -Pfutures        # or -Prx
mvn exec:java -Pload           # 4 verticles × 10 concurrency = 40 in-flight
```

### 250ms backend delay (simulated downstream latency)

```bash
mvn exec:exec -Pbackend -Dbackend.delayMs=250
mvn exec:exec -Pfutures        # or -Prx  or  -Ppfutures  or  -Pprx
mvn exec:java -Pload -Dload.concurrency=100   # 400 in-flight
```

### All profiles

| Profile | Server | Port |
|---|---|---|
| `-Pbackend` | BackendServer (server-3) | 8083 |
| `-Pfutures` | FuturesServer — sequential Futures | 8082 |
| `-Prx` | RxServer — sequential RxJava3 | 8082 |
| `-Ppfutures` | ParallelFuturesServer — parallel Futures | 8082 |
| `-Pprx` | ParallelRxServer — parallel RxJava3 | 8082 |
| `-Pload` | LoadGenerator (server-1) | — |

### Tuning

```bash
-Dserver.heap=512m          # max heap for server-2 (default 256m)
-Dbackend.delayMs=250       # backend response delay in ms (default 0)
-Dload.concurrency=100      # in-flight requests per load verticle (default 10)
-Dexec.args="4"             # load generator verticle count (default 4)
```

## Load generator output

```
  rps=1580    | µs: avg=252074  p50=251516  p95=255858  p99=261836 | errors=0
```

Latency is in **microseconds**. Each row is a 5-second window.

## Results (laptop, background apps closed, 256m heap)

Full raw data in [results.md](results.md). Summary:

| Variant | rps | p50 | p99 max | Notes |
|---|---|---|---|---|
| Sequential Futures (250ms delay) | ~530 | 754ms | 771ms | flat, no degradation |
| Sequential RxJava3 (250ms delay) | ~530 | 754ms | 762ms | flat, no degradation |
| Parallel Futures (250ms delay) | ~1,580 | 252ms | 309ms | flat, no degradation |
| Parallel RxJava3 (250ms delay) | ~1,580 | 252ms | 298ms | flat, no degradation |
| Sequential Futures (no delay) | ~12,700 peak | 3.1ms | 4.2ms | GC floor at ~10,700 rps |
| Sequential RxJava3 (no delay) | ~13,000 peak | 3.0ms | 3.9ms | GC floor at ~10,700 rps |

## Key findings

**Sequential vs parallel is the only choice that materially matters.**
With a 250ms backend delay, parallel calls triple throughput (530 → 1,580 rps)
and cut latency by 3× (754ms → 252ms). This is simply 3×250ms collapsing to 1×250ms.
Use parallel whenever the three downstream calls are independent.

**RxJava3 overhead is negligible in practice.**
Throughput is identical to plain Futures in every scenario. RxJava3 shows marginally
tighter p99 (Rx short-lived subscription objects suit G1GC's young-gen collector).
The choice between the two is ergonomics, not performance.

**`Single.zip` is the natural fit for parallel fan-out.**
```java
// RxJava3 — intent is explicit
Single.zip(fetchValue(), fetchValue(), fetchValue(),
    (v1, v2, v3) -> v1 + v2 + v3)

// Futures — requires capturing futures before the combinator
Future<Integer> f1 = fetchValue(), f2 = fetchValue(), f3 = fetchValue();
CompositeFuture.all(f1, f2, f3)
    .map(cf -> f1.result() + f2.result() + f3.result())
```

**Watch your connection pool size.**
Sequential variants need at most `in-flight` connections to the backend.
Parallel variants need `in-flight × 3`. Undersizing the pool causes queuing that
can exceed your request timeout and trigger an error cascade.
See `WebClientOptions.setMaxPoolSize()` in each server-2 variant.

## Stack

- Vert.x 4.5.10
- vertx-web, vertx-web-client, vertx-rx-java3
- Java 21, Maven
