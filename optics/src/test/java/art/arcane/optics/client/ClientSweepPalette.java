package art.arcane.optics.client;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import art.arcane.optics.client.ClientSweepScene;

final class ClientSweepPalette {
    static final int BLACKOUT_ID = 3;
    static final int BACKING_STATE_ID = 4;

    private final Object2IntOpenHashMap<String> ids;
    private final List<String> states;

    ClientSweepPalette() {
        this.ids = new Object2IntOpenHashMap<String>();
        this.ids.defaultReturnValue(-1);
        this.states = new ArrayList<String>();
        register(ClientSweepScene.AIR);
        register("wormholes:occluded");
        register("wormholes:backing");
        register(ClientSweepScene.BLACKOUT);
        register(ClientSweepScene.STONE);
    }

    int id(String state) {
        int id = ids.getInt(state);
        return id >= 0 ? id : register(state);
    }

    String state(int id) {
        return states.get(id);
    }

    private int register(String state) {
        int id = states.size();
        states.add(state);
        ids.put(state, id);
        return id;
    }
}
