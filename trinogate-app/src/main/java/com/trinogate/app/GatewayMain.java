package com.trinogate.app;

import com.trinogate.config.ConfigLoader;
import com.trinogate.config.GatewayConfig;
import com.trinogate.server.GatewayApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Config resolution: -Dtrinogate.config=... > $TRINOGATE_CONFIG > ./config.yaml
 * <pre>
 *   java -jar trinogate.jar [config.yaml]
 * </pre>
 */
public final class GatewayMain {

    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);

    private GatewayMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.setProperty("trinogate.config", args[0]);
        }
        GatewayConfig config = ConfigLoader.load();
        GatewayApplication app = GatewayApplication.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        log.info("Trino gateway ready. Listening on port {}; API base {}", 
                app.actualPort(), config.getServer().getGatewayBaseUri());
        Thread.currentThread().join();
    }
}
