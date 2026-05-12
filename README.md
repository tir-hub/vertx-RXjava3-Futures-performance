# Vert.x RxJava3 vs Futures — Performance Comparison

https://github.com/tir-hub/vertx-RXjava3-Futures-comparison

Benchmarks eight approaches to sequential, parallel, and resilient HTTP fan-out in Vert.x 4.5,
comparing plain `Future.compose` / `CompositeFuture.all` / manual timeout+retry against RxJava3
`Single.flatMap` / `Single.zip` / `.timeout().retry()` and Java 21 virtual threads.

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
  - `VirtualThreadServer` — sequential blocking calls on Java 21 virtual threads; no reactive API
  - `VirtualThreadRetryServer` — same as above with per-call timeout and retry via a plain `for` loop
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

### Virtual threads (Java 21 blocking style)

```bash
mvn exec:exec -Pbackend
mvn exec:exec -Pvt
mvn exec:java -Pload           # no delay: 40 in-flight
mvn exec:java -Pload -Dload.concurrency=100   # 250ms delay: 400 in-flight
```

Note: the `vt` server uses `java.net.http.HttpClient` forced to HTTP/1.1.
The default client negotiates HTTP/2 with Vert.x and hits `MAX_CONCURRENT_STREAMS`
limits under high concurrency.

### Retry/timeout variants (20% backend fail rate)

```bash
mvn exec:exec -Pbackend -Dbackend.failRate=0.2
mvn exec:exec -Pfrt            # or -Prxrt  or  -Pvtrt
mvn exec:java -Pload           # 40 in-flight; expect ~2.4% errors
```

Each backend call gets a 500ms timeout and up to 2 retries. With a 20% fail rate,
~2.4% of requests exhaust all three attempts and return an error to the client.

**Why 2.4% and not 20%?** The math has two steps.

*Step 1 — one `fetchValueRT` call exhausting all 3 attempts:*
Each attempt is independent, so multiply the per-attempt fail probability:
```
P(all 3 attempts fail) = 0.2 × 0.2 × 0.2 = 0.2³ = 0.008  (0.8%)
```

*Step 2 — a whole request failing:*
Each request makes 3 sequential `fetchValueRT` calls. It succeeds only if all 3
succeed. Rather than adding up all the ways one, two, or three calls could fail,
use the complement: compute the probability of success and subtract from 1.
```
P(one fetchValueRT succeeds) = 1 − 0.008 = 0.992
P(all 3 succeed)             = 0.992³    ≈ 0.976
P(at least one fails)        = 1 − 0.992³ ≈ 0.024  (2.4%)
```

The general formula: `P(request fails) = 1 − (1 − p^(retries+1))^calls`
where `p` is the per-attempt fail rate. With `p=0.2`, `retries=2`, `calls=3` → 2.4%.

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
| `-Pvt` | VirtualThreadServer — Java 21 virtual threads | 8082 |
| `-Pvtrt` | VirtualThreadRetryServer — Java 21 virtual threads + retry | 8082 |
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
| Retry/Timeout Virtual Threads (20% fail rate) | ~8,500 | 4.3ms | 8.9ms | 2.4% error rate; 500ms timeout, 2 retries |

## Summary of findings

**RxJava3 adds no measurable overhead vs plain Vert.x Futures.**
Throughput and latency are statistically identical across every scenario tested.
The choice between the two is ergonomics, not performance.

| Scenario | Futures rps | RxJava3 rps | Virtual Threads rps | Futures p99 | RxJava3 p99 | VT p99 |
|---|---|---|---|---|---|---|
| Sequential, no delay | ~12,700 | ~13,000 | **~17,000** | 4.2ms | 3.9ms | **3.6ms** |
| Sequential, 250ms delay | ~530 | ~530 | ~530 | 771ms | 762ms | ~780ms |
| Parallel, 250ms delay | ~1,580 | ~1,580 | — | 309ms | 298ms | — |
| Retry/timeout, 20% fail rate | ~7,280 | ~7,190 | **~8,500** | 10.1ms | 9.8ms | **8.9ms** |

**Parallel vs sequential is the only decision that changes performance.**
With a 250ms backend delay, firing three calls in parallel instead of sequence
triples throughput (530 → 1,580 rps) and cuts latency by 3×. RxJava3 or Futures
makes no difference here.

