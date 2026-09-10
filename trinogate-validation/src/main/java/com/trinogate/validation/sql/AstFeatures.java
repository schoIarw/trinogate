package com.trinogate.validation.sql;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AST facts used by validation rules: statement kind, WHERE/LIMIT guard status,
 * referenced tables, and SELECT * usage.
 */
public class AstFeatures {

    private final StatementKind statementKind;
    private final boolean fromPresent;
    private final boolean topLevelGuarded;
    private final int querySpecsWithoutGuard;
    private final Set<String> tables;
    private final boolean selectStar;

    AstFeatures(StatementKind statementKind, boolean fromPresent, boolean topLevelGuarded,
                int querySpecsWithoutGuard, Set<String> tables, boolean selectStar) {
        this.statementKind = statementKind;
        this.fromPresent = fromPresent;
        this.topLevelGuarded = topLevelGuarded;
        this.querySpecsWithoutGuard = querySpecsWithoutGuard;
        this.tables = Collections.unmodifiableSet(new LinkedHashSet<>(tables));
        this.selectStar = selectStar;
    }

    public StatementKind statementKind() { return statementKind; }
    public boolean fromPresent() { return fromPresent; }
    public boolean topLevelGuarded() { return topLevelGuarded; }
    public int querySpecsWithoutGuard() { return querySpecsWithoutGuard; }
    public Set<String> tables() { return tables; }
    public boolean selectStar() { return selectStar; }

    @Override
    public String toString() {
        return "AstFeatures{kind=" + statementKind
                + ", fromPresent=" + fromPresent
                + ", topLevelGuarded=" + topLevelGuarded
                + ", unguarded=" + querySpecsWithoutGuard
                + ", tables=" + tables
                + ", selectStar=" + selectStar + '}';
    }
}
