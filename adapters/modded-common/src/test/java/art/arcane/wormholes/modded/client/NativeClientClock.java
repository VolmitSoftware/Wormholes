package art.arcane.wormholes.modded.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.mockito.verification.VerificationMode;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class NativeClientClock {
    private NativeClientClock() {
    }

    static void stub(Entity entity) {
        when(entity.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        doCallRealMethod().when(entity).commonTick();
    }

    static void verifyTick(Entity entity, VerificationMode mode) {
        verify(entity, mode).commonTick();
    }

    static ItemEntity item(ClientLevel level) {
        ItemEntity item = mock(ItemEntity.class);
        AtomicReference<Vec3> position = new AtomicReference<>(Vec3.ZERO);
        when(item.level()).thenReturn(level);
        when(item.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        when(item.position()).thenAnswer(ignored -> position.get());
        when(item.getX()).thenAnswer(ignored -> position.get().x);
        when(item.getY()).thenAnswer(ignored -> position.get().y);
        when(item.getZ()).thenAnswer(ignored -> position.get().z);
        when(item.storePositionAndRotation()).thenAnswer(ignored -> PositionAndRotation.of(position.get(), 0, 0));
        doAnswer(call -> {
            position.set(call.getArgument(0));
            return null;
        }).when(item).setPos(any(Vec3.class));
        doAnswer(call -> {
            position.set(new Vec3(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(item).setPos(anyDouble(), anyDouble(), anyDouble());
        doAnswer(ignored -> {
            Vec3 previous = position.get();
            item.xOld = previous.x;
            item.yOld = previous.y;
            item.zOld = previous.z;
            return null;
        }).when(item).setOldPosAndRot();
        doCallRealMethod().when(item).commonTick();
        return item;
    }

}
