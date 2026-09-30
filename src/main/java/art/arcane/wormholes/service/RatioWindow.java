package art.arcane.wormholes.service;

final class RatioWindow {
    private static final long MIN_WINDOW_MS = 1000L;

    private boolean primed;
    private long windowStartMs;
    private long windowNumerator;
    private long windowDenominator;
    private double ratio = Double.NaN;

    synchronized double ratio(long numerator, long denominator, long now) {
        if (!primed) {
            primed = true;
            startWindow(numerator, denominator, now);
            ratio = Double.NaN;
            return ratio;
        }
        if (now - windowStartMs < MIN_WINDOW_MS) {
            return ratio;
        }

        long numeratorDelta = numerator - windowNumerator;
        long denominatorDelta = denominator - windowDenominator;
        ratio = denominatorDelta > 0L && numeratorDelta >= 0L
            ? numeratorDelta / (double) denominatorDelta
            : Double.NaN;
        startWindow(numerator, denominator, now);
        return ratio;
    }

    synchronized void clear() {
        primed = false;
        windowStartMs = 0L;
        windowNumerator = 0L;
        windowDenominator = 0L;
        ratio = Double.NaN;
    }

    private void startWindow(long numerator, long denominator, long now) {
        windowStartMs = now;
        windowNumerator = numerator;
        windowDenominator = denominator;
    }
}
