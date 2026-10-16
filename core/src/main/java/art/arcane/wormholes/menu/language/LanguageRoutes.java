package art.arcane.wormholes.menu.language;

import java.util.Objects;

import art.arcane.wormholes.menu.MenuRoute;

public final class LanguageRoutes {
    public static final String CONTROLLER = "language";
    public static final String LOCALES = "language.locales";
    public static final String GROUPS = "language.groups";
    public static final String KEYS = "language.keys";
    public static final String FORMS = "language.forms";
    public static final String LOCALE = "locale";
    public static final String GROUP = "group";
    public static final String FILTER = "filter";
    public static final String KEY = "key";

    private LanguageRoutes() {
    }

    public static MenuRoute locales() {
        return MenuRoute.of(CONTROLLER, LOCALES);
    }

    public static MenuRoute groups(String locale) {
        return MenuRoute.of(CONTROLLER, GROUPS).with(LOCALE, Objects.requireNonNull(locale, LOCALE));
    }

    public static MenuRoute group(String locale, String group) {
        return MenuRoute.of(CONTROLLER, KEYS)
            .with(LOCALE, Objects.requireNonNull(locale, LOCALE))
            .with(GROUP, Objects.requireNonNull(group, GROUP));
    }

    public static MenuRoute search(String locale, String filter) {
        return MenuRoute.of(CONTROLLER, KEYS)
            .with(LOCALE, Objects.requireNonNull(locale, LOCALE))
            .with(FILTER, Objects.requireNonNull(filter, FILTER));
    }

    public static MenuRoute forms(MenuRoute keys, String key) {
        if (!keys.screen().equals(KEYS)) {
            throw new IllegalArgumentException("forms open from " + KEYS + ", not " + keys.screen());
        }
        return new MenuRoute(CONTROLLER, FORMS, keys.params()).with(KEY, Objects.requireNonNull(key, KEY));
    }

    public static MenuRoute keys(MenuRoute forms) {
        return group(forms) == null ? search(locale(forms), filter(forms)) : group(locale(forms), group(forms));
    }

    public static String locale(MenuRoute route) {
        return Objects.requireNonNull(route.params().get(LOCALE), LOCALE);
    }

    public static String group(MenuRoute route) {
        return route.params().get(GROUP);
    }

    public static String filter(MenuRoute route) {
        return route.params().getOrDefault(FILTER, "");
    }

    public static String key(MenuRoute route) {
        return Objects.requireNonNull(route.params().get(KEY), KEY);
    }
}
