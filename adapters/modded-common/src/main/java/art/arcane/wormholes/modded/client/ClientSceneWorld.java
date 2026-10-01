package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;

public interface ClientSceneWorld {
    boolean spawn(int entityId, EntityVisual visual);

    void move(int entityId, EntityVisual visual);

    void metadata(int entityId, byte[] metadata);

    void equipment(int entityId, byte[] equipment);

    void remove(int entityId, EntityVisual visual);

    void particle(String key, double x, double y, double z, double spread, double speed, int count);

    void burst(String key, double x, double y, double z, double spreadHorizontal, double spreadVertical, double speed, int count);

    void emission(PortalAnimation.ParticleEmission emission);

    void dust(double x, double y, double z, int rgb, float scale);

    void sound(String key, double x, double y, double z, float volume, float pitch, AcousticsProfile.SoundClass soundClass);

    float rain();

    float thunder();

    void weather(float rain, float thunder);

    boolean hasClock();

    long clock();

    void clock(long ticks);

    long gameTime();
}
