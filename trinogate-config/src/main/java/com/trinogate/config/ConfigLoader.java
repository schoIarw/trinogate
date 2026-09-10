package com.trinogate.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@link GatewayConfig} from a YAML file with ${ENV:VAR} environment interpolation.
 * Config path resolution: system property "trinogate.config" > env TRINOGATE_CONFIG > ./config.yaml.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{ENV:([A-Za-z0-9_]+)}");

    private ConfigLoader() {}

    public static GatewayConfig load() {
        String path = System.getProperty("trinogate.config");
        if (path == null || path.isBlank()) {
            path = System.getenv("TRINOGATE_CONFIG");
        }
        if (path == null || path.isBlank()) {
            path = "config.yaml";
        }
        return load(Path.of(path));
    }

    public static GatewayConfig load(Path path) {
        if (!Files.exists(path)) {
            log.warn("Config file {} not found, using defaults", path.toAbsolutePath());
            return new GatewayConfig();
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            text = interpolateEnv(text);
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            GatewayConfig config = mapper.readValue(text, GatewayConfig.class);
            normalize(config);
            return config;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load config " + path, e);
        }
    }

    private static String interpolateEnv(String text) {
        Matcher m = ENV_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = System.getenv(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void normalize(GatewayConfig config) {
        String base = config.getServer().getGatewayBaseUri();
        if (base == null || base.isBlank()) {
            config.getServer().setGatewayBaseUri("http://localhost:" + config.getServer().getHttpPort());
        }
    }
}
