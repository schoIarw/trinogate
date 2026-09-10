package com.trinogate.validation.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates the active rule set. Rule lists are swapped atomically on reload,
 * so hot updates never see a half-applied rule set.
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private volatile List<SqlRule> rules = List.of();

    public void replaceRules(List<SqlRule> newRules) {
        List<SqlRule> copy = List.copyOf(new ArrayList<>(newRules));
        this.rules = copy;
        log.info("Rule engine now active with {} rules", copy.size());
    }

    public List<RuleResult> evaluate(SqlValidationContext context) {
        List<RuleResult> results = new ArrayList<>();
        for (SqlRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            if (!rule.appliesTo().isEmpty() && !rule.appliesTo().contains(context.features().statementKind())) {
                continue;
            }
            if (rule.exceptUsers().contains(context.user())) {
                continue;
            }
            Optional<RuleResult> result;
            try {
                result = rule.evaluate(context);
            } catch (Exception e) {
                log.error("Rule {} failed, skipping: {}", rule.id(), e.toString());
                continue;
            }
            result.ifPresent(results::add);
        }
        return results;
    }

    public List<SqlRule> activeRules() {
        return rules;
    }
}
