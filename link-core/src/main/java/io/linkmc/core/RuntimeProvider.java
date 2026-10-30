package io.linkmc.core;

import io.linkmc.api.LinkApi;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Installed by Link bootstrap, shared with the plugin-facing ServiceLoader API. */
public final class RuntimeProvider implements LinkApi {
    private static final AtomicReference<LinkApi> RUNTIME=new AtomicReference<>();
    public static void install(LinkApi service) {
        if(!RUNTIME.compareAndSet(null,Objects.requireNonNull(service))) throw new IllegalStateException("Link already initialized");
    }
    public static void uninstall(LinkApi service) { RUNTIME.compareAndSet(service,null); }
    private static LinkApi runtime() {
        LinkApi service=RUNTIME.get();
        if(service==null) throw new IllegalStateException("Link runtime is disabled or unavailable");
        return service;
    }
    @Override public Data data() {return runtime().data();}
    @Override public Sessions sessions() {return runtime().sessions();}
    @Override public Network network() {return runtime().network();}
    @Override public Worlds worlds() {return runtime().worlds();}
}
