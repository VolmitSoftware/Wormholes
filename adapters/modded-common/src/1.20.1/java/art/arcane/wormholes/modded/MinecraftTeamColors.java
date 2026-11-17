package art.arcane.wormholes.modded;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.ChatFormatting;


public final class MinecraftTeamColors {
    private MinecraftTeamColors() {
    }

    public static void white(PlayerTeam team) {
        team.setColor(ChatFormatting.WHITE);
    }
}
