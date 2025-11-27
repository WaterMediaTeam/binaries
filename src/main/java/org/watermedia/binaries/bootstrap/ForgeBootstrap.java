package org.watermedia.binaries.bootstrap;

import net.minecraftforge.fml.common.Mod;
import org.watermedia.binaries.WaterMediaBinaries;

@Mod(WaterMediaBinaries.ID)
public class ForgeBootstrap {
    private static final String NAME = "Forge";

    public ForgeBootstrap() {
        try {
        } catch (Exception e) {
            throw new RuntimeException("Failed starting " + WaterMediaBinaries.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }
}
