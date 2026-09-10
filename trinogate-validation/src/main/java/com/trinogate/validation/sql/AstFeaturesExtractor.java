package com.trinogate.validation.sql;

import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.AllColumns;
import io.trino.sql.tree.DefaultTraversalVisitor;
import io.trino.sql.tree.Prepare;
import io.trino.sql.tree.Query;
import io.trino.sql.tree.QuerySpecification;
import io.trino.sql.tree.Statement;
import io.trino.sql.tree.Table;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Walks the Trino AST and extracts rule-relevant facts.
 * <p>
 * Guard semantics: a SELECT branch is "guarded" when it has a WHERE or a LIMIT
 * (on the {@link QuerySpecification} or the wrapping {@link Query}). The extractor
 * always counts unguarded query specifications; the rule decides whether nested
 * (subquery / CTE) branches count, driven by config.
 * <p>
 * Note: Trino's {@code AstVisitor} does not recurse into children on its own;
 * this extractor extends {@link DefaultTraversalVisitor} which performs a full
 * tree walk.
 */
public class AstFeaturesExtractor {

    private final StatementClassifier classifier;

    public AstFeaturesExtractor(StatementClassifier classifier) {
        this.classifier = classifier;
    }

    public AstFeatures extract(String sql) {
        return extract(new SqlParser().createStatement(sql));
    }

    public AstFeatures extract(Statement statement) {
        Statement effective = statement;
        StatementKind kind = classifier.classify(effective);
        if (effective instanceof Prepare prepare) {
            effective = prepare.getStatement();
            kind = classifier.classify(effective);
        }
        Extractor visitor = new Extractor();
        effective.accept(visitor, null);
        return new AstFeatures(kind, visitor.fromPresent, visitor.topLevelGuarded,
                visitor.querySpecsWithoutGuard, visitor.tables, visitor.selectStar);
    }

    private final class Extractor extends DefaultTraversalVisitor<Void> {

        private boolean topLevelQuery = true;
        private boolean topLevelSpec = true;
        private boolean topLevelQueryLimit;
        private boolean fromPresent;
        private boolean topLevelGuarded;
        private int querySpecsWithoutGuard;
        private boolean selectStar;
        private final Set<String> tables = new LinkedHashSet<>();

        @Override
        protected Void visitQuery(Query node, Void context) {
            if (topLevelQuery) {
                topLevelQuery = false;
                // ORDER BY ... LIMIT places the limit on Query instead of QuerySpecification
                topLevelQueryLimit = node.getLimit().isPresent();
            }
            return super.visitQuery(node, context);
        }

        @Override
        protected Void visitQuerySpecification(QuerySpecification node, Void context) {
            boolean topLevel = topLevelSpec;
            topLevelSpec = false;
            boolean hasWhere = node.getWhere().isPresent();
            boolean hasLimit = node.getLimit().isPresent();
            boolean guarded = hasWhere || hasLimit;

            if (node.getFrom().isPresent()) {
                fromPresent = true;
            }
            if (topLevel) {
                topLevelGuarded = guarded || topLevelQueryLimit;
            }
            if (!guarded) {
                querySpecsWithoutGuard++;
            }
            return super.visitQuerySpecification(node, context);
        }

        @Override
        protected Void visitTable(Table node, Void context) {
            tables.add(String.join(".", node.getName().getParts()));
            return super.visitTable(node, context);
        }

        @Override
        protected Void visitAllColumns(AllColumns node, Void context) {
            selectStar = true;
            return super.visitAllColumns(node, context);
        }
    }
}
