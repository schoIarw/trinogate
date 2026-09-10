package com.trinogate.validation.rules;

/** Result of a single rule evaluation. */
public record RuleResult(String ruleId, RuleAction action, String message) {
}
