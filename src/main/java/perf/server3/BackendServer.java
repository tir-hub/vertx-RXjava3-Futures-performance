package perf.server3;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.concurrent.ThreadLocalRandom;

public class BackendServer extends AbstractVerticle {

    static final int PORT = 8083;

    // Non-zero simulates a real downstream (DB, external API). Zero = raw overhead only.
    // Override at startup: -Dbackend.delayMs=250
    private static final long DELAY_MS = Long.getLong("backend.delayMs", 0);

    @Override
    public void start() {
        Router router = Router.router(vertx);

        router.get("/value").handler(ctx -> {
            if (DELAY_MS <= 0) {
                respond(ctx);
            } else {
                // setTimer is non-blocking — the event loop is free during the wait
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
