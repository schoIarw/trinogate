package com.trinogate.validation.sql;

import io.trino.sql.tree.Call;
import io.trino.sql.tree.CreateTableAsSelect;
import io.trino.sql.tree.Deallocate;
import io.trino.sql.tree.Delete;
import io.trino.sql.tree.Execute;
import io.trino.sql.tree.Explain;
import io.trino.sql.tree.Insert;
import io.trino.sql.tree.Merge;
import io.trino.sql.tree.Prepare;
import io.trino.sql.tree.Query;
import io.trino.sql.tree.ResetSession;
import io.trino.sql.tree.SetSession;
import io.trino.sql.tree.Statement;
import io.trino.sql.tree.Update;
import io.trino.sql.tree.Use;

/**
 * Classifies a parsed statement. Metadata statements (SHOW/DESCRIBE/USE/SET SESSION/
 * EXPLAIN) are detected by class name prefix so new statement types stay safe by default.
 */
public final class StatementClassifier {

    public StatementKind classify(Statement statement) {
        if (statement instanceof Query) {
            return StatementKind.QUERY;
        }
        if (statement instanceof Insert) {
            return StatementKind.INSERT;
        }
        if (statement instanceof Update) {
            return StatementKind.UPDATE;
        }
        if (statement instanceof Delete) {
            return StatementKind.DELETE;
        }
        if (statement instanceof Merge) {
            return StatementKind.MERGE;
        }
        if (statement instanceof Call) {
            return StatementKind.CALL;
        }
        if (statement instanceof Prepare) {
            return StatementKind.PREPARE;
        }
        if (statement instanceof Execute) {
            return StatementKind.EXECUTE;
        }
        if (statement instanceof Deallocate) {
            return StatementKind.DEALLOCATE;
        }
        if (statement instanceof Explain
                || statement instanceof Use
                || statement instanceof SetSession
                || statement instanceof ResetSession) {
            return StatementKind.METADATA;
        }
        String simpleName = statement.getClass().getSimpleName();
        if (simpleName.startsWith("Show") || simpleName.startsWith("Describe")) {
            return StatementKind.METADATA;
        }
        if (statement instanceof CreateTableAsSelect) {
            return StatementKind.DDL;
        }
        return StatementKind.DDL;
    }
}
