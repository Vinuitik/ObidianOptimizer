package com.obsidian.obsidian.vaults;

/** Business-rule failure of a vault operation; {@code status} is the REST code (400/404/409). */
public class VaultException extends RuntimeException {
    private final int status;

    public VaultException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }

    public static VaultException notFound(long id) { return new VaultException(404, "unknown vault " + id); }

    public static VaultException conflict(String msg) { return new VaultException(409, msg); }
}
