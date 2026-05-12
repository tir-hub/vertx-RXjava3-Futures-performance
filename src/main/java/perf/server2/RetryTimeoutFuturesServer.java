package perf.server2;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * Server-2: sequential fan-out with per-call timeout and retry using plain Vert.x Futures.
 *
 * withTimeout() races a Future against a vertx timer via a shared Promise.
 * fetchValueRT() wraps fetchValue() with that timeout then uses .recover() to
 * retry recursively, decrementing retriesLeft on each attempt.
 *
 * Compare line-for-line with RetryTimeoutRxServer to see the difference in
 * expressing the same semantics with RxJava3 operators.
 */
public class RetryTimeoutFuturesServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private static final long TIMEOUT_MS  = 500;
    private static final int  MAX_RETRIES = 2;

    private WebClient client;

    @Override
    public void start() {
        client = WebClient.create(vertx,
            new WebClientOptions().setMaxPoolSize(500));

        Router router = Router.router(vertx);

        router.get("/sum").handler(ctx ->
            fetchValueRT(MAX_RETRIES)
                .compose(v1 -> fetchValueRT(MAX_RETRIES).map(v2 -> v1 + v2))
                .compose(sum12 -> fetchValueRT(MAX_RETRIES).map(v3 -> sum12 + v3))
                .onSuccess(total ->
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("sum", total).encode()))
                .onFailure(ctx::fail)
        );

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private Future<Integer> fetchValueRT(int retriesLeft) {
        return withTimeout(fetchValue(), TIMEOUT_MS)
            .recover(e -> retriesLeft > 0
                ? fetchValueRT(retriesLeft - 1)
                : Future.failedFuture(e));
    }

    // Races `future` against a vertx timer; whoever fires first wins via tryComplete/tryFail.
    private <T> Future<T> withTimeout(Future<T> future, long ms) {
        Promise<T> promise = Promise.promise();
        long timerId = vertx.setTimer(ms, id -> promise.tryFail("timeout after " + ms + "ms"));
        future.onComplete(ar -> {
            vertx.cancelTimer(timerId);
            if (ar.succeeded()) {
                promise.tryComplete(ar.result());
            } else {
                promise.tryFail(ar.cause());
            }
        });
        return promise.future();
    }

    private Future<Integer> fetchValue() {
        return client.get(BACKEND_PORT, "localhost", "/value")
            .send()
            .compose(resp -> resp.statusCode() == 200
                ? Future.succeededFuture(resp.bodyAsJsonObject().getInteger("value"))
                : Future.failedFuture("HTTP " + resp.statusCode()));
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Vertx.vertx()
            .deployVerticle(RetryTimeoutFuturesServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("RetryTimeout-Futures server (server-2) listening on port %d  [%d instance(s), timeout=%dms, maxRetries=%d]%n",
                    PORT, instances, TIMEOUT_MS, MAX_RETRIES));
    }
}
