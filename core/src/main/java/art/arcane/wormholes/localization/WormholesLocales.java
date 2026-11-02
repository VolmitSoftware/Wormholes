package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LanguageDirectory;
import art.arcane.volmlib.util.localization.VolmitLocales;

public final class WormholesLocales {
    private static final String ENGLISH = "en_US";

    private WormholesLocales() {
    }

    public static String normalize(String locale) {
        if (locale == null || !LanguageDirectory.LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            return ENGLISH;
        }
        return canonical(locale.trim());
    }

    static String require(String locale) {
        if (locale == null || !LanguageDirectory.LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            throw new IllegalArgumentException("Invalid language locale: " + locale);
        }
        return canonical(locale.trim());
    }

    private static String canonical(String locale) {
        for (String known : VolmitLocales.all()) {
            if (LanguageDirectory.sameLocale(known, locale)) {
                return known;
            }
        }
        return locale;
    }
}
