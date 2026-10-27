package org.edtp.edb.adaptors.vmp;

import net.fabricmc.loader.api.FabricLoader;

public final class VmpCompatibility {
    private VmpCompatibility() {}
    public static void verify() {
        if (!FabricLoader.getInstance().isModLoaded("vmp")) return;
        try {
            if (Class.forName("com.ishland.vmp.common.config.Config").getField("USE_ASYNC_CHUNKS_ON_SOME_COMMANDS").getBoolean(null))
                throw new IllegalStateException("VMP asynchronous commands must be disabled before using EDB (use_async_chunks_on_some_commands)");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported VMP configuration API", error); }
    }
}
