package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisHistory;
import net.irisshaders.iris.uniforms.CameraUniforms;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
public abstract class IrisPortalCameraHistoryMixin implements PortalIrisHistory.State {
    @Shadow @Final private Vector3d shift;
    @Shadow private Vector3d previousCameraPosition;
    @Shadow private Vector3d currentCameraPosition;
    @Shadow private Vector3d previousCameraPositionUnshifted;
    @Shadow private Vector3d currentCameraPositionUnshifted;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$capture(CallbackInfo callback) {
        PortalIrisHistory.register(this);
    }

    @Override
    public void wormholes$resetHistory() {
        shift.zero();
        Vector3d position = CameraUniforms.getUnshiftedCameraPosition();
        currentCameraPosition = new Vector3d(position);
        previousCameraPosition = new Vector3d(position);
        currentCameraPositionUnshifted = new Vector3d(position);
        previousCameraPositionUnshifted = new Vector3d(position);
    }
}
