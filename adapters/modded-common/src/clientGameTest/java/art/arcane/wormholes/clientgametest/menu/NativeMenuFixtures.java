package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.LookCaptureElement;
import art.arcane.wormholes.menu.MenuChrome;
import art.arcane.wormholes.menu.MenuConfirmation;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NativeMenuFixtures {
    public static final String CONTROLS = "fixture.controls";
    public static final String LIST = "fixture.list";
    public static final String CONFIRM = "fixture.confirm";
    public static final String FEEDBACK = "fixture.feedback";
    public static final String SETTINGS = "fixture.settings30";
    public static final String RENAME = "fixture.rename";
    public static final String PICKER = "fixture.picker";
    public static final String LONG_ENTRY = "entry-long";

    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> PRESS_ALL = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);
    private static final Set<MenuInteraction> NUMBER = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);

    private NativeMenuFixtures() {
    }

    public static MenuChrome chrome(String pageLabel) {
        return new MenuChrome("Back", "Close", "Apply", "Confirm", "Cancel", "Previous page", "Next page", pageLabel, "Use held item",
            "Aim", "The server did not answer.");
    }

    public static MenuScreen controls(long revision, boolean projection, int mode, double range, String name) {
        List<MenuElement> elements = List.of(
            new ButtonElement(common("open-settings", "§fSettings...", MenuIcon.sprite("settings"))
                .description(List.of("Open the portal settings.")).interactions(PRESS).build()),
            new ToggleElement(common("toggle-projection", projection ? "Projection: ON" : "Projection: OFF", MenuIcon.sprite("projection"))
                .description(List.of("Shows the destination through the portal.")).interactions(PRESS).build(), projection),
            new CycleElement(common("cycle-mode", "Mode", MenuIcon.sprite("portal"))
                .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY)).build(),
                List.of("Portal", "Wormhole", "Gateway"), mode),
            new NumberElement(common("number-range", "Activation range", MenuIcon.item("minecraft:spyglass")).interactions(NUMBER).build(),
                range, 0.0D, 64.0D, 1.0D, 8.0D, (int) range + " blocks"),
            new TextElement(common("text-name", "Name", MenuIcon.sprite("rename")).interactions(TEXT).build(), name, 32, "Portal name",
                List.of(), ChestReopen.ALWAYS, null),
            new ListEntryElement(common("entry-linked", "Linked: Harbor", MenuIcon.sprite("wormhole"))
                .description(List.of("§7world 120, 64, -30"))
                .interactions(PRESS_ALL)
                .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "Favorite", MenuIcon.sprite("small/favorite")),
                    new MenuSideAction(MenuInteraction.TERTIARY, "Remove", MenuIcon.sprite("small/delete"))))
                .build(), true),
            new LabelElement(common("label-info", "Projection shows the destination.", MenuIcon.sprite("info"))
                .description(List.of("Turn it off to save bandwidth.")).build()),
            new ItemCaptureElement(common("capture-item", "Travel cost item", MenuIcon.item("minecraft:diamond", 3, true))
                .interactions(PRESS).build(), List.of()),
            new LookCaptureElement(common("capture-look", "Orientation", MenuIcon.sprite("rotate")).interactions(PRESS).build()),
            new ButtonElement(common("button-disabled", "Delete Portal...", MenuIcon.item("minecraft:tnt"))
                .description(List.of("Locked while the portal is linked.")).enabled(false).style(MenuStyle.DESTRUCTIVE)
                .interactions(PRESS).build()),
            new ButtonElement(common("primary-done", "Save", MenuIcon.sprite("confirm")).style(MenuStyle.PRIMARY).interactions(PRESS).build()));
        return MenuScreen.builder(CONTROLS)
            .title("Portal")
            .context("Garden Arch - Wormhole")
            .revision(revision)
            .elements(elements)
            .canGoBack(true)
            .chrome(chrome(""))
            .build();
    }

    public static MenuScreen list(long revision, int page) {
        List<MenuElement> elements = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            String id = index == 3 ? LONG_ENTRY : "entry-" + index;
            String label = index == 3 ? "The Extremely Long Destination Name That Never Ends Near The Old Spawn" : "Destination " + (index + 1 + page * 14);
            MenuElementCommon.Builder builder = common(id, label, index % 3 == 0 ? MenuIcon.sprite("portal") : MenuIcon.sprite("wormhole"))
                .description(List.of("§7overworld " + (index * 37) + ", 64, " + (-index * 11)))
                .interactions(PRESS_ALL);
            if (index == 1) {
                builder.sideActions(List.of(new MenuSideAction(MenuInteraction.PRIMARY, "Link", MenuIcon.sprite("small/link")),
                    new MenuSideAction(MenuInteraction.SECONDARY, "Favorite", MenuIcon.sprite("small/favorite")),
                    new MenuSideAction(MenuInteraction.TERTIARY, "Remove", MenuIcon.sprite("small/delete"))));
            }
            elements.add(new ListEntryElement(builder.build(), index == 2));
        }
        return MenuScreen.builder(LIST)
            .title("Destinations")
            .context("Garden Arch > Destinations")
            .revision(revision)
            .elements(elements)
            .paging(new MenuPaging(page, 3))
            .canGoBack(true)
            .chrome(chrome("Page " + (page + 1) + " / 3"))
            .build();
    }

    public static MenuScreen confirm(long revision) {
        List<MenuElement> elements = List.of(
            new LabelElement(common("label-portal", "Garden Arch", MenuIcon.sprite("portal")).description(List.of("Wormhole")).build()),
            new ButtonElement(common("confirm-delete", "Delete", MenuIcon.sprite("delete")).style(MenuStyle.DESTRUCTIVE).interactions(PRESS).build()),
            new ButtonElement(common("cancel-delete", "Cancel", MenuIcon.sprite("cancel")).interactions(PRESS).build()));
        return MenuScreen.builder(CONFIRM)
            .title("Delete Portal")
            .revision(revision)
            .elements(elements)
            .canGoBack(true)
            .confirmation(new MenuConfirmation(List.of("§fDelete Garden Arch?",
                "This removes the portal and unlinks 2 portals that point to it.", "§cThis cannot be undone."),
                "confirm-delete", "cancel-delete"))
            .chrome(chrome(""))
            .build();
    }

    public static MenuScreen feedback(long revision, MenuFeedback feedback) {
        List<MenuElement> elements = List.of(
            new ToggleElement(common("toggle-projection", "Projection: ON", MenuIcon.sprite("projection")).interactions(PRESS).build(), true),
            new ButtonElement(common("open-cost", "Travel cost...", MenuIcon.sprite("cost")).interactions(PRESS).build()));
        return MenuScreen.builder(FEEDBACK)
            .title("Projection")
            .revision(revision)
            .elements(elements)
            .canGoBack(true)
            .feedback(feedback)
            .chrome(chrome(""))
            .build();
    }

    public static MenuScreen settings(long revision, String removed, String renamed) {
        List<MenuElement> elements = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            String id = "s-" + index;
            if (id.equals(removed)) {
                continue;
            }
            String label = id.equals(renamed) ? "Renamed setting " + index : settingLabel(index);
            elements.add(switch (index % 5) {
                case 0 -> new ToggleElement(common(id, label, MenuIcon.sprite("toggle_on")).interactions(PRESS).build(), index % 2 == 0);
                case 1 -> new NumberElement(common(id, label, MenuIcon.sprite("transit")).interactions(NUMBER).build(), index, 0.0D, 100.0D,
                    1.0D, 10.0D, index + "%");
                case 2 -> new CycleElement(common(id, label, MenuIcon.sprite("projection")).interactions(PRESS).build(),
                    List.of("Low", "Medium", "High"), index % 3);
                case 3 -> new ButtonElement(common(id, label, MenuIcon.item("minecraft:ender_eye", 1 + index % 4, false)).interactions(PRESS).build());
                default -> new LabelElement(common(id, label, MenuIcon.sprite("info")).build());
            });
        }
        return MenuScreen.builder(SETTINGS)
            .title("Portal Settings With A Very Long Title That Needs Clipping On Narrow Screens")
            .context("Garden Arch > Settings > Transit > Gravity > Advanced Options For Travellers")
            .revision(revision)
            .elements(elements)
            .canGoBack(true)
            .chrome(chrome(""))
            .build();
    }

    public static MenuScreen rename(long revision, String name) {
        List<MenuElement> elements = List.of(
            new TextElement(common("set-name", "Name", MenuIcon.sprite("rename")).description(List.of("Up to 24 characters.")).interactions(TEXT).build(),
                name, 24, "Portal name", List.of(), ChestReopen.ALWAYS, null));
        return MenuScreen.builder(RENAME)
            .title("Rename")
            .context("Garden Arch")
            .revision(revision)
            .elements(elements)
            .canGoBack(false)
            .chrome(chrome(""))
            .build();
    }

    public static MenuScreen picker(long revision, int index) {
        List<String> colors = List.of("White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink", "Gray", "Light Gray", "Cyan");
        List<MenuElement> elements = List.of(
            new CycleElement(common("cycle-color", "Blackout color", MenuIcon.item("minecraft:white_concrete"))
                .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SELECT)).build(), colors, index),
            new ToggleElement(common("toggle-blackout", "Blackout: ON", MenuIcon.sprite("toggle_on")).interactions(PRESS).build(), true));
        return MenuScreen.builder(PICKER)
            .title("Cosmetics")
            .revision(revision)
            .elements(elements)
            .canGoBack(true)
            .chrome(chrome(""))
            .build();
    }

    private static String settingLabel(int index) {
        return switch (index % 4) {
            case 0 -> "Setting " + index;
            case 1 -> "Network view entity interval in ticks " + index;
            case 2 -> "Quality " + index;
            default -> "Gravity rotation smoothing for travellers " + index;
        };
    }

    private static MenuElementCommon.Builder common(String id, String label, MenuIcon icon) {
        return MenuElementCommon.builder(id).label(label).icon(icon);
    }
}
