package com.trinogate.validation.rules;

import com.trinogate.validation.sql.StatementKind;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A single validation rule. Implementations may be built-in, YAML-loaded, or scripted. */
public interface SqlRule {

    String id();

    String name();

    int version();

    boolean enabled();

    /** Action applied when the rule matches. */
    RuleAction action();

    /** Statement kinds this rule applies to; empty = all kinds. */
    List<StatementKind> appliesTo();

    /** Users exempted from this rule. */
    Set<String> exceptUsers();

    /** @return a result when the rule matches, otherwise empty */
    Optional<RuleResult> evaluate(SqlValidationContext context);
}
