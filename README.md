# Vert.x RxJava3 vs Futures — Performance Comparison

https://github.com/tir-hub/vertx-RXjava3-Futures-comparison

Benchmarks six approaches to sequential, parallel, and resilient HTTP fan-out in Vert.x 4.5,
comparing plain `Future.compose` / `CompositeFuture.all` / manual timeout+retry against RxJava3
`Single.flatMap` / `Single.zip` / `.timeout().retry()`.

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
  - `RetryTimeoutFuturesServer` — sequential with per-call timeout and retry via `withTimeout()` + `.recover()`
  - `RetryTimeoutRxServer` — sequential with per-call timeout and retry via `.timeout(scheduler).retry(n)`
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

### Retry/timeout variants (20% backend fail rate)

```bash
mvn exec:exec -Pbackend -Dbackend.failRate=0.2
mvn exec:exec -Pfrt            # or -Prxrt
mvn exec:java -Pload           # 40 in-flight; expect ~2.4% errors
```

Each backend call gets a 500ms timeout and up to 2 retries. With a 20% fail rate,
~2.4% of requests exhaust all three attempts and return an error to the client.

### All profiles

| Profile | Server | Port |
|---|---|---|
| `-Pbackend` | BackendServer (server-3) | 8083 |
| `-Pfutures` | FuturesServer — sequential Futures | 8082 |
| `-Prx` | RxServer — sequential RxJava3 | 8082 |
| `-Ppfutures` | ParallelFuturesServer — parallel Futures | 8082 |
| `-Pprx` | ParallelRxServer — parallel RxJava3 | 8082 |
| `-Pfrt` | RetryTimeoutFuturesServer — retry/timeout Futures | 8082 |
| `-Prxrt` | RetryTimeoutRxServer — retry/timeout RxJava3 | 8082 |
| `-Pload` | LoadGenerator (server-1) | — |

### Tuning

```bash
-Dserver.heap=512m          # max heap for server-2 (default 256m)
-Dbackend.delayMs=250       # backend response delay in ms (default 0)
-Dbackend.failRate=0.2      # fraction of backend responses that return HTTP 500 (default 0)
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
| Retry/Timeout Futures (20% fail rate) | ~7,280 | 5.1ms | 10.1ms | 2.4% error rate; 500ms timeout, 2 retries |
| Retry/Timeout RxJava3 (20% fail rate) | ~7,190 | 5.2ms | 9.8ms | 2.4% error rate; 500ms timeout, 2 retries |

## Summary of findings

**RxJava3 adds no measurable overhead vs plain Vert.x Futures.**
Throughput and latency are statistically identical across every scenario tested.
The choice between the two is ergonomics, not performance.

| Scenario | Futures rps | RxJava3 rps | Futures p99 | RxJava3 p99 |
|---|---|---|---|---|
| Sequential, no delay | ~12,700 | ~13,000 | 4.2ms | 3.9ms |
| Sequential, 250ms delay | ~530 | ~530 | 771ms | 762ms |
| Parallel, 250ms delay | ~1,580 | ~1,580 | 309ms | 298ms |
| Retry/timeout, 20% fail rate | ~7,280 | ~7,190 | 10.1ms | 9.8ms |

**Parallel vs sequential is the only decision that changes performance.**
With a 250ms backend delay, firing three calls in parallel instead of sequence
triples throughput (530 → 1,580 rps) and cuts latency by 3×. RxJava3 or Futures
makes no difference here.

**RxJava3 wins on conciseness when resilience is involved.**
Timeout + retry is 3 operator lines in RxJava3 vs a 12-line `withTimeout` helper
plus recursive retry bookkeeping in plain Futures. For production code that needs
retries and timeouts, RxJava3's declarative style pays off.

**RxJava3 shows marginally better tail latency in every run.**
Short-lived subscription objects are young-gen garbage that G1GC collects cheaply,
smoothing p99 by a few percent. This is consistent but not large enough to drive
a technology choice.

**Watch your connection pool size.**
Sequential variants need at most `in-flight` connections to the backend.
Parallel variants need `in-flight × 3`. Undersizing the pool causes queuing that
can exceed your request timeout and trigger an error cascade.
See `WebClientOptions.setMaxPoolSize()` in each server-2 variant.

## Code comparison: frt vs rxrt

The fan-out and fetch code maps one-for-one between the two styles.
The timeout/retry is where they diverge most.

### Fetching a value (with status check)

```java
// Futures
private Future<Integer> fetchValue() {
    return client.get(BACKEND_PORT, "localhost", "/value")
        .send()
        .compose(resp -> resp.statusCode() == 200
            ? Future.succeededFuture(resp.bodyAsJsonObject().getInteger("value"))
            : Future.failedFuture("HTTP " + resp.statusCode()));
}

// RxJava3
private Single<Integer> fetchValue() {
    return rxClient.get(BACKEND_PORT, "localhost", "/value")
        .rxSend()
        .flatMap(resp -> resp.statusCode() == 200
            ? Single.just(resp.bodyAsJsonObject().getInteger("value"))
            : Single.error(new RuntimeException("HTTP " + resp.statusCode())));
}
```

### Timeout + retry

```java
// Futures — explicit: Promise races against a vertx timer, recursive retry
private Future<Integer> fetchValueRT(int retriesLeft) {
    return withTimeout(fetchValue(), TIMEOUT_MS)
        .recover(e -> retriesLeft > 0
            ? fetchValueRT(retriesLeft - 1)
            : Future.failedFuture(e));
}

private <T> Future<T> withTimeout(Future<T> future, long ms) {
    Promise<T> promise = Promise.promise();
    long timerId = vertx.setTimer(ms, id -> promise.tryFail("timeout after " + ms + "ms"));
    future.onComplete(ar -> {
        vertx.cancelTimer(timerId);
        if (ar.succeeded()) promise.tryComplete(ar.result());
        else promise.tryFail(ar.cause());
    });
    return promise.future();
}

// RxJava3 — declarative: operators compose directly
private Single<Integer> fetchValueRT() {
    return fetchValue()
        .timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS, scheduler)
        .retry(MAX_RETRIES);
}
```

### Sequential fan-out

```java
// Futures
fetchValueRT(MAX_RETRIES)
    .compose(v1 -> fetchValueRT(MAX_RETRIES).map(v2 -> v1 + v2))
    .compose(sum12 -> fetchValueRT(MAX_RETRIES).map(v3 -> sum12 + v3))
    .onSuccess(total -> ctx.response()...end(...))
    .onFailure(ctx::fail);

// RxJava3
fetchValueRT()
    .flatMap(v1 -> fetchValueRT().map(v2 -> v1 + v2))
    .flatMap(sum12 -> fetchValueRT().map(v3 -> sum12 + v3))
    .subscribe(
        total -> ctx.response()...end(...),
        ctx::fail);
```

`compose`/`flatMap` and `Future`/`Single` swap one-for-one throughout.
The only real structural difference is timeout/retry: Futures requires ~12 lines
of explicit plumbing (`withTimeout` helper + recursive `retriesLeft` parameter),
while RxJava3 expresses the same thing in 3 lines of operators. That is the
clearest argument for RxJava3 — not throughput, but conciseness when resilience
patterns are involved.

## Stack

- Vert.x 4.5.10
- vertx-web, vertx-web-client, vertx-rx-java3
- Java 21, Maven
