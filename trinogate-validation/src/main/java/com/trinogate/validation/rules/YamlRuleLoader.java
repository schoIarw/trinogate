package com.trinogate.validation.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * Every {@code *.yaml} file may contain a top-level {@code rules: [...]} list.
 * The {@link RuleManager} polls this directory for hot reload.
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
            RulesFile rulesFile = YAML.readValue(text, RulesFile.class);
            List<SqlRule> rules = new ArrayList<>();
            if (rulesFile.rules != null) {
                for (RuleDefinition def : rulesFile.rules) {
                    if (def.id == null || def.id.isBlank()) {
                        log.warn("Ignoring rule without id in {}", file);
                        continue;
                    }
                    rules.add(new DynamicRule(def));
                }
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
