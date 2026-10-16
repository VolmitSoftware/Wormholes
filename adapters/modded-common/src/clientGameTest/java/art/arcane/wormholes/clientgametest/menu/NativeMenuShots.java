package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.clientgametest.ClientGui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeMenuShots {
    private static final int[] SCALES = {2, 4};
    private static final int SETTLE_TICKS = 3;

    private final MenuScreenDriver driver;
    private final Path directory;
    private final List<Path> captured = new ArrayList<>();

    public NativeMenuShots(MenuScreenDriver driver, Path directory) {
        this.driver = driver;
        this.directory = directory;
    }

    public List<Path> captured() {
        return List.copyOf(captured);
    }

    public void capture(String name) {
        NativeMenuInput input = driver.input();
        int original = driver.guiScale();
        try {
            for (int scale : SCALES) {
                driver.guiScale(scale);
                input.runOnClient(ClientGui::clearToasts);
                input.setCursorPos(0.0D, 0.0D);
                input.waitTicks(SETTLE_TICKS);
                driver.assertLayout();
                captured.add(input.screenshot(name + "-gui" + scale, directory));
            }
        } finally {
            driver.guiScale(original);
        }
    }
}
