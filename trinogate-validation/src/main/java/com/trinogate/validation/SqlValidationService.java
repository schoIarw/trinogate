package com.trinogate.validation;

import com.trinogate.config.ValidationConfig;
import com.trinogate.validation.rules.RuleManager;
import com.trinogate.validation.rules.RuleResult;
import com.trinogate.validation.rules.SqlValidationContext;
import com.trinogate.validation.sql.AstFeatures;
import com.trinogate.validation.sql.AstFeaturesExtractor;
import com.trinogate.validation.sql.SqlParserService;
import com.trinogate.validation.sql.StatementClassifier;
import io.trino.sql.tree.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Facade used by the query pipeline: parse → classify → extract AST features →
 * evaluate rules → outcome. Rejections are returned as a message the server turns
 * into {@code QueryResults.error} (HTTP 200), so clients see a clean SQL error.
 */
public class SqlValidationService {

    private static final Logger log = LoggerFactory.getLogger(SqlValidationService.class);

    private final SqlParserService parser = new SqlParserService();
    private final StatementClassifier classifier = new StatementClassifier();
    private final AstFeaturesExtractor extractor;
    private final RuleManager ruleManager;
    private final ValidationConfig config;

    public SqlValidationService(ValidationConfig config) {
        this.config = config;
        this.extractor = new AstFeaturesExtractor(classifier);
        this.ruleManager = new RuleManager(config);
    }

    public void start() {
        ruleManager.start();
    }

    public void close() {
        ruleManager.close();
    }

    /** @return empty when the SQL is allowed, otherwise the rejection message */
    public Optional<String> validate(String sql, String user, String source) {
        Optional<Statement> parsed = parser.parse(sql);
        if (parsed.isEmpty()) {
            // Unparsable SQL is passed through so the backend reports the real syntax error.
            return Optional.empty();
        }
        AstFeatures features = extractor.extract(parsed.get());

        if (config.isAllowMetadataStatements() && features.statementKind().name().equals("METADATA")) {
            return Optional.empty();
        }

        SqlValidationContext context = new SqlValidationContext(sql, features, user, source, null, null);
        List<RuleResult> results = ruleManager.evaluate(context);
        for (RuleResult r : results) {
            switch (r.action()) {
                case REJECT -> {
                    log.info("Validation REJECT user={} rule={} sql={}", user, r.ruleId(), abbreviate(sql));
                    return Optional.of(r.message());
                }
                case WARN -> log.warn("Validation WARN user={} rule={}: {}", user, r.ruleId(), r.message());
                default -> { }
            }
        }
        return Optional.empty();
    }

    public RuleManager ruleManager() {
        return ruleManager;
    }

    private static String abbreviate(String sql) {
        String s = sql.replace('\n', ' ').trim();
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
