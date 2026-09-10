package com.trinogate.validation.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads rule definitions from YAML files under a rules directory.
 * Every {@code *.yaml} file may contain either a plain list of rule definitions
 * (the recommended form) or a top-level {@code rules: [...]} wrapper. The
 * {@link RuleManager} polls this directory for hot reload.
 */
public class YamlRuleLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlRuleLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path rulesDir;

    public YamlRuleLoader(String rulesPath) {
        this.rulesDir = Path.of(rulesPath);
    }

    public List<SqlRule> load() {
        List<SqlRule> rules = new ArrayList<>();
        if (!Files.isDirectory(rulesDir)) {
            return rules;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDir, "*.yaml")) {
            for (Path file : stream) {
                rules.addAll(loadFile(file));
            }
        } catch (IOException e) {
            log.warn("Failed to scan rules dir {}: {}", rulesDir, e.toString());
        }
        return rules;
    }

    private List<SqlRule> loadFile(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = YAML.readTree(text);
            List<RuleDefinition> definitions;
            if (root != null && root.isArray()) {
                definitions = new ArrayList<>();
                for (JsonNode node : root) {
                    definitions.add(YAML.treeToValue(node, RuleDefinition.class));
                }
            } else if (root != null && root.has("rules")) {
                RulesFile rulesFile = YAML.treeToValue(root, RulesFile.class);
                definitions = rulesFile.rules == null ? List.of() : rulesFile.rules;
            } else {
                log.warn("Ignoring rules file {}: expected a list or a 'rules:' wrapper", file);
                return List.of();
            }
            List<SqlRule> rules = new ArrayList<>();
            for (RuleDefinition def : definitions) {
                if (def.id == null || def.id.isBlank()) {
                    log.warn("Ignoring rule without id in {}", file);
                    continue;
                }
                rules.add(new DynamicRule(def));
            }
            log.info("Loaded {} rules from {}", rules.size(), file);
            return rules;
        } catch (IOException e) {
            log.warn("Failed to parse rules file {}: {}", file, e.toString());
            return List.of();
        }
    }

    public static class RulesFile {
        public List<RuleDefinition> rules;
    }
}
