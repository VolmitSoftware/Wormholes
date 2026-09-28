package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireCodec;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeerIntroductionPolicyTest {
    private static PeerAnnounce signedAnnounce() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new PeerAnnounce("gamma", WireCodec.PROTOCOL_VERSION, "test", "10.0.0.7", 8903, new GameEndpoint("10.0.0.7", 25567), null,
            keys.getPublic().getEncoded(), 1L, WireCapability.localSet(), 1L, new byte[0]).signWith(keys.getPrivate());
    }

    private static NetworkConfig.MeshConfig mesh(String introducers, boolean autoAccept, String... allowlist) {
        NetworkConfig.MeshConfig mesh = new NetworkConfig.MeshConfig();
        mesh.introducers = introducers;
        mesh.autoAcceptIntroductions = autoAccept;
        mesh.introducerAllowlist = List.of(allowlist);
        mesh.normalizeRuntimeBounds();
        return mesh;
    }

    @Test
    void trustedIntroducersQuarantineByDefaultAndAcceptWhenAutoAcceptIsOn() throws Exception {
        PeerAnnounce announce = signedAnnounce();
        assertEquals(PeerIntroductionPolicy.Decision.QUARANTINE, PeerIntroductionPolicy.decide(announce, "beta", mesh("trusted", false)));
        assertEquals(PeerIntroductionPolicy.Decision.ACCEPT, PeerIntroductionPolicy.decide(announce, "beta", mesh("trusted", true)));
    }

    @Test
    void allowlistOnlyHonoursListedIntroducers() throws Exception {
        PeerAnnounce announce = signedAnnounce();
        assertEquals(PeerIntroductionPolicy.Decision.QUARANTINE, PeerIntroductionPolicy.decide(announce, "beta", mesh("allowlist", false, "beta")));
        assertEquals(PeerIntroductionPolicy.Decision.ACCEPT, PeerIntroductionPolicy.decide(announce, "Beta", mesh("allowlist", true, "beta")));
        assertEquals(PeerIntroductionPolicy.Decision.REJECT, PeerIntroductionPolicy.decide(announce, "delta", mesh("allowlist", true, "beta")));
    }

    @Test
    void manualIgnoresIntroductionsAndDisabledMeshRejects() throws Exception {
        PeerAnnounce announce = signedAnnounce();
        assertEquals(PeerIntroductionPolicy.Decision.REJECT, PeerIntroductionPolicy.decide(announce, "beta", mesh("manual", true)));
        NetworkConfig.MeshConfig disabled = mesh("trusted", true);
        disabled.enabled = false;
        assertEquals(PeerIntroductionPolicy.Decision.REJECT, PeerIntroductionPolicy.decide(announce, "beta", disabled));
    }

    @Test
    void unsignedOrTamperedAnnouncesAreRejectedRegardlessOfPolicy() throws Exception {
        PeerAnnounce signed = signedAnnounce();
        PeerAnnounce tampered = new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(),
            signed.privateGameEndpoint(), signed.publicKey(), signed.epoch() + 1L, signed.capabilities(), signed.issuedAtMillis(), signed.signature());
        assertEquals(PeerIntroductionPolicy.Decision.REJECT, PeerIntroductionPolicy.decide(tampered, "beta", mesh("trusted", true)));
    }
}
