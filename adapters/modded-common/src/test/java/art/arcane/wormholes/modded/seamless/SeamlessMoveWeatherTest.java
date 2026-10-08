package art.arcane.wormholes.modded.seamless;

import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class SeamlessMoveWeatherTest {
    @Test
    public void clearArrivalLeavesTheRetainedClientLevelClearAfterItRained() {
        ClientWeather retained = new ClientWeather(1.0F, 1.0F);

        retained.apply(MinecraftSeamlessMove.weather(false, 0.0F, 0.0F));

        assertEquals(0.0F, retained.rain, 0.0F);
        assertEquals(0.0F, retained.thunder, 0.0F);
    }

    @Test
    public void clearArrivalOnAFreshClientLevelStaysClear() {
        ClientWeather fresh = new ClientWeather(0.0F, 0.0F);

        fresh.apply(MinecraftSeamlessMove.weather(false, 0.0F, 0.0F));

        assertEquals(0.0F, fresh.rain, 0.0F);
        assertEquals(0.0F, fresh.thunder, 0.0F);
    }

    @Test
    public void rainingArrivalCarriesTheServerLevels() {
        ClientWeather retained = new ClientWeather(0.0F, 0.0F);

        retained.apply(MinecraftSeamlessMove.weather(true, 0.75F, 0.25F));

        assertEquals(0.75F, retained.rain, 0.0F);
        assertEquals(0.25F, retained.thunder, 0.0F);
    }

    @Test
    public void fadingArrivalCarriesThePartialServerLevelsEitherWay() {
        ClientWeather fadingOut = new ClientWeather(0.0F, 0.0F);
        ClientWeather fadingIn = new ClientWeather(1.0F, 1.0F);

        fadingOut.apply(MinecraftSeamlessMove.weather(false, 0.15F, 0.05F));
        fadingIn.apply(MinecraftSeamlessMove.weather(true, 0.3F, 0.0F));

        assertEquals(0.15F, fadingOut.rain, 0.0F);
        assertEquals(0.05F, fadingOut.thunder, 0.0F);
        assertEquals(0.3F, fadingIn.rain, 0.0F);
        assertEquals(0.0F, fadingIn.thunder, 0.0F);
    }

    private static final class ClientWeather {
        private float rain;
        private float thunder;

        private ClientWeather(float rain, float thunder) {
            this.rain = rain;
            this.thunder = thunder;
        }

        private void apply(List<ClientboundGameEventPacket> packets) {
            for (ClientboundGameEventPacket packet : packets) {
                ClientboundGameEventPacket.Type event = packet.getEvent();
                if (event == ClientboundGameEventPacket.START_RAINING) {
                    rain = 0.0F;
                } else if (event == ClientboundGameEventPacket.STOP_RAINING) {
                    rain = 1.0F;
                } else if (event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
                    rain = packet.getParam();
                } else if (event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
                    thunder = packet.getParam();
                }
            }
        }
    }
}
