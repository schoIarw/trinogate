package com.trinogate.config;

/** Authentication settings (design doc M2). */
public class AuthConfig {

    /** header | basic | jwt */
    private String type = "header";

    /** Header name used by the {@code header} authenticator. */
    private String headerName = "X-Auth-User";

    /** Path to YAML user file for the {@code basic} authenticator: users: [{username, password|passwordSha256}]. */
    private String passwordFile = "users.yaml";

    /** HMAC secret for the {@code jwt} authenticator. */
    private String jwtSecret = "";

    private String jwtIssuer = "";
    private String jwtAudience = "";

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public String getPasswordFile() { return passwordFile; }
    public void setPasswordFile(String passwordFile) { this.passwordFile = passwordFile; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getJwtIssuer() { return jwtIssuer; }
    public void setJwtIssuer(String jwtIssuer) { this.jwtIssuer = jwtIssuer; }
    public String getJwtAudience() { return jwtAudience; }
    public void setJwtAudience(String jwtAudience) { this.jwtAudience = jwtAudience; }
}
