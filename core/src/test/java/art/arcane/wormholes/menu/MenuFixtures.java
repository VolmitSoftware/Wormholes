package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

public final class MenuFixtures {
    private static final String[] ITEMS = {"minecraft:compass", "minecraft:ender_eye", "minecraft:name_tag", "wormholes:portal_wand"};
    private static final String[] SPRITES = {"portal", "small/back", "delete", "settings", "small/rotate"};
    private static final int[] ALPHABET = "abcXYZ019 _-éß世界§".codePoints().toArray();
    private static final int EMOJI = 0x1F300;
    private static final List<MenuInteraction> PRESSABLE = List.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);

    private MenuFixtures() {
    }

    public static MenuScreen randomScreen(SplittableRandom random) {
        int elementCount = random.nextInt(24);
        List<MenuElement> elements = new ArrayList<>(elementCount + 2);
        Set<Integer> usedSlots = new HashSet<>();
        for (int index = 0; index < elementCount; index++) {
            elements.add(randomElement(random, "e" + index, usedSlots));
        }
        MenuScreen.Builder builder = MenuScreen.builder("screen-" + random.nextInt(1000))
            .title(randomText(random, 40))
            .context(randomText(random, 20))
            .revision(random.nextLong(Long.MAX_VALUE))
            .canGoBack(random.nextBoolean())
            .chrome(new MenuChrome(randomText(random, 6), randomText(random, 6), randomText(random, 6), randomText(random, 6),
                randomText(random, 6), randomText(random, 6), randomText(random, 6), randomText(random, 12), randomText(random, 6),
                randomText(random, 6), randomText(random, 12)));
        if (random.nextBoolean()) {
            int pageCount = 1 + random.nextInt(20);
            builder.paging(new MenuPaging(random.nextInt(pageCount), pageCount));
        }
        if (random.nextBoolean()) {
            MenuFeedbackStyle[] styles = MenuFeedbackStyle.values();
            builder.feedback(new MenuFeedback(styles[random.nextInt(styles.length)], randomText(random, 60)));
        }
        if (random.nextInt(4) == 0) {
            elements.add(new ButtonElement(MenuElementCommon.builder("confirm-accept").label("Yes").interactions(Set.of(MenuInteraction.PRIMARY)).build()));
            elements.add(new ButtonElement(MenuElementCommon.builder("confirm-cancel").label("No").interactions(Set.of(MenuInteraction.PRIMARY)).build()));
            builder.confirmation(new MenuConfirmation(randomLines(random, 3, 50), "confirm-accept", "confirm-cancel"));
        }
        return builder.elements(elements).build();
    }

    public static MenuElement randomElement(SplittableRandom random, String id, Set<Integer> usedSlots) {
        int kind = random.nextInt(9);
        Set<MenuInteraction> interactions = switch (kind) {
            case 2 -> pick(random, List.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY, MenuInteraction.SELECT));
            case 3 -> pick(random, List.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.BIG_INCREMENT,
                MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE));
            case 4 -> pick(random, List.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY));
            case 6 -> Set.of();
            default -> pick(random, PRESSABLE);
        };
        MenuElementCommon common = common(random, id, interactions, usedSlots);
        return switch (kind) {
            case 0 -> new ButtonElement(common);
            case 1 -> new ToggleElement(common, random.nextBoolean());
            case 2 -> {
                List<String> options = randomLines(random, 1 + random.nextInt(5), 12);
                yield new CycleElement(common, options, random.nextInt(options.size()));
            }
            case 3 -> {
                double min = random.nextInt(100) - 50;
                double max = min + random.nextInt(500);
                double value = min + (max - min) * random.nextDouble();
                yield new NumberElement(common, value, min, max, 1.0D + random.nextInt(4), 8.0D, randomText(random, 10));
            }
            case 4 -> {
                int maxLength = random.nextInt(MenuLimits.MAX_TEXT_INPUT_LENGTH + 1);
                String value = randomText(random, Math.min(maxLength, 20));
                while (MenuText.codePoints(value) > maxLength) {
                    value = value.substring(0, value.offsetByCodePoints(0, MenuText.codePoints(value) - 1));
                }
                ChestReopen reopen = ChestReopen.values()[random.nextInt(ChestReopen.values().length)];
                MenuRoute cancelRoute = reopen != ChestReopen.NEVER && random.nextBoolean() ? MenuRoute.of("rules", "rules.profile") : null;
                yield new TextElement(common, value, maxLength, randomText(random, 16), randomLines(random, random.nextInt(3), 30), reopen,
                    cancelRoute);
            }
            case 5 -> new ListEntryElement(common, random.nextBoolean());
            case 6 -> new LabelElement(common);
            case 7 -> new ItemCaptureElement(common, randomLines(random, random.nextInt(3), 30));
            default -> new LookCaptureElement(common);
        };
    }

    public static String randomText(SplittableRandom random, int maxCodePoints) {
        int length = random.nextInt(maxCodePoints + 1);
        StringBuilder builder = new StringBuilder(length * 2);
        for (int index = 0; index < length; index++) {
            builder.appendCodePoint(random.nextInt(12) == 0 ? EMOJI + random.nextInt(32) : ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private static MenuElementCommon common(SplittableRandom random, String id, Set<MenuInteraction> interactions, Set<Integer> usedSlots) {
        MenuStyle[] styles = MenuStyle.values();
        MenuSlot slot = null;
        if (random.nextBoolean()) {
            MenuSlot candidate = new MenuSlot(random.nextInt(9) - 4, random.nextInt(MenuLimits.MAX_CHEST_ROW + 1));
            if (usedSlots.add(candidate.slot())) {
                slot = candidate;
            }
        }
        List<MenuSideAction> sideActions = new ArrayList<>(3);
        for (MenuInteraction interaction : PRESSABLE) {
            if (interactions.contains(interaction) && random.nextInt(3) == 0) {
                sideActions.add(new MenuSideAction(interaction, randomText(random, 10), randomIcon(random)));
            }
        }
        return MenuElementCommon.builder(id)
            .label(randomText(random, 30))
            .description(randomLines(random, random.nextInt(5), 40))
            .chestHints(randomLines(random, random.nextInt(2), 20))
            .icon(random.nextInt(5) == 0
                ? MenuIcon.template(ITEMS[random.nextInt(ITEMS.length)], 1 + random.nextInt(MenuLimits.MAX_ITEM_COUNT), random.nextBoolean(), "cost-" + id)
                : randomIcon(random))
            .nativeIcon(random.nextBoolean() ? randomIcon(random) : null)
            .enabled(random.nextBoolean())
            .style(styles[random.nextInt(styles.length)])
            .interactions(interactions)
            .sideActions(sideActions)
            .chestSlot(slot)
            .nativeHidden(slot != null && random.nextInt(5) == 0)
            .build();
    }

    private static MenuIcon randomIcon(SplittableRandom random) {
        return random.nextBoolean()
            ? MenuIcon.item(ITEMS[random.nextInt(ITEMS.length)], 1 + random.nextInt(MenuLimits.MAX_ITEM_COUNT), random.nextBoolean())
            : MenuIcon.sprite(SPRITES[random.nextInt(SPRITES.length)]);
    }

    private static Set<MenuInteraction> pick(SplittableRandom random, List<MenuInteraction> allowed) {
        EnumSet<MenuInteraction> picked = EnumSet.noneOf(MenuInteraction.class);
        for (MenuInteraction interaction : allowed) {
            if (random.nextInt(3) == 0) {
                picked.add(interaction);
            }
        }
        return picked;
    }

    private static List<String> randomLines(SplittableRandom random, int count, int maxCodePoints) {
        List<String> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(randomText(random, maxCodePoints));
        }
        return lines;
    }
}
