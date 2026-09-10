package com.trinogate.validation.rules;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for rules managed through the admin API. Rules added here
 * are merged with file-loaded rules on every {@link RuleManager#reload()}.
 */
public class InMemoryRuleStore {

    private final Map<String, RuleDefinition> rules = new ConcurrentHashMap<>();

    public void add(RuleDefinition definition) {
        rules.put(definition.id, definition);
    }

    public boolean remove(String id) {
        return rules.remove(id) != null;
    }

    public List<SqlRule> toRules() {
        return rules.values().stream().map(DynamicRule::new).collect(java.util.stream.Collectors.toList());
    }

    public List<RuleDefinition> definitions() {
        return List.copyOf(rules.values());
    }
}
