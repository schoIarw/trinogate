package com.trinogate.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gateway query registry: maps a gateway-generated query id to the backend query.
 * A background sweeper cancels queries whose client stopped polling (idle timeout),
 * which prevents backend queries from hanging after a client disconnect.
 */
public class QueryRegistry<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueryRegistry.class);

    private final ConcurrentHashMap<String, RegisteredQuery<T>> queries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final long idleTimeoutMillis;

    public QueryRegistry(long idleTimeoutMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "query-registry-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleAtFixedRate(this::sweep, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Registers a query under a caller-generated gateway id.
     * @return true when registered; false on id collision (caller should retry with a new id)
     */
    public boolean register(String gatewayId, RegisteredQuery<T> query) {
        return queries.putIfAbsent(gatewayId, query) == null;
    }

    public Optional<RegisteredQuery<T>> get(String gatewayId) {
        RegisteredQuery<T> q = queries.get(gatewayId);
        if (q == null) {
            return Optional.empty();
        }
        q.touch();
        return Optional.of(q);
    }

    /** Remove without invoking the expiry callback (normal completion). */
    public Optional<RegisteredQuery<T>> remove(String gatewayId) {
        return Optional.ofNullable(queries.remove(gatewayId));
    }

    public List<RegisteredQuery<T>> all() {
        return new ArrayList<>(queries.values());
    }

    public int size() {
        return queries.size();
    }

    private void sweep() {
        for (RegisteredQuery<T> q : queries.values()) {
            if (q.idleMillis() > idleTimeoutMillis) {
                if (queries.remove(q.getGatewayId(), q)) {
                    try {
                        q.getOnExpire().run();
                    } catch (Exception e) {
                        log.warn("onExpire failed for query {}", q.getGatewayId(), e);
                    }
                    log.warn("Evicted idle gateway query {} (backend {}) after {}s without polling",
                            q.getGatewayId(), q.getBackendQueryId(), idleTimeoutMillis / 1000);
                }
            }
        }
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        for (RegisteredQuery<T> q : queries.values()) {
            try {
                q.getOnExpire().run();
            } catch (Exception ignored) {
            }
        }
        queries.clear();
    }
}