**Java 21 virtual threads outperform reactive when I/O is fast, converge when it is slow.**
With no backend delay, virtual threads deliver ~17,000 rps vs ~13,000 for Futures/Rx — a 30%
advantage. The Java `HttpClient` + straight blocking code has less per-call overhead than the
reactive callback machinery when I/O returns in microseconds. At 250ms backend delay all three
converge at ~530 rps: the 3×250ms serial latency swamps any framework difference. The crossover
is somewhere in the low single-digit millisecond range. The advantage carries through to the retry scenario (no backend delay, 20% fail rate):
virtual threads hit ~8,500 rps vs ~7,200 for Futures/Rx. With a 250ms backend delay
all three would converge, as the serial I/O latency dominates framework overhead.

**Virtual threads produce the simplest resilience code.**
Retry in blocking code is a `for` loop. Timeout is a `Duration` on the request.
No helper methods, no recursive parameters, no operators needed. RxJava3's
`.timeout().retry()` is the next cleanest. Plain Futures requires the most
boilerplate: a 12-line `withTimeout` helper plus a recursive `retriesLeft` parameter.

**RxJava3 shows marginally better tail latency in every run.**
Short-lived subscription objects are young-gen garbage that G1GC collects cheaply,
smoothing p99 by a few percent. This is consistent but not large enough to drive
a technology choice.

**Watch your connection pool size.**
Sequential variants need at most `in-flight` connections to the backend.
Parallel variants need `in-flight × 3`. Undersizing the pool causes queuing that
can exceed your request timeout and trigger an error cascade.
See `WebClientOptions.setMaxPoolSize()` in each server-2 variant.

## Code comparison

The fan-out and fetch code maps one-for-one between the reactive styles.
The timeout/retry implementation is where the three coding styles look most different from each other.

### Parallel fan-out

```java
// RxJava3 — intent is explicit, combinator function states the result directly
Single.zip(fetchValue(), fetchValue(), fetchValue(),
    (v1, v2, v3) -> v1 + v2 + v3)

// Futures — must capture futures first, then extract results after the combinator
Future<Integer> f1 = fetchValue(), f2 = fetchValue(), f3 = fetchValue();
CompositeFuture.all(f1, f2, f3)
    .map(cf -> f1.result() + f2.result() + f3.result())

// Virtual threads — submit three tasks, then block (parks the virtual thread,
// not a platform thread) until all complete
var f1 = CompletableFuture.supplyAsync(this::fetchValue, vtExecutor);
var f2 = CompletableFuture.supplyAsync(this::fetchValue, vtExecutor);
var f3 = CompletableFuture.supplyAsync(this::fetchValue, vtExecutor);
int sum = f1.get() + f2.get() + f3.get();
```

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
// Virtual threads — a for loop and a Duration; no helpers needed
private int fetchValueRT() throws Exception {
    Exception last = null;
    for (int attempts = MAX_RETRIES + 1; attempts > 0; attempts--) {
        try { return fetchValue(); }
        catch (Exception e) { last = e; }
    }
    throw last;
}
// timeout lives on the request:
//   HttpRequest.newBuilder().timeout(Duration.ofMillis(TIMEOUT_MS))...

// RxJava3 — declarative: operators compose directly
private Single<Integer> fetchValueRT() {
    return fetchValue()
        .timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS, scheduler)
        .retry(MAX_RETRIES);
}

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

Virtual threads win on simplicity: retry is a `for` loop, timeout is a `Duration`
on the request — no helpers, no operators, no extra parameters. RxJava3 is next
with 3 declarative operator lines. Plain Futures requires the most code: a 12-line
`withTimeout` helper plus a recursive `retriesLeft` parameter.

For the reactive styles, `compose`/`flatMap` and `Future`/`Single` swap
one-for-one in the fan-out chain — those parts are structurally identical.

## Why Vert.x + virtual threads?

Vert.x is still a reasonable — and arguably good — host for the virtual thread
implementations. It handles what it does best: the HTTP server, connection
management, routing, and the event loop that accepts and dispatches requests.
Virtual threads only take over for the blocking I/O inside the handler. The two
layers don't conflict; they each handle what they are best at.

The alternative would be an embedded Jetty or Spring Boot app with virtual threads.
Those work, but you trade Vert.x's lean HTTP layer for a heavier container without
gaining anything for the network side. Vert.x's HTTP server is one of the fastest
on the JVM — there is no reason to replace it just because the handler code is now
blocking.

The practical advantages Vert.x is known for — small fat-jar, low memory footprint,
fast startup, quick build iterations — are unaffected by virtual threads. The VT
servers here add zero new dependencies (`java.net.http` and `java.util.concurrent`
are part of the JDK), the jar stays the same size, and startup time is unchanged.

## Stack

- Vert.x 4.5.10
- vertx-web, vertx-web-client, vertx-rx-java3
- Java 21, Maven
