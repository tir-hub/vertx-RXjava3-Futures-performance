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
 * Server-2 using RxJava 3.
 *
 * The Vert.x WebClient is wrapped in its RxJava3 delegate so that send() becomes
 * rxSend(), returning Single<HttpResponse<Buffer>> instead of Future<...>.
 * The three sequential calls are then chained with Single.flatMap() — exactly
 * mirroring the Future.compose() chain in FuturesServer.
 *
 * Note: we stay on the standard Vert.x AbstractVerticle and Router to keep the
 * routing layer identical between the two servers. Only the async HTTP-client
 * calls are expressed in RxJava3 terms.
 */
public class RxServer extends AbstractVerticle {

    static final int PORT         = 8082;
    static final int BACKEND_PORT = 8083;

    private WebClient rxClient;

    @Override
    public void start() {
        // Wrap the core Vertx once; the Rx WebClient delegates to the same event loop.
        io.vertx.rxjava3.core.Vertx rxVertx = io.vertx.rxjava3.core.Vertx.newInstance(vertx);
        // Pool must cover peak in-flight from the load generator — one connection per
        // concurrent request since the three backend calls are sequential, not parallel.
        rxClient = WebClient.create(rxVertx,
            new WebClientOptions().setMaxPoolSize(500));

        Router router = Router.router(vertx);
        router.get("/sum").handler(this::handleSum);

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private void handleSum(RoutingContext ctx) {
        fetchValue()
            .flatMap(v1 -> fetchValue().map(v2 -> v1 + v2))
            .flatMap(sum12 -> fetchValue().map(v3 -> sum12 + v3))
            .subscribe(
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
            .deployVerticle(RxServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("RxJava3 server (server-2) listening on port %d  [%d instance(s)]%n",
                    PORT, instances));
    }
}
