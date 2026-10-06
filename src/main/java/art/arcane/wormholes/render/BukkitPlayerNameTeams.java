package art.arcane.wormholes.render;

import java.util.List;
import org.bukkit.entity.Player;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import art.arcane.optics.entity.PlayerNames;

final class BukkitPlayerNameTeams implements PlayerNames.Host<Player> {
    private final EntityRenderPacketChannel channel;

    BukkitPlayerNameTeams(EntityRenderPacketChannel channel) {
        this.channel = channel;
    }

    @Override
    public void create(Player observer, String teamName) {
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(), Component.empty(), Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE);
        channel.send(observer, new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.CREATE, info, List.of()));
    }

    @Override
    public void add(Player observer, String teamName, String name) {
        channel.send(observer, new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
            (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, name));
    }

    @Override
    public void remove(Player observer, String teamName, String name) {
        channel.send(observer, new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
            (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, name));
    }

    @Override
    public void removeTeam(Player observer, String teamName) {
        channel.send(observer, new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.REMOVE,
            (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of()));
    }
}
