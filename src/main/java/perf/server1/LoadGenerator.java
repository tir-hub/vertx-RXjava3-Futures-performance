package perf.server1;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Closed-loop load generator for server-2 (port 8082).
 *
 * Each worker verticle keeps CONCURRENCY_PER_VERTICLE requests in flight at all times.
 * As soon as a response arrives, the next request fires — so total in-flight is always
 * (instances × CONCURRENCY_PER_VERTICLE). This is the "closed-loop" / "constant load"
 * model, which is the right baseline for comparing two server implementations.
 *
 * Stats are printed every REPORT_INTERVAL_SECONDS showing:
 *   rps, avg latency, p50/p95/p99 latency (in microseconds), error count.
 *
 * Usage:
 *   mvn exec:java -Pload                                          # 4 verticles × 10 concurrency (no delay)
 *   mvn exec:java -Pload -Dexec.args="8"                         # 8 verticles × 10 concurrency
 *   mvn exec:java -Pload -Dload.concurrency=100                   # 4 verticles × 100 concurrency (250ms delay)
 *   mvn exec:java -Pload -Dexec.args="4" -Dload.concurrency=100  # explicit both
 */
public class LoadGenerator extends AbstractVerticle {

    private static final int TARGET_PORT            = 8082;
    private static final int REPORT_INTERVAL_SECONDS = 5;

    // Read once at class-load time so all verticle instances share the same value
    private static final int CONCURRENCY_PER_VERTICLE =
        Integer.getInteger("load.concurrency", 10);

    private static final ConcurrentLinkedQueue<Long> latenciesUs = new ConcurrentLinkedQueue<>();
    private static final LongAdder successCount = new LongAdder();
    private static final LongAdder errorCount   = new LongAdder();

    private WebClient client;

    @Override
    public void start() {
        client = WebClient.create(vertx,
            new WebClientOptions().setMaxPoolSize(CONCURRENCY_PER_VERTICLE + 4));
        for (int i = 0; i < CONCURRENCY_PER_VERTICLE; i++) {
            sendNext();
        }
    }

    private void sendNext() {
        long startNs = System.nanoTime();
        client.get(TARGET_PORT, "localhost", "/sum")
            .timeout(10_000)
            .send()
            .onComplete(ar -> {
                if (ar.succeeded() && ar.result().statusCode() == 200) {
                    latenciesUs.add((System.nanoTime() - startNs) / 1_000);
                    successCount.increment();
                } else {
                    errorCount.increment();
                }
                sendNext();
            });
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 4;

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(LoadGenerator::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id -> System.out.printf(
                "Load generator started — %d verticles × %d concurrency = %d in-flight%n"
                + "Target: http://localhost:%d/sum%n"
                + "Reporting every %ds  (latency in µs)%n%n",
                instances, CONCURRENCY_PER_VERTICLE, instances * CONCURRENCY_PER_VERTICLE,
                TARGET_PORT, REPORT_INTERVAL_SECONDS));

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-reporter");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(LoadGenerator::printStats,
            REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void printStats() {
        List<Long> window = new ArrayList<>(latenciesUs.size() + 16);
        Long sample;
        while ((sample = latenciesUs.poll()) != null) window.add(sample);

        long errors = errorCount.sumThenReset();

        if (window.isEmpty()) {
            System.out.printf("  [no responses — errors=%d — is server-2 running on port %d?]%n",
                errors, TARGET_PORT);
            return;
        }

        Collections.sort(window);
        int n = window.size();
        double rps  = n / (double) REPORT_INTERVAL_SECONDS;
        long   avg  = window.stream().mapToLong(v -> v).sum() / n;
        long   p50  = window.get((int) (n * 0.50));
        long   p95  = window.get((int) (n * 0.95));
        long   p99  = window.get(Math.min(n - 1, (int) (n * 0.99)));

        System.out.printf("  rps=%-8.0f | µs: avg=%-6d  p50=%-6d  p95=%-6d  p99=%-6d | errors=%d%n",
            rps, avg, p50, p95, p99, errors);
    }
}
