package io.github.aplite36.chunkcarts.bukkit;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Skeleton entry point. Logs its version and does nothing else — no listeners, no scheduler,
 * no tickets. SPEC §12 step 1: prove the build and the load path before any behaviour exists.
 */
public final class ChunkCartsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ChunkCarts " + getPluginMeta().getVersion()
                + " enabled — skeleton build, no behaviour yet");
    }
}
