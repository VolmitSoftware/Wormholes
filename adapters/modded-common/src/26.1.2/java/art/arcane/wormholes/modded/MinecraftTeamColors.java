package art.arcane.wormholes.modded;

import net.minecraft.ChatFormatting;
import net.minecraft.world.scores.PlayerTeam;

public final class MinecraftTeamColors {
    private MinecraftTeamColors() {
    }

    public static void white(PlayerTeam team) {
        team.setColor(ChatFormatting.WHITE);
    }
}
