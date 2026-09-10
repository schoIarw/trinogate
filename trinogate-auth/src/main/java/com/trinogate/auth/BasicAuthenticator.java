package com.trinogate.auth;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic (RFC 7617) authenticator backed by a YAML password file:
 * <pre>
 *   users:
 *     - { username: alice, password: secret }
 *     - { username: bob,   passwordSha256: "..." }   # hex sha256 of the password
 * </pre>
 * An LDAP-backed store can be plugged in by replacing {@link UserStore}.
 */
public class BasicAuthenticator implements Authenticator {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthenticator.class);
    private static final String BASIC_PREFIX = "Basic ";
    private static final String SHA256_PREFIX = "sha256:";

    private final UserStore userStore;

    public BasicAuthenticator(UserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public String type() {
        return "basic";
    }

    @Override
    public Optional<IdentityContext> authenticate(Map<String, String> headers, String remoteAddr) {
        String authz = first(headers, "Authorization");
        if (authz == null || !authz.startsWith(BASIC_PREFIX)) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(java.util.Base64.getDecoder().decode(authz.substring(BASIC_PREFIX.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        if (!userStore.verify(username, password)) {
            return Optional.empty();
        }
        return Optional.of(IdentityContext.of(username, type()));
    }

    private static String first(Map<String, String> headers, String name) {
        String v = headers.get(name);
        return v != null ? v : headers.get(name.toLowerCase());
    }

    /** User credential store; swap for LDAP by implementing this interface. */
    public interface UserStore {
        boolean verify(String username, String password);
    }

    /** File-backed store: loads a YAML users file (plaintext or sha256 hashed passwords). */
    public static class FileUserStore implements UserStore {
        private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        private volatile Map<String, String> users = Map.of();
        private final Path path;

        public FileUserStore(String path) {
            this.path = Path.of(path);
            reload();
        }

        public synchronized void reload() {
            if (!Files.exists(path)) {
                log.warn("User file {} not found; no basic-auth users configured", path);
                users = Map.of();
                return;
            }
            try {
                UserFile file = YAML.readValue(Files.readString(path, StandardCharsets.UTF_8), UserFile.class);
                Map<String, String> map = new ConcurrentHashMap<>();
                if (file.users != null) {
                    for (UserEntry u : file.users) {
                        String stored = u.password != null ? u.password : SHA256_PREFIX + sha256Hex(u.passwordSha256 == null ? "" : u.passwordSha256);
                        map.put(u.username, stored);
                    }
                }
                users = map;
                log.info("Loaded {} users from {}", map.size(), path);
            } catch (IOException e) {
                log.error("Failed to load user file {}", path, e);
            }
        }

        @Override
        public boolean verify(String username, String password) {
            String stored = users.get(username);
            if (stored == null) {
                return false;
            }
            if (stored.startsWith(SHA256_PREFIX)) {
                return constantTimeEquals(stored.substring(SHA256_PREFIX.length()), sha256Hex(password));
            }
            return constantTimeEquals(stored, password);
        }

        private static String sha256Hex(String value) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static class UserFile {
        public List<UserEntry> users;
    }

    public static class UserEntry {
        public String username;
        public String password;
        public String passwordSha256;
    }
}
