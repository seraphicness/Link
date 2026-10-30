package io.linkmc.api;

import java.util.ServiceLoader;

/** Plugin entry point. The Link server supplies a provider, not a bundled plugin copy. */
public final class Link {
    private Link() {}
    public static LinkApi api() {
        return ServiceLoader.load(LinkApi.class, LinkApi.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Link runtime is not installed or initialized"));
    }
}
