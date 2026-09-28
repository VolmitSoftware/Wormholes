package art.arcane.wormholes.portal;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TravelCurrencyAmount {
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
    private static final int MAX_SCALE = 8;

    private TravelCurrencyAmount() {
    }

    public static BigDecimal parse(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("Vault travel cost must be a decimal amount");
        }
        try {
            return normalize(new BigDecimal(amount));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Vault travel cost must be a decimal amount", exception);
        }
    }

    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Vault travel cost must be greater than zero and at most " + MAX_AMOUNT);
        }
        BigDecimal normalized = amount.setScale(Math.min(Math.max(amount.scale(), 0), MAX_SCALE), RoundingMode.HALF_UP)
            .stripTrailingZeros();
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("Vault travel cost is too small");
        }
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}
