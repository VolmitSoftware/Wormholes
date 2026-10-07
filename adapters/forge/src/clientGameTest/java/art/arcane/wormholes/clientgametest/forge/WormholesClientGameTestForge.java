package art.arcane.wormholes.clientgametest.forge;

import art.arcane.wormholes.clientgametest.SeamlessQaCommand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("wormholes_clientgametest")
public final class WormholesClientGameTestForge {
    public WormholesClientGameTestForge(FMLJavaModLoadingContext context) {
        RegisterCommandsEvent.BUS.addListener(event -> SeamlessQaCommand.register(event.getDispatcher()));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientGameTestClient.initialize();
        }
    }
}
