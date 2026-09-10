package com.trinogate.validation.rules;

import com.trinogate.validation.sql.AstFeatures;

/** Everything a rule may inspect to make a decision. */
public record SqlValidationContext(
        String sql,
        AstFeatures features,
        String user,
        String source,
        String catalog,
        String schema) {
}
