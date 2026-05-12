package perf.server2;

import io.reactivex.rxjava3.core.Single;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.ext.web.client.WebClient;

/**
 * Server-2 parallel variant using RxJava 3.
 *
 * Single.zip() fires all three backend calls simultaneously and combines their
 * results in one step once all three complete — a natural fit for parallel fan-out.
 * Compare with RxServer (flatMap chain) to see sequential vs parallel,
 * and with ParallelFuturesServer to see Rx zip vs CompositeFuture side by side.
 */
public class ParallelRxServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private WebClient rxClient;

    @Override
    public void start() {
        io.vertx.rxjava3.core.Vertx rxVertx = io.vertx.rxjava3.core.Vertx.newInstance(vertx);
        // Parallel variant fires 3 calls simultaneously per request, so peak connections =
        // in-flight × 3. Size the pool to cover that without queuing.
        rxClient = WebClient.create(rxVertx,
            new WebClientOptions().setMaxPoolSize(1500));

        Router router = Router.router(vertx);
        router.get("/sum").handler(this::handleSum);

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private void handleSum(RoutingContext ctx) {
        Single.zip(fetchValue(), fetchValue(), fetchValue(),
            (v1, v2, v3) -> v1 + v2 + v3
        ).subscribe(
            total -> ctx.response()
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("sum", total).encode()),
            ctx::fail
        );
    }

    private Single<Integer> fetchValue() {
        return rxClient.get(BACKEND_PORT, "localhost", "/value")
            .rxSend()
            .map(resp -> resp.bodyAsJsonObject().getInteger("value"));
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Vertx.vertx()
            .deployVerticle(ParallelRxServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("Parallel-RxJava3 server (server-2) listening on port %d  [%d instance(s)]%n",
                    PORT, instances));
    }
}
