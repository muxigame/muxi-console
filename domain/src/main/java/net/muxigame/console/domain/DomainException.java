package net.muxigame.console.domain;

/** A stable rejection code for a request that must not mutate domain state. */
public final class DomainException extends IllegalArgumentException {
    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
