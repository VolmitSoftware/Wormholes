/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the swap of SodiumRenderingContext in SodiumInterface as a stack of captured renderer states,
 * one per open portal layer, with the layer depth as the slot that per-layer Sodium state is kept under.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import java.util.ArrayDeque;

public final class LayerContextStack<S> {
    private final ArrayDeque<Frame<S>> frames = new ArrayDeque<>();
    private int slot;

    public int slot() {
        return slot;
    }

    public boolean open() {
        return !frames.isEmpty();
    }

    public Context<S> top() {
        Frame<S> frame = frames.peek();
        return frame == null ? null : frame.context();
    }

    public void enter(int depth, Context<S> context) {
        if (depth <= slot) {
            throw new IllegalStateException("Portal layer " + depth + " cannot open inside portal layer " + slot);
        }
        frames.push(new Frame<>(context, context.capture(), slot));
        slot = depth;
    }

    public void exit(Context<S> context) {
        Frame<S> frame = frames.peek();
        if (frame == null || frame.context() != context) {
            throw new IllegalStateException("Portal layer " + slot + " closed out of order");
        }
        frames.pop();
        slot = frame.slot();
        context.restore(frame.saved());
    }

    public interface Context<S> {
        S capture();

        void restore(S state);
    }

    private record Frame<S>(Context<S> context, S saved, int slot) {
    }
}
