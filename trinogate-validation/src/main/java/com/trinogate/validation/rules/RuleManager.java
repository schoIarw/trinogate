package com.trinogate.validation.rules;

import com.trinogate.config.ValidationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the composed rule set: built-in rules + YAML file rules (hot reload) +
 * admin-managed rules. Exposes the operations used by the admin API.
 */
public class RuleManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleManager.class);

    private final RuleEngine engine = new RuleEngine();
    private final ValidationConfig config;
    private final YamlRuleLoader fileLoader;
    private final InMemoryRuleStore adminStore = new InMemoryRuleStore();
    private final ScheduledExecutorService reloader;

    public RuleManager(ValidationConfig config) {
        this.config = config;
        this.fileLoader = new YamlRuleLoader(config.getRulesPath());
        this.reloader = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rule-reloader");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        reload();
        int seconds = Math.max(1, config.getRulesPollSeconds());
        reloader.scheduleAtFixedRate(this::reload, seconds, seconds, TimeUnit.SECONDS);
        log.info("Rule hot reload started: poll {}s, dir {}", seconds, config.getRulesPath());
    }

    /** Rebuilds the active rule set from all sources and swaps it atomically. */
    public synchronized void reload() {
        List<SqlRule> rules = new ArrayList<>();
        rules.addAll(BuiltinRules.create(config));
        rules.addAll(fileLoader.load());
        rules.addAll(adminStore.toRules());
        engine.replaceRules(rules);
    }

    public List<RuleResult> evaluate(SqlValidationContext context) {
        return engine.evaluate(context);
    }

    public List<SqlRule> activeRules() {
        return engine.activeRules();
    }

    // ---- admin API ----

    public void addRule(RuleDefinition definition) {
        if (definition.id == null || definition.id.isBlank()) {
            throw new IllegalArgumentException("rule id is required");
        }
        adminStore.add(definition);
        reload();
        log.info("Admin added rule {}", definition.id);
    }

    public boolean removeRule(String id) {
        boolean removed = adminStore.remove(id);
        if (removed) {
            reload();
            log.info("Admin removed rule {}", id);
        }
        return removed;
    }

    public List<RuleDefinition> adminRules() {
        return adminStore.definitions();
    }

    @Override
    public void close() {
        reloader.shutdownNow();
    }
}
