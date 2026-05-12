package perf.server2;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * Server-2 parallel variant using plain Vert.x Futures.
 *
 * All three backend calls are fired simultaneously with CompositeFuture.all().
 * The handler only runs when every future has resolved, then sums the results.
 * Compare with FuturesServer to see the sequential vs parallel trade-off.
 */
public class ParallelFuturesServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private WebClient client;

    @Override
    public void start() {
        // Parallel variant fires 3 calls simultaneously per request, so peak connections =
        // in-flight × 3. Size the pool to cover that without queuing.
        client = WebClient.create(vertx,
            new WebClientOptions().setMaxPoolSize(1500));

        Router router = Router.router(vertx);

        router.get("/sum").handler(ctx -> {
            Future<Integer> f1 = fetchValue();
            Future<Integer> f2 = fetchValue();
            Future<Integer> f3 = fetchValue();

            CompositeFuture.all(f1, f2, f3)
                .onSuccess(cf ->
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                            .put("sum", f1.result() + f2.result() + f3.result())
                            .encode()))
                .onFailure(ctx::fail);
        });

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private Future<Integer> fetchValue() {
        return client.get(BACKEND_PORT, "localhost", "/value")
            .send()
            .map(resp -> resp.bodyAsJsonObject().getInteger("value"));
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Vertx.vertx()
            .deployVerticle(ParallelFuturesServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("Parallel-Futures server (server-2) listening on port %d  [%d instance(s)]%n",
                    PORT, instances));
    }
}
