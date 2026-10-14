package art.arcane.wormholes.modded;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

import java.util.Optional;

public final class MinecraftTeamColors {
    private MinecraftTeamColors() {
    }

    public static void white(PlayerTeam team) {
        team.setColor(Optional.of(TeamColor.WHITE));
    }
}
