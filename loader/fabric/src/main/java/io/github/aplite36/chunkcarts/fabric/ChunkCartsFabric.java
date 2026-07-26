package io.github.aplite36.chunkcarts.fabric;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skeleton entrypoint. SPEC §12 orders a skeleton that compiles and loads on a real server
 * before any adapter logic exists, because chunk-ticket APIs are where plausible-but-wrong
 * code gets produced and the compiler is the only thing that catches it.
 */
public final class ChunkCartsFabric implements ModInitializer {

    public static final String MOD_ID = "chunkcarts";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("ChunkCarts skeleton loaded.");
    }
}
