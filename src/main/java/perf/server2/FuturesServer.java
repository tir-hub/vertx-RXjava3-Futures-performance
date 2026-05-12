package perf.server2;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * Server-2 using plain Vert.x Futures.
 *
 * Three sequential GET /value calls to server-3 are chained with Future.compose().
 * Each step carries the running sum forward; the final handler writes the response.
 *
 * Compare this file line-for-line with RxServer — the structure is nearly identical,
 * which is the point: RxJava3 maps cleanly onto what Vert.x Futures already express.
 */
public class FuturesServer extends AbstractVerticle {

    static final int PORT        = 8082;
    static final int BACKEND_PORT = 8083;

    private WebClient client;

    @Override
    public void start() {
        // Pool must cover peak in-flight from the load generator — one connection per
        // concurrent request since the three backend calls are sequential, not parallel.
        client = WebClient.create(vertx,
            new WebClientOptions().setMaxPoolSize(500));

        Router router = Router.router(vertx);

        router.get("/sum").handler(ctx ->
            fetchValue()
                .compose(v1 -> fetchValue().map(v2 -> v1 + v2))
                .compose(sum12 -> fetchValue().map(v3 -> sum12 + v3))
                .onSuccess(total ->
                    ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("sum", total).encode()))
                .onFailure(ctx::fail)
        );

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
            .deployVerticle(FuturesServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("Futures server (server-2) listening on port %d  [%d instance(s)]%n",
                    PORT, instances));
    }
}
