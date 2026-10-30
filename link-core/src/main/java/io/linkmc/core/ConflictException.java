package io.linkmc.core;

/** A rejected operation, not permission to retry with a new operation ID. */
public final class ConflictException extends IllegalStateException {
    public ConflictException(String reason) { super(reason); }
}
