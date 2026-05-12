package perf.server2;

import io.reactivex.rxjava3.core.Single;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.RxHelper;
import io.vertx.rxjava3.ext.web.client.WebClient;

import java.util.concurrent.TimeUnit;

/**
 * Server-2: sequential fan-out with per-call timeout and retry using RxJava 3.
 *
 * timeout() races the upstream Single against a Vert.x-scheduler-backed timer so
 * the timeout fires on the event loop rather than a background thread.
 * retry(n) resubscribes the full fetchValue() chain — including a fresh timeout
 * window — up to n times on any error (timeout or HTTP non-200).
 *
 * Compare line-for-line with RetryTimeoutFuturesServer to see how the same
 * semantics map onto RxJava3 operators vs explicit Promise/recover.
 */
public class RetryTimeoutRxServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private static final long TIMEOUT_MS  = 500;
    private static final int  MAX_RETRIES = 2;

    private WebClient rxClient;
    private io.reactivex.rxjava3.core.Scheduler scheduler;

    @Override
    public void start() {
        io.vertx.rxjava3.core.Vertx rxVertx = io.vertx.rxjava3.core.Vertx.newInstance(vertx);
        rxClient = WebClient.create(rxVertx,
            new WebClientOptions().setMaxPoolSize(500));
        // Vert.x-aware scheduler so timeout callbacks run on this verticle's event loop.
        scheduler = RxHelper.scheduler(vertx);

        Router router = Router.router(vertx);
        router.get("/sum").handler(this::handleSum);

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private void handleSum(RoutingContext ctx) {
        fetchValueRT()
            .flatMap(v1 -> fetchValueRT().map(v2 -> v1 + v2))
            .flatMap(sum12 -> fetchValueRT().map(v3 -> sum12 + v3))
            .subscribe(
                total -> ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("sum", total).encode()),
                ctx::fail
            );
    }

    private Single<Integer> fetchValueRT() {
        return fetchValue()
            .timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS, scheduler)
            .retry(MAX_RETRIES);
    }

    private Single<Integer> fetchValue() {
        return rxClient.get(BACKEND_PORT, "localhost", "/value")
            .rxSend()
            .flatMap(resp -> resp.statusCode() == 200
                ? Single.just(resp.bodyAsJsonObject().getInteger("value"))
                : Single.error(new RuntimeException("HTTP " + resp.statusCode())));
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Vertx.vertx()
            .deployVerticle(RetryTimeoutRxServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("RetryTimeout-RxJava3 server (server-2) listening on port %d  [%d instance(s), timeout=%dms, maxRetries=%d]%n",
                    PORT, instances, TIMEOUT_MS, MAX_RETRIES));
    }
}
