package org.watermedia.binaries.bootstrap;

import net.neoforged.fml.common.Mod;
import org.watermedia.binaries.WaterMediaBinaries;

@Mod(WaterMediaBinaries.ID)
public class NeoBootstrap {
    private static final String NAME = "NeoForge";

    public NeoBootstrap() {
        try {
        } catch (Exception e) {
            throw new RuntimeException("Failed starting " + WaterMediaBinaries.NAME + " for " + NAME +": " + e.getMessage(), e);
        }
    }
}
