package com.trinogate.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicAuthenticatorTest {

    @TempDir
    Path tempDir;

    private static String basic(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        return "Basic " + token;
    }

    private BasicAuthenticator authenticator(Path userFile) {
        return new BasicAuthenticator(new BasicAuthenticator.FileUserStore(userFile.toString()));
    }

    private Path writeUserFile(String yaml) throws Exception {
        Path path = tempDir.resolve("users.yaml");
        Files.writeString(path, yaml);
        return path;
    }

    private static final String SHA256_TRINO123 =
            "4d6a7f7c0441a3483f985e315cd40712c3f758ce5400e0ed27903623d67721b2";

    @Test
    void acceptsValidCredentialsWithSha256Password() throws Exception {
        Path file = writeUserFile("""
                users:
                  - { username: alice, passwordSha256: "%s" }
                """.formatted(SHA256_TRINO123));
        BasicAuthenticator auth = authenticator(file);

        Optional<IdentityContext> ok = auth.authenticate(Map.of("Authorization", basic("alice", "trino123")), "127.0.0.1");
        assertTrue(ok.isPresent());
        assertEquals("alice", ok.get().getUser());
        assertEquals("basic", ok.get().getAuthType());
    }

    @Test
    void acceptsPlaintextPasswordField() throws Exception {
        Path file = writeUserFile("""
                users:
                  - { username: bob, password: "secret42" }
                """);
        BasicAuthenticator auth = authenticator(file);
        assertTrue(auth.authenticate(Map.of("Authorization", basic("bob", "secret42")), "127.0.0.1").isPresent());
    }

    @Test
    void rejectsWrongPasswordUnknownUserAndMissingHeader() throws Exception {
        Path file = writeUserFile("""
                users:
                  - { username: alice, passwordSha256: "%s" }
                """.formatted(SHA256_TRINO123));
        BasicAuthenticator auth = authenticator(file);

        assertFalse(auth.authenticate(Map.of("Authorization", basic("alice", "wrong")), "127.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("Authorization", basic("nobody", "trino123")), "127.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of(), "127.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("Authorization", "Bearer xyz"), "127.0.0.1").isPresent());
    }

    @Test
    void missingUserFileDisablesAuthentication() throws Exception {
        BasicAuthenticator auth = authenticator(tempDir.resolve("not-exists.yaml"));
        assertFalse(auth.authenticate(Map.of("Authorization", basic("alice", "trino123")), "127.0.0.1").isPresent());
    }
}
