package perf.server2;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-2: sequential fan-out with per-call timeout and retry using Java 21 virtual threads.
 *
 * Retry is a plain for-loop counting down attempts. Timeout is a per-request
 * Duration on HttpRequest — the JDK throws HttpTimeoutException when it fires,
 * which the catch block treats as any other retriable error.
 *
 * Compare fetchValueRT() here with the same method in RetryTimeoutFuturesServer
 * (12-line withTimeout helper + recursive retriesLeft parameter) and
 * RetryTimeoutRxServer (.timeout(scheduler).retry(n)) to see how the three
 * styles express identical semantics.
 */
public class VirtualThreadRetryServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private static final long TIMEOUT_MS  = 500;
    private static final int  MAX_RETRIES = 2;

    private ExecutorService vtExecutor;
    private HttpClient httpClient;

    @Override
    public void start() {
        vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

        Router router = Router.router(vertx);
        router.get("/sum").handler(this::handleSum);

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private void handleSum(RoutingContext ctx) {
        Context vtxCtx = vertx.getOrCreateContext();
        vtExecutor.submit(() -> {
            try {
                int sum = fetchValueRT() + fetchValueRT() + fetchValueRT();
                vtxCtx.runOnContext(v ->
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("sum", sum).encode()));
            } catch (Exception e) {
                vtxCtx.runOnContext(v -> ctx.fail(e));
            }
        });
    }

    private int fetchValueRT() throws Exception {
        Exception last = null;
        for (int attempts = MAX_RETRIES + 1; attempts > 0; attempts--) {
            try {
                return fetchValue();
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    private int fetchValue() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + BACKEND_PORT + "/value"))
            .timeout(Duration.ofMillis(TIMEOUT_MS))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        return new JsonObject(response.body()).getInteger("value");
    }

    @Override
    public void stop() {
        vtExecutor.shutdown();
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Vertx.vertx()
            .deployVerticle(VirtualThreadRetryServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("VirtualThreadRetry server (server-2) listening on port %d  [%d instance(s), timeout=%dms, maxRetries=%d]%n",
                    PORT, instances, TIMEOUT_MS, MAX_RETRIES));
    }
}
