package com.trinogate.validation.rules;

import com.trinogate.validation.sql.StatementKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Adapts a {@link RuleDefinition} (loaded from YAML or created via the admin API)
 * into an executable {@link SqlRule}.
 */
public class DynamicRule implements SqlRule {

    private final RuleDefinition definition;
    private final List<StatementKind> kinds;
    private final Set<String> exceptUsers;
    private final RuleAction action;

    public DynamicRule(RuleDefinition definition) {
        this.definition = definition;
        this.action = RuleAction.valueOf(definition.action.toUpperCase(Locale.ROOT));
        this.kinds = new ArrayList<>();
        for (String k : definition.appliesTo) {
            StatementKind kind = StatementKind.parse(k);
            if (kind != StatementKind.OTHER) {
                kinds.add(kind);
            }
        }
        this.exceptUsers = new HashSet<>(definition.exceptUsers);
    }

    @Override
    public String id() { return definition.id; }

    @Override
    public String name() { return definition.name; }

    @Override
    public int version() { return definition.version; }

    @Override
    public boolean enabled() { return definition.enabled; }

    @Override
    public RuleAction action() { return action; }

    @Override
    public List<StatementKind> appliesTo() { return kinds; }

    @Override
    public Set<String> exceptUsers() { return exceptUsers; }

    @Override
    public Optional<RuleResult> evaluate(SqlValidationContext ctx) {
        RuleDefinition.Condition c = definition.condition;
        String matched = null;

        if (c.forbiddenTables != null && !c.forbiddenTables.isEmpty()) {
            for (String table : c.forbiddenTables) {
                if (ctx.features().tables().contains(table)) {
                    matched = table;
                    break;
                }
            }
            if (matched == null) {
                return Optional.empty();
            }
        } else if (c.statementKinds != null && !c.statementKinds.isEmpty()) {
            if (!c.statementKinds.contains(ctx.features().statementKind().name())) {
                return Optional.empty();
            }
        } else if (c.sqlContains != null && !c.sqlContains.isEmpty()) {
            if (!ctx.sql().toLowerCase(Locale.ROOT).contains(c.sqlContains.toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
        } else {
            return Optional.empty(); // condition-less rules never match
        }

        String message = definition.message;
        if (matched != null && message.contains("%s")) {
            message = message.formatted(matched);
        }
        if (message.isBlank()) {
            message = "SQL 被规则拒绝（rule: " + id() + "）";
        }
        return Optional.of(new RuleResult(id(), action, message));
    }

    @Override
    public String toString() {
        return "DynamicRule{id='" + id() + "', action=" + action + ", enabled=" + enabled() + '}';
    }
}
