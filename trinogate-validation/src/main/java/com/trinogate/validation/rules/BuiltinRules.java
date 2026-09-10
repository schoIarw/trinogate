package com.trinogate.validation.rules;

import com.trinogate.config.ValidationConfig;
import com.trinogate.validation.sql.StatementKind;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Built-in rules, always present and independent of dynamic rule files:
 * <ul>
 *   <li>{@code select-require-where-or-limit} — SELECT statements must contain
 *       WHERE or LIMIT (the core requirement; action from {@code validation.policy})</li>
 *   <li>{@code select-star-without-limit} — WARN on SELECT * without LIMIT</li>
 * </ul>
 */
public final class BuiltinRules {

    private BuiltinRules() {}

    public static List<SqlRule> create(ValidationConfig config) {
        RuleAction guardAction = "WARN".equalsIgnoreCase(config.getPolicy()) ? RuleAction.WARN : RuleAction.REJECT;
        return List.of(
                new SelectWhereOrLimitRule(config, guardAction),
                new SelectStarWithoutLimitRule());
    }

    static final class SelectWhereOrLimitRule implements SqlRule {
        private final ValidationConfig config;
        private final RuleAction action;

        SelectWhereOrLimitRule(ValidationConfig config, RuleAction action) {
            this.config = config;
            this.action = action;
        }

        @Override
        public String id() { return "select-require-where-or-limit"; }

        @Override
        public String name() { return "SELECT 必须包含 WHERE 或 LIMIT"; }

        @Override
        public int version() { return 1; }

        @Override
        public boolean enabled() { return config.isSelectRequireWhereOrLimit(); }

        @Override
        public RuleAction action() { return action; }

        @Override
        public List<StatementKind> appliesTo() { return List.of(StatementKind.QUERY); }

        @Override
        public Set<String> exceptUsers() { return Set.of(); }

        @Override
        public Optional<RuleResult> evaluate(SqlValidationContext ctx) {
            boolean violated = config.isIncludeSubqueries()
                    ? ctx.features().querySpecsWithoutGuard() > 0
                    : !ctx.features().topLevelGuarded();
            if (!violated) {
                return Optional.empty();
            }
            if (config.isExemptNoFrom() && !ctx.features().fromPresent()) {
                return Optional.empty();
            }
            return Optional.of(new RuleResult(id(), action(),
                    "查询必须包含 WHERE 或 LIMIT 条件，禁止全表扫描（rule: " + id() + "）"));
        }
    }

    static final class SelectStarWithoutLimitRule implements SqlRule {
        @Override
        public String id() { return "select-star-without-limit"; }

        @Override
        public String name() { return "禁止 SELECT * 且无 LIMIT"; }

        @Override
        public int version() { return 1; }

        @Override
        public boolean enabled() { return true; }

        @Override
        public RuleAction action() { return RuleAction.WARN; }

        @Override
        public List<StatementKind> appliesTo() { return List.of(StatementKind.QUERY); }

        @Override
        public Set<String> exceptUsers() { return Set.of(); }

        @Override
        public Optional<RuleResult> evaluate(SqlValidationContext ctx) {
            if (ctx.features().selectStar() && !ctx.features().topLevelGuarded() && ctx.features().fromPresent()) {
                return Optional.of(new RuleResult(id(), RuleAction.WARN,
                        "SELECT * 建议显式列出列并加 LIMIT（rule: " + id() + "）"));
            }
            return Optional.empty();
        }
    }
}
