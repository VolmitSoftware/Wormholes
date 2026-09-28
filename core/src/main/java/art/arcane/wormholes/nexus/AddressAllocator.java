package art.arcane.wormholes.nexus;

import java.util.Objects;
import java.util.Random;

/** Picks a short dial address no member of the network already answers to. */
public final class AddressAllocator {
    private static final String FALLBACK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 16;
    private static final int ATTEMPTS_PER_LENGTH = 256;

    private AddressAllocator() {
    }

    public static String next(PortalNetwork network, String alphabet, int length, Random random) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(random, "random");
        char[] characters = sanitizeAlphabet(alphabet);
        int width = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));

        while (width <= MAX_LENGTH) {
            for (int attempt = 0; attempt < ATTEMPTS_PER_LENGTH; attempt++) {
                String candidate = draw(characters, width, random);
                if (!network.usesAddress(candidate)) {
                    return candidate;
                }
            }
            width++;
        }
        throw new IllegalStateException("no free address in network " + network.id() + " up to " + MAX_LENGTH + " characters");
    }

    public static boolean isValid(String address, String alphabet) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String normalized = NetworkMember.normalizeAddress(address);
        String allowed = new String(sanitizeAlphabet(alphabet));
        for (int index = 0; index < normalized.length(); index++) {
            if (allowed.indexOf(normalized.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String draw(char[] characters, int width, Random random) {
        char[] drawn = new char[width];
        for (int index = 0; index < width; index++) {
            drawn[index] = characters[random.nextInt(characters.length)];
        }
        return new String(drawn);
    }

    private static char[] sanitizeAlphabet(String alphabet) {
        String source = alphabet == null || alphabet.isBlank() ? FALLBACK_ALPHABET : alphabet;
        StringBuilder unique = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char character = Character.toUpperCase(source.charAt(index));
            if (!Character.isWhitespace(character) && unique.indexOf(String.valueOf(character)) < 0) {
                unique.append(character);
            }
        }
        return unique.length() == 0 ? FALLBACK_ALPHABET.toCharArray() : unique.toString().toCharArray();
    }
}
