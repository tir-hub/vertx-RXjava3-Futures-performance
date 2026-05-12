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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-2: sequential fan-out using Java 21 virtual threads.
 *
 * Each request is handed off to a virtual thread where three blocking HTTP calls
 * run as ordinary sequential method calls — no Future, no flatMap, no callbacks.
 * Virtual threads park (without blocking a platform thread) during each I/O wait,
 * so throughput scales without a large platform thread pool.
 *
 * The Vert.x event loop still owns the HTTP server and accepts connections.
 * When the blocking work is done, runOnContext() marshals the response write
 * back onto the event loop to keep Vert.x's threading contract.
 *
 * Compare handleSum/fetchValue with FuturesServer and RxServer to see how the
 * same logic reads in all three styles.
 */
public class VirtualThreadServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private ExecutorService vtExecutor;
    private HttpClient httpClient;

    @Override
    public void start() {
        vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // Force HTTP/1.1 — the default HttpClient negotiates HTTP/2 with Vert.x and
        // hits MAX_CONCURRENT_STREAMS limits under high concurrency.
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
                int sum = fetchValue() + fetchValue() + fetchValue();
                vtxCtx.runOnContext(v ->
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("sum", sum).encode()));
            } catch (Exception e) {
                vtxCtx.runOnContext(v -> ctx.fail(e));
            }
        });
    }

    private int fetchValue() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + BACKEND_PORT + "/value"))
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
            .deployVerticle(VirtualThreadServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("VirtualThread server (server-2) listening on port %d  [%d instance(s)]%n",
                    PORT, instances));
    }
}
