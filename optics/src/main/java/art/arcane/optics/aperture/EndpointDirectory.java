package art.arcane.optics.aperture;

import java.util.List;

import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Box;

public interface EndpointDirectory<W, P extends Endpoint> {
    List<P> endpoints();

    W world(P endpoint);

    CellAperture aperture(P endpoint);

    Box view(P endpoint);

    boolean eligible(P endpoint);

    boolean mirror(P endpoint);

    QuarterTurn mirrorTurns(P endpoint);

    P destination(P endpoint);
}
