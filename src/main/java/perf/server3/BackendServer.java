package perf.server3;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.concurrent.ThreadLocalRandom;

public class BackendServer extends AbstractVerticle {

    static final int PORT = 8083;

    // Override at startup: -Dbackend.delayMs=50
    private static final long DELAY_MS = Long.getLong("backend.delayMs", 0);
    // Fraction of requests that return HTTP 500. Use to exercise retry logic: -Dbackend.failRate=0.2
    private static final double FAIL_RATE = Double.parseDouble(System.getProperty("backend.failRate", "0"));

    @Override
    public void start() {
        Router router = Router.router(vertx);

        router.get("/value").handler(ctx -> {
            if (FAIL_RATE > 0 && ThreadLocalRandom.current().nextDouble() < FAIL_RATE) {
                ctx.response().setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "simulated failure").encode());
                return;
            }
            if (DELAY_MS <= 0) {
                respond(ctx);
            } else {
                vertx.setTimer(DELAY_MS, id -> respond(ctx));
            }
        });

        vertx.createHttpServer().requestHandler(router).listen(PORT);
    }

    private void respond(io.vertx.ext.web.RoutingContext ctx) {
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(new JsonObject()
                .put("value", ThreadLocalRandom.current().nextInt(1, 101))
                .encode());
    }

    public static void main(String[] args) {
        int instances = Runtime.getRuntime().availableProcessors();
        Vertx.vertx()
            .deployVerticle(BackendServer::new, new DeploymentOptions().setInstances(instances))
            .onSuccess(id ->
                System.out.printf("Backend server (server-3) listening on port %d  [%d instances, delay=%dms]%n",
                    PORT, instances, DELAY_MS));
    }
}
