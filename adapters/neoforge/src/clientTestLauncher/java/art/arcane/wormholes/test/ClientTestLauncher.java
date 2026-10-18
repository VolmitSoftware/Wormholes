package art.arcane.wormholes.test;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.startup.StartupArgs;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class ClientTestLauncher implements LauncherSessionListener {
    private ClassLoader previousLoader;
    private FMLLoader loader;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            loader = FMLLoader.create(new StartupArgs(Path.of(""), true, Dist.CLIENT, false,
                new String[0], Set.of(), List.of(), previousLoader));
            if (loader.getLoadingModList().hasErrors()) {
                throw new ModLoadingException(loader.getLoadingModList().getModLoadingIssues());
            }
            loader.getCurrentClassLoader().loadClass("art.arcane.wormholes.modded.MinecraftTestBase")
                .getMethod("bootstrap").invoke(null);
        } catch (ReflectiveOperationException failure) {
            close();
            throw new IllegalStateException("Cannot bootstrap client test registries", failure);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        close();
    }

    private void close() {
        try {
            if (loader != null) {
                loader.close();
                loader = null;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }
}
