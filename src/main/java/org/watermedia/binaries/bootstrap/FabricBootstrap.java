package org.watermedia.binaries.bootstrap;

import net.fabricmc.api.ModInitializer;
import org.watermedia.binaries.WaterMediaBinaries;

public class FabricBootstrap implements ModInitializer {
    private static final String NAME = "Fabric";

    @Override
    public void onInitialize() {
        try {
        } catch (Exception e) {
            throw new RuntimeException("Failed starting " + WaterMediaBinaries.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }
}
