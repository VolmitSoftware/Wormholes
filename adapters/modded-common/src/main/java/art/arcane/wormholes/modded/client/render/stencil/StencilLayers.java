/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the portal layer stack of PortalRendering as plain bookkeeping with mirror parity and a frame budget.
 */
package art.arcane.wormholes.modded.client.render.stencil;

public final class StencilLayers {
    public static final int MAX_REFERENCE = 255;

    private final int maxDepth;
    private final boolean[] reflections;
    private int depth;
    private int reflected;
    private int viewsLeft;
    private boolean stencilCleared;

    public StencilLayers(int maxDepth) {
        if (maxDepth < 1 || maxDepth > MAX_REFERENCE) {
            throw new IllegalArgumentException("Portal layer depth must be in 1.." + MAX_REFERENCE + ", got " + maxDepth);
        }
        this.maxDepth = maxDepth;
        reflections = new boolean[maxDepth];
    }

    public void beginFrame(int viewBudget) {
        if (depth != 0) {
            throw new IllegalStateException("A frame began while portal layer " + depth + " was still open");
        }
        viewsLeft = Math.max(0, viewBudget);
        stencilCleared = false;
    }

    public boolean claimStencilClear() {
        if (stencilCleared) {
            return false;
        }
        stencilCleared = true;
        return true;
    }

    public boolean claimView() {
        if (viewsLeft <= 0) {
            return false;
        }
        viewsLeft--;
        return true;
    }

    public int depth() {
        return depth;
    }

    public boolean nested() {
        return depth > 0;
    }

    public int reference() {
        return depth;
    }

    public int outerReference() {
        if (depth == 0) {
            throw new IllegalStateException("The outer layer has no enclosing layer");
        }
        return depth - 1;
    }

    public boolean mirrored() {
        return (reflected & 1) != 0;
    }

    public boolean canEnter(int requestedDepth) {
        return depth < Math.min(maxDepth, requestedDepth);
    }

    public int enter(boolean mirrored) {
        if (depth >= maxDepth) {
            throw new IllegalStateException("Portal layer depth " + maxDepth + " exceeded");
        }
        reflections[depth] = mirrored;
        if (mirrored) {
            reflected++;
        }
        depth++;
        return depth;
    }

    public void exit() {
        if (depth == 0) {
            throw new IllegalStateException("No portal layer is open");
        }
        depth--;
        if (reflections[depth]) {
            reflected--;
        }
    }
}
