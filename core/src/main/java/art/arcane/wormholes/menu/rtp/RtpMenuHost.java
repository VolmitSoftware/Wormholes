package art.arcane.wormholes.menu.rtp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;

public interface RtpMenuHost {
    Optional<EditorSnapshot> snapshot(UUID portalId);

    List<BiomeOption> biomeOptions(UUID portalId);

    CompletableFuture<RtpOutcome> mutate(MenuViewer viewer, UUID portalId, long expectedRevision, Mutation mutation);

    CompletableFuture<RtpOutcome> reset(MenuViewer viewer, UUID portalId, long expectedRevision);

    CompletableFuture<RtpOutcome> manual(MenuViewer viewer, UUID portalId, long expectedRevision, ManualAction action);
}
