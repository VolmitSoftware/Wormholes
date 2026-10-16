package art.arcane.wormholes.modded.client.menu.icon;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MenuIconMaps {
    public static final String SMALL_PREFIX = "small/";
    public static final int LARGE_SIZE = 16;
    public static final int SMALL_SIZE = 12;
    public static final char TRANSPARENT = '.';

    private static final List<MenuIconMap> MAPS = build();

    private MenuIconMaps() {
    }

    public static List<MenuIconMap> all() {
        return MAPS;
    }

    private static List<MenuIconMap> build() {
        List<MenuIconMap> maps = new ArrayList<>(69);
        portal(maps);
        wormhole(maps);
        gateway(maps);
        rtp(maps);
        door(maps);
        pocket(maps);
        settings(maps);
        access(maps);
        cost(maps);
        projection(maps);
        transit(maps);
        atlas(maps);
        rename(maps);
        delete(maps);
        back(maps);
        prev(maps);
        next(maps);
        confirm(maps);
        cancel(maps);
        toggleOn(maps);
        toggleOff(maps);
        sort(maps);
        search(maps);
        player(maps);
        network(maps);
        cosmetics(maps);
        language(maps);
        info(maps);
        warning(maps);
        mirror(maps);
        link(maps);
        rotate(maps);
        favorite(maps);
        reset(maps);
        chevronRight(maps);
        return List.copyOf(maps);
    }

    private static void add(List<MenuIconMap> maps, String name, Map<Character, MenuIconPalette> legend, List<String> large, List<String> small) {
        maps.add(new MenuIconMap(name, legend, large));
        maps.add(new MenuIconMap(SMALL_PREFIX + name, legend, small));
    }

    private static void addSmall(List<MenuIconMap> maps, String name, Map<Character, MenuIconPalette> legend, List<String> small) {
        maps.add(new MenuIconMap(SMALL_PREFIX + name, legend, small));
    }

    private static void portal(List<MenuIconMap> maps) {
        add(maps, "portal", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'O', MenuIconPalette.STONE_3,
                'o', MenuIconPalette.STONE_2,
                'v', MenuIconPalette.VIOLET_1,
                'w', MenuIconPalette.VIOLET_2,
                'x', MenuIconPalette.VIOLET_3,
                'y', MenuIconPalette.VIOLET_4), List.of(
                "................",
                "..KKKKKKKKKKKK..",
                "..KOOOOOOOOOoK..",
                "..KOyxxxxyxxoK..",
                "..KOxxxxyxxxoK..",
                "..KOxxxyxxxyoK..",
                "..KOxxyxxxyxoK..",
                "..KOxyxxxyxwoK..",
                "..KOyxxxyxwwoK..",
                "..KOxxxyxwwwoK..",
                "..KOxxywxwwwoK..",
                "..KOxywwwwwvoK..",
                "..KOwwwwwwvvoK..",
                "..KooooooooooK..",
                "..KKKKKKKKKKKK..",
                "................"), List.of(
                ".KKKKKKKKKK.",
                ".KOOOOOOOoK.",
                ".KOxxxxyxoK.",
                ".KOxxxyxxoK.",
                ".KOxxyxxyoK.",
                ".KOxyxxyxoK.",
                ".KOyxxyxwoK.",
                ".KOxxyxwwoK.",
                ".KOxyxwwvoK.",
                ".KOywwwvvoK.",
                ".KooooooooK.",
                ".KKKKKKKKKK."));
    }

    private static void wormhole(List<MenuIconMap> maps) {
        add(maps, "wormhole", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '1', MenuIconPalette.VIOLET_1,
                '2', MenuIconPalette.VIOLET_2,
                '3', MenuIconPalette.VIOLET_3,
                '4', MenuIconPalette.VIOLET_4), List.of(
                "................",
                "......KKKK......",
                "....KK2222KK....",
                "...K22222222K...",
                "..K2W22333222K..",
                "..K2233333332K..",
                ".K222334443322K.",
                ".K223344444332K.",
                ".K223344114332K.",
                ".K223344114332K.",
                "..K2233444332K..",
                "..K2233333332K..",
                "...K22233322K...",
                "....KK2222KK....",
                "......KKKK......",
                "................"), List.of(
                "....KKKK....",
                "..KK2222KK..",
                ".K22222222K.",
                ".K2W233322K.",
                "K2223343322K",
                "K2233114332K",
                "K2234114432K",
                "K2233444332K",
                ".K22334332K.",
                ".K22233322K.",
                "..KK2222KK..",
                "....KKKK...."));
    }

    private static void gateway(List<MenuIconMap> maps) {
        add(maps, "gateway", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'O', MenuIconPalette.STONE_3,
                'o', MenuIconPalette.STONE_2,
                '1', MenuIconPalette.AQUA_1,
                '2', MenuIconPalette.AQUA_2,
                '3', MenuIconPalette.AQUA_3,
                '4', MenuIconPalette.AQUA_4), List.of(
                "................",
                "......KKKK......",
                "....KKO44oKK....",
                "...KOO3333ooK...",
                "..KO33333433oK..",
                "..KO33334333oK..",
                "..KO33343332oK..",
                "..KO33433322oK..",
                "..KO34333222oK..",
                "..KO43332222oK..",
                "..KO33322221oK..",
                "..KO33222211oK..",
                "..KO32222111oK..",
                "..KooooooooooK..",
                "..KKKKKKKKKKKK..",
                "................"), List.of(
                "....KKKK....",
                "..KKOOooKK..",
                ".KOO3333ooK.",
                ".KO333343oK.",
                ".KO333433oK.",
                ".KO334332oK.",
                ".KO343322oK.",
                ".KO433222oK.",
                ".KO332221oK.",
                ".KO322211oK.",
                ".KooooooooK.",
                ".KKKKKKKKKK."));
    }

    private static void rtp(List<MenuIconMap> maps) {
        add(maps, "rtp", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '2', MenuIconPalette.VIOLET_2,
                '3', MenuIconPalette.VIOLET_3,
                '4', MenuIconPalette.VIOLET_4), List.of(
                "................",
                "..KKKKKKKKKKKK..",
                ".K444444444443K.",
                ".K433333333332K.",
                ".K43WW3333WW32K.",
                ".K43WW3333WW32K.",
                ".K433333333332K.",
                ".K43333WW33332K.",
                ".K43333WW33332K.",
                ".K433333333332K.",
                ".K43WW3333WW32K.",
                ".K43WW3333WW32K.",
                ".K433333333332K.",
                ".K322222222222K.",
                "..KKKKKKKKKKKK..",
                "................"), List.of(
                ".KKKKKKKKKK.",
                "K4444444443K",
                "K4WW3333WW2K",
                "K4WW3333WW2K",
                "K4333333332K",
                "K4333WW3332K",
                "K4333WW3332K",
                "K4333333332K",
                "K4WW3333WW2K",
                "K4WW3333WW2K",
                "K3222222222K",
                ".KKKKKKKKKK."));
    }

    private static void door(List<MenuIconMap> maps) {
        add(maps, "door", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '1', MenuIconPalette.STONE_1,
                '2', MenuIconPalette.STONE_2,
                '3', MenuIconPalette.STONE_3,
                '4', MenuIconPalette.STONE_4,
                'y', MenuIconPalette.VIOLET_4,
                'g', MenuIconPalette.GOLD_3), List.of(
                "................",
                "...KKKKKKKKKK...",
                "...K4444443yK...",
                "...K4333332yK...",
                "...K4311132yK...",
                "...K4312232yK...",
                "...K4312232yK...",
                "...K4312232yK...",
                "...K43333g2yK...",
                "...K4311132yK...",
                "...K4312232yK...",
                "...K4312232yK...",
                "...K4312232yK...",
                "...K3222222yK...",
                "...KKKKKKKKKK...",
                "................"), List.of(
                "..KKKKKKKK..",
                "..K44443yK..",
                "..K43332yK..",
                "..K41132yK..",
                "..K41232yK..",
                "..K433g2yK..",
                "..K41132yK..",
                "..K41232yK..",
                "..K41232yK..",
                "..K43332yK..",
                "..K32222yK..",
                "..KKKKKKKK.."));
    }

    private static void pocket(List<MenuIconMap> maps) {
        add(maps, "pocket", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '1', MenuIconPalette.VIOLET_1,
                '2', MenuIconPalette.VIOLET_2,
                '4', MenuIconPalette.VIOLET_4,
                'g', MenuIconPalette.GREEN_3,
                'h', MenuIconPalette.GREEN_4,
                'p', MenuIconPalette.PARCHMENT_2,
                'q', MenuIconPalette.PARCHMENT_1), List.of(
                "................",
                ".....KKKKKK.....",
                "...KK442222KK...",
                "..K4411111122K..",
                "..K4111111112K..",
                ".K411111111112K.",
                ".K411111111112K.",
                ".K21hggggggg12K.",
                ".K21pppppppp12K.",
                ".K211qqqqqq112K.",
                ".K2111qqqq1112K.",
                "..K2111qq1112K..",
                "..K2211111122K..",
                "...KK222222KK...",
                ".....KKKKKK.....",
                "................"), List.of(
                "....KKKK....",
                "..KK4422KK..",
                ".K44111122K.",
                ".K41111112K.",
                "K4111111112K",
                "K21hggggg12K",
                "K21pppppp12K",
                "K211qqqq112K",
                ".K211qq112K.",
                ".K22111122K.",
                "..KK2222KK..",
                "....KKKK...."));
    }

    private static void settings(List<MenuIconMap> maps) {
        add(maps, "settings", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                ".......KK.......",
                "....KKK44KKK....",
                "...K44K44K33K...",
                "...K44444333K...",
                "....K444333K....",
                "..KK444KK333KK..",
                ".K4444K..K3222K.",
                ".K4443K..K2222K.",
                "..KK333KK222KK..",
                "....K333222K....",
                "...K33322222K...",
                "...K33K22K22K...",
                "....KKK22KKK....",
                ".......KK.......",
                "................"), List.of(
                ".....KK.....",
                "..KKK44KKK..",
                ".K44K44K33K.",
                ".K44443333K.",
                ".KK44KK33KK.",
                "K444K..K322K",
                "K443K..K222K",
                ".KK33KK22KK.",
                ".K33332222K.",
                ".K33K22K22K.",
                "..KKK22KKK..",
                ".....KK....."));
    }

    private static void access(List<MenuIconMap> maps) {
        add(maps, "access", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '4', MenuIconPalette.STEEL_4,
                '3', MenuIconPalette.STEEL_3,
                '2', MenuIconPalette.STEEL_2,
                'H', MenuIconPalette.GOLD_4,
                'g', MenuIconPalette.GOLD_3,
                'd', MenuIconPalette.GOLD_2), List.of(
                "................",
                ".....KKKKKK.....",
                "....K444444K....",
                "...K44KKKK32K...",
                "...K43K..K32K...",
                "...K43K..K32K...",
                "...K43K..K32K...",
                "..KKKKKKKKKKKK..",
                "..KHHHHHHHHHHK..",
                "..KHgggKKgggdK..",
                "..KHgggKKgggdK..",
                "..KHgggKKgggdK..",
                "..KHggggggggdK..",
                "..KddddddddddK..",
                "..KKKKKKKKKKKK..",
                "................"), List.of(
                "...KKKKKK...",
                "..K444444K..",
                "..K4KKKK3K..",
                "..K4K..K3K..",
                "..K4K..K3K..",
                ".KKKKKKKKKK.",
                ".KHHHHHHHHK.",
                ".KHggKKggdK.",
                ".KHggKKggdK.",
                ".KHggggggdK.",
                ".KddddddddK.",
                ".KKKKKKKKKK."));
    }

    private static void cost(List<MenuIconMap> maps) {
        add(maps, "cost", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'H', MenuIconPalette.GOLD_4,
                'g', MenuIconPalette.GOLD_3,
                'd', MenuIconPalette.GOLD_2), List.of(
                "................",
                ".......KKKK.....",
                ".....KKHHHHKK...",
                "....KHHggggHdK..",
                "...KKHggHdggdK..",
                "..KKHgggHdgggdK.",
                "..KKHgggHdgggdK.",
                ".KdKHgggHdgggdK.",
                ".KdKHgggHdgggdK.",
                ".KddKHggHdggdK..",
                ".KddKddggggddK..",
                "..KddKKddddKK...",
                "..KddddKKKKK....",
                "...KKddddKK.....",
                ".....KKKK.......",
                "................"), List.of(
                ".....KKKK...",
                "...KKHHHHKK.",
                "..KHHggggddK",
                ".KKHggHdggdK",
                "KdKHggHdggdK",
                "KdKHggHdggdK",
                "KdKHggHdggdK",
                "KdKHdggggddK",
                "KddKKddddKK.",
                "KddddKKKKK..",
                ".KKddddKK...",
                "...KKKK....."));
    }

    private static void projection(List<MenuIconMap> maps) {
        add(maps, "projection", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '4', MenuIconPalette.STEEL_4,
                '3', MenuIconPalette.STEEL_3,
                'x', MenuIconPalette.VIOLET_3,
                'w', MenuIconPalette.VIOLET_2,
                '1', MenuIconPalette.VIOLET_1), List.of(
                "................",
                "................",
                "................",
                "................",
                ".....KKKKKK.....",
                "...KK444444KK...",
                "..K444xxxx444K..",
                ".K4444xW1x4443K.",
                ".K3333x11w3333K.",
                "..K333xwww333K..",
                "...KK333333KK...",
                ".....KKKKKK.....",
                "................",
                "................",
                "................",
                "................"), List.of(
                "............",
                "............",
                "............",
                "...KKKKKK...",
                ".KK4xxxx4KK.",
                "K444xW1x444K",
                "K333x11w333K",
                ".KK3wwww3KK.",
                "...KKKKKK...",
                "............",
                "............",
                "............"));
    }

    private static void transit(List<MenuIconMap> maps) {
        add(maps, "transit", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '4', MenuIconPalette.STEEL_4,
                '3', MenuIconPalette.STEEL_3,
                'y', MenuIconPalette.VIOLET_4,
                'x', MenuIconPalette.VIOLET_3), List.of(
                "................",
                "......KKKK......",
                "..KK..KyxK......",
                ".K33K.KyxKK.....",
                "..KK..KyxK4K....",
                "......KyxK44K...",
                "..KKKKKyxK444K..",
                ".K4444KyxK4444K.",
                ".K3333KyxK3333K.",
                "..KKKKKyxK333K..",
                "......KyxK33K...",
                "..KK..KyxK3K....",
                ".K33K.KyxKK.....",
                "..KK..KyxK......",
                "......KKKK......",
                "................"), List.of(
                "....KKKK....",
                "....KyxK....",
                "....KyxKK...",
                "....KyxK4K..",
                ".KKKKyxK44K.",
                "K444KyxK444K",
                "K333KyxK333K",
                ".KKKKyxK33K.",
                "....KyxK3K..",
                "....KyxKK...",
                "....KyxK....",
                "....KKKK...."));
    }

    private static void atlas(List<MenuIconMap> maps) {
        add(maps, "atlas", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '1', MenuIconPalette.PARCHMENT_1,
                '2', MenuIconPalette.PARCHMENT_2,
                '3', MenuIconPalette.PARCHMENT_3,
                '4', MenuIconPalette.PARCHMENT_4,
                'w', MenuIconPalette.VIOLET_2), List.of(
                "................",
                "................",
                ".KKKKK....KKKKK.",
                ".K4444KKKK4444K.",
                ".K333322223w3wK.",
                ".K3333222233w3K.",
                ".K333322223w3wK.",
                ".K333322221333K.",
                ".K333322123333K.",
                ".K333312223333K.",
                ".K131322223333K.",
                ".K333322223333K.",
                ".KKKKK2222KKKKK.",
                "......KKKK......",
                "................",
                "................"), List.of(
                "............",
                "KKKK....KKKK",
                "K444KKKK444K",
                "K3332222w3wK",
                "K33322223w3K",
                "K3332222w3wK",
                "K3332221333K",
                "K3332122333K",
                "K1312222333K",
                "K3332222333K",
                "KKKK2222KKKK",
                "....KKKK...."));
    }

    private static void rename(List<MenuIconMap> maps) {
        add(maps, "rename", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'G', MenuIconPalette.STEEL_1,
                'P', MenuIconPalette.PARCHMENT_3,
                '4', MenuIconPalette.GOLD_4,
                '3', MenuIconPalette.GOLD_3,
                '2', MenuIconPalette.GOLD_2,
                'F', MenuIconPalette.STEEL_3,
                'E', MenuIconPalette.RED_4), List.of(
                "................",
                "............KK..",
                "...........KEEK.",
                "..........KEEEK.",
                ".........KFFEK..",
                "........K43FK...",
                ".......K432K....",
                "......K432K.....",
                ".....K432K......",
                "....K432K.......",
                "...K432K........",
                "..KPP2K.........",
                ".KGPPK..........",
                ".KGGK...........",
                "..KK............",
                "................"), List.of(
                ".........KK.",
                "........KEEK",
                ".......KEEEK",
                "......KFFEK.",
                ".....K43FK..",
                "....K432K...",
                "...K432K....",
                "..K432K.....",
                ".KP32K......",
                "KGPPK.......",
                "KGGK........",
                ".KK........."));
    }

    private static void delete(List<MenuIconMap> maps) {
        add(maps, "delete", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '1', MenuIconPalette.RED_1,
                '2', MenuIconPalette.RED_2,
                '3', MenuIconPalette.RED_3,
                '4', MenuIconPalette.RED_4), List.of(
                "................",
                "......KKKK......",
                "......K..K......",
                "..KKKKKKKKKKKK..",
                "..K4444444443K..",
                "..KKKKKKKKKKKK..",
                "...K22222221K...",
                "...K43233231K...",
                "...K43233231K...",
                "...K43233231K...",
                "...K43233231K...",
                "...K43233231K...",
                "...K43233231K...",
                "...K32222211K...",
                "...KKKKKKKKKK...",
                "................"), List.of(
                "....KKKK....",
                "KKKKKKKKKKKK",
                "K4444444443K",
                "KKKKKKKKKKKK",
                ".K22222221K.",
                ".K43233231K.",
                ".K43233231K.",
                ".K43233231K.",
                ".K43233231K.",
                ".K43233231K.",
                ".K32222211K.",
                ".KKKKKKKKKK."));
    }

    private static void back(List<MenuIconMap> maps) {
        add(maps, "back", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "................",
                "................",
                ".....K..........",
                "....K4K.........",
                "...K44KKKKKKKK..",
                "..K44444444443K.",
                ".K444444444443K.",
                ".K333333333332K.",
                "..K33333333322K.",
                "...K33KKKKKKKK..",
                "....K3K.........",
                ".....K..........",
                "................",
                "................",
                "................"), List.of(
                "............",
                "............",
                "....KK......",
                "...K4K......",
                "..K44KKKKKK.",
                ".K444444443K",
                ".K333333332K",
                "..K33KKKKKK.",
                "...K3K......",
                "....KK......",
                "............",
                "............"));
    }

    private static void prev(List<MenuIconMap> maps) {
        add(maps, "prev", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "................",
                ".........KKK....",
                "........K444K...",
                ".......K444K....",
                "......K444K.....",
                ".....K444K......",
                "....K444K.......",
                "....K333K.......",
                ".....K333K......",
                "......K333K.....",
                ".......K333K....",
                "........K333K...",
                ".........KKK....",
                "................",
                "................"), List.of(
                "............",
                ".......KKK..",
                "......K444K.",
                ".....K444K..",
                "....K444K...",
                "...K444K....",
                "...K333K....",
                "....K333K...",
                ".....K333K..",
                "......K333K.",
                ".......KKK..",
                "............"));
    }

    private static void next(List<MenuIconMap> maps) {
        add(maps, "next", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "................",
                "....KKK.........",
                "...K444K........",
                "....K444K.......",
                ".....K444K......",
                "......K444K.....",
                ".......K444K....",
                ".......K333K....",
                "......K333K.....",
                ".....K333K......",
                "....K333K.......",
                "...K333K........",
                "....KKK.........",
                "................",
                "................"), List.of(
                "............",
                "..KKK.......",
                ".K444K......",
                "..K444K.....",
                "...K444K....",
                "....K444K...",
                "....K333K...",
                "...K333K....",
                "..K333K.....",
                ".K333K......",
                "..KKK.......",
                "............"));
    }

    private static void confirm(List<MenuIconMap> maps) {
        add(maps, "confirm", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.GREEN_2,
                '3', MenuIconPalette.GREEN_3,
                '4', MenuIconPalette.GREEN_4), List.of(
                "................",
                "................",
                "................",
                "............KK..",
                "...........K43K.",
                "..........K42K..",
                ".........K42K...",
                "..KK....K42K....",
                ".K34K..K42K.....",
                "..K24KK42K......",
                "...K2442K.......",
                "....K22K........",
                ".....KK.........",
                "................",
                "................",
                "................"), List.of(
                "............",
                ".........KK.",
                "........K43K",
                ".......K42K.",
                ".KK...K42K..",
                "K34K.K42K...",
                ".K24K42K....",
                "..K242K.....",
                "...K2K......",
                "....K.......",
                "............",
                "............"));
    }

    private static void cancel(List<MenuIconMap> maps) {
        add(maps, "cancel", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.RED_2,
                '3', MenuIconPalette.RED_3,
                '4', MenuIconPalette.RED_4), List.of(
                "................",
                "................",
                "................",
                "....KK....KK....",
                "...K34K..K43K...",
                "....K24KK42K....",
                ".....K2442K.....",
                "......K33K......",
                ".....K4224K.....",
                "....K42KK24K....",
                "...K42K..K24K...",
                "..K32K....K23K..",
                "...KK......KK...",
                "................",
                "................",
                "................"), List.of(
                "............",
                "..KK....KK..",
                ".K34K..K43K.",
                "..K24KK42K..",
                "...K2442K...",
                "....K33K....",
                "...K4224K...",
                "..K42KK24K..",
                ".K42K..K24K.",
                "K32K....K23K",
                ".KK......KK.",
                "............"));
    }

    private static void toggleOn(List<MenuIconMap> maps) {
        add(maps, "toggle_on", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '2', MenuIconPalette.GREEN_2,
                '3', MenuIconPalette.GREEN_3,
                '4', MenuIconPalette.GREEN_4,
                's', MenuIconPalette.STEEL_4), List.of(
                "................",
                "................",
                "................",
                "................",
                "...KKKKKKKKKK...",
                "..K4444KWWWWWK..",
                ".K33333KWWWWWsK.",
                ".K33333KWWWWWsK.",
                ".K33333KWWWWWsK.",
                ".K33333KWWWWWsK.",
                "..K2222KsssssK..",
                "...KKKKKKKKKK...",
                "................",
                "................",
                "................",
                "................"), List.of(
                "............",
                "............",
                "............",
                "..KKKKKKKK..",
                ".K444KWWWWK.",
                "K3333KWWWWsK",
                "K3333KWWWWsK",
                ".K222KssssK.",
                "..KKKKKKKK..",
                "............",
                "............",
                "............"));
    }

    private static void toggleOff(List<MenuIconMap> maps) {
        add(maps, "toggle_off", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'h', MenuIconPalette.STEEL_4,
                'k', MenuIconPalette.STEEL_3,
                'm', MenuIconPalette.STEEL_2,
                '1', MenuIconPalette.STEEL_1), List.of(
                "................",
                "................",
                "................",
                "................",
                "...KKKKKKKKKK...",
                "..KhhhhhK1111K..",
                ".KhkkkkmK11111K.",
                ".KhkkkkmK11111K.",
                ".KhkkkkmK11111K.",
                ".KhkkkkmK11111K.",
                "..KmmmmmK1111K..",
                "...KKKKKKKKKK...",
                "................",
                "................",
                "................",
                "................"), List.of(
                "............",
                "............",
                "............",
                "..KKKKKKKK..",
                ".KhhhhK111K.",
                "KhkkkmK1111K",
                "KhkkkmK1111K",
                ".KmmmmK111K.",
                "..KKKKKKKK..",
                "............",
                "............",
                "............"));
    }

    private static void sort(List<MenuIconMap> maps) {
        add(maps, "sort", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "...........K....",
                "..KKKKKK..K4K...",
                ".K444444K.K4K...",
                ".K333333K.K3K...",
                "..KKKKKK..K3K...",
                "..KKKK....K3K...",
                ".K4444K...K3K...",
                ".K3333K..KK3KK..",
                "..KKKK..K33333K.",
                "..KK.....K333K..",
                ".K44K.....K3K...",
                ".K33K......K....",
                "..KK............",
                "................",
                "................"), List.of(
                ".KKKKK...K..",
                "K44444K.K4K.",
                "K33333K.K3K.",
                ".KKKKK..K3K.",
                ".KKK....K3K.",
                "K444K...K3K.",
                "K333K...K3K.",
                ".KKK...K333K",
                ".K......K3K.",
                "K4K......K..",
                "K3K.........",
                ".K.........."));
    }

    private static void search(List<MenuIconMap> maps) {
        add(maps, "search", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4,
                'y', MenuIconPalette.VIOLET_4,
                'x', MenuIconPalette.VIOLET_3), List.of(
                "................",
                ".....KKK........",
                "...KK444KK......",
                "..K4444433K.....",
                "..K4Wyyy33K.....",
                ".K44yyyxx22K....",
                ".K44yyxxx22K....",
                ".K44yxxxx22K....",
                "..K33xxx22K.....",
                "..K3322222K.....",
                "...KK222KK2K....",
                ".....KKK..K2K...",
                "...........K2K..",
                "............KK..",
                "................",
                "................"), List.of(
                "....KK......",
                "..KK44KK....",
                ".K444433K...",
                ".K4Wyyx3K...",
                "K44yyxx22K..",
                "K44yxxx22K..",
                ".K3xxxx2K...",
                ".K332222K...",
                "..KK22KK2K..",
                "....KK..K2K.",
                ".........KK.",
                "............"));
    }

    private static void player(List<MenuIconMap> maps) {
        add(maps, "player", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '4', MenuIconPalette.STEEL_4,
                '3', MenuIconPalette.STEEL_3,
                '2', MenuIconPalette.STEEL_2,
                'y', MenuIconPalette.VIOLET_4,
                'x', MenuIconPalette.VIOLET_3,
                'w', MenuIconPalette.VIOLET_2), List.of(
                "................",
                "......KKKK......",
                ".....K4444K.....",
                "....K444443K....",
                "....K444443K....",
                "....K444332K....",
                ".....K3322K.....",
                "......KKKK......",
                "....KKKKKKKK....",
                "...KyyyyyyyyK...",
                "..KyxxxxxxxxwK..",
                ".KyxxxxxxxxxxwK.",
                ".KxxxxxxxxxxxwK.",
                ".KwwwwwwwwwwwwK.",
                ".KKKKKKKKKKKKKK.",
                "................"), List.of(
                "....KKKK....",
                "...K4444K...",
                "...K4443K...",
                "...K4332K...",
                "....KKKK....",
                "..KKKKKKKK..",
                ".KyyyyyyyyK.",
                "KyxxxxxxxxwK",
                "KyxxxxxxxxwK",
                "KxxxxxxxxxwK",
                "KwwwwwwwwwwK",
                ".KKKKKKKKKK."));
    }

    private static void network(List<MenuIconMap> maps) {
        add(maps, "network", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '1', MenuIconPalette.STEEL_1,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4,
                'A', MenuIconPalette.AQUA_4,
                'L', MenuIconPalette.AQUA_3), List.of(
                "................",
                ".KKKKKKKKKKKKKK.",
                ".K444444444443K.",
                ".K411111333A32K.",
                ".K322222222222K.",
                ".KKKKKKKKKKKKKK.",
                ".K444444444443K.",
                ".K411111333L32K.",
                ".K322222222222K.",
                ".KKKKKKKKKKKKKK.",
                ".K444444444443K.",
                ".K411111333L32K.",
                ".K322222222222K.",
                ".KKKKKKKKKKKKKK.",
                ".......KK.......",
                "................"), List.of(
                ".KKKKKKKKKK.",
                "K4444444443K",
                "K41111333A2K",
                "KKKKKKKKKKKK",
                "K4444444443K",
                "K41111333L2K",
                "KKKKKKKKKKKK",
                "K4444444443K",
                "K41111333L2K",
                ".KKKKKKKKKK.",
                ".....KK.....",
                "..KKKKKKKK.."));
    }

    private static void cosmetics(List<MenuIconMap> maps) {
        add(maps, "cosmetics", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '3', MenuIconPalette.VIOLET_3,
                '4', MenuIconPalette.VIOLET_4), List.of(
                "................",
                "..........K.....",
                ".........K3K....",
                ".........K3K....",
                ".......KK444KK..",
                "......K334W433K.",
                ".......KK444KK..",
                ".........K3K....",
                "....K....K3K....",
                "...K3K....K.....",
                "..K343K.........",
                "...K3K.....K....",
                "....K.....K4K...",
                "...........K....",
                "................",
                "................"), List.of(
                ".......K....",
                "......K3K...",
                "......K3K...",
                "....KK444KK.",
                "...K334W433K",
                "....KK444KK.",
                "......K3K...",
                "..K...K3K...",
                ".K3K...K.K..",
                "K343K...K4K.",
                ".K3K.....K..",
                "..K........."));
    }

    private static void language(List<MenuIconMap> maps) {
        add(maps, "language", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.AQUA_2,
                '3', MenuIconPalette.AQUA_3,
                '4', MenuIconPalette.AQUA_4), List.of(
                "................",
                "......KKKK......",
                "....KK4434KK....",
                "...K44444444K...",
                "..K3343433422K..",
                "..K3343432422K..",
                ".K333434224222K.",
                ".K444444444444K.",
                ".K334424224422K.",
                ".K333424224222K.",
                "..K3242422422K..",
                "..K4444444444K..",
                "...K24242242K...",
                "....KK4424KK....",
                "......KKKK......",
                "................"), List.of(
                "....KKKK....",
                "..KK4434KK..",
                ".K34443442K.",
                ".K34343242K.",
                "K3343422422K",
                "K4444444444K",
                "K3342422422K",
                "K3342422422K",
                ".K24242242K.",
                ".K24442442K.",
                "..KK4424KK..",
                "....KKKK...."));
    }

    private static void info(List<MenuIconMap> maps) {
        add(maps, "info", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "......KKKK......",
                "....KK4444KK....",
                "...K443KK344K...",
                "..K4333KK3332K..",
                "..K4333333332K..",
                ".K43333KK33332K.",
                ".K43333KK33332K.",
                ".K43333KK33332K.",
                ".K43333KK33332K.",
                "..K4333KK3332K..",
                "..K4333KK3332K..",
                "...K22333322K...",
                "....KK2222KK....",
                "......KKKK......",
                "................"), List.of(
                "....KKKK....",
                "..KK4444KK..",
                ".K443KK342K.",
                ".K43333332K.",
                "K4333KK3332K",
                "K4333KK3332K",
                "K4333KK3332K",
                "K4333KK3332K",
                ".K433KK332K.",
                ".K22333322K.",
                "..KK2222KK..",
                "....KKKK...."));
    }

    private static void warning(List<MenuIconMap> maps) {
        add(maps, "warning", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '2', MenuIconPalette.RED_2,
                '3', MenuIconPalette.RED_3), List.of(
                "................",
                ".......KK.......",
                ".......KK.......",
                "......K32K......",
                "......K32K......",
                ".....K3WW2K.....",
                ".....K3KK2K.....",
                "....K3WKKW2K....",
                "....K3WKKW2K....",
                "...K3WWKKWW2K...",
                "...K3WWKKWW2K...",
                "..K3WWWWWWWW2K..",
                "..K3WWWKKWWW2K..",
                ".K333333222222K.",
                ".KKKKKKKKKKKKKK.",
                "................"), List.of(
                ".....KK.....",
                ".....KK.....",
                "....K32K....",
                "....K32K....",
                "...K3KK2K...",
                "...K3KK2K...",
                "..K3WKKW2K..",
                "..K3WKKW2K..",
                ".K3WWWWWW2K.",
                ".K3WWKKWW2K.",
                "K3333322222K",
                "KKKKKKKKKKKK"));
    }

    private static void mirror(List<MenuIconMap> maps) {
        add(maps, "mirror", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4,
                'a', MenuIconPalette.AQUA_3,
                'b', MenuIconPalette.AQUA_4), List.of(
                "................",
                "......KKKK......",
                ".....K4444K.....",
                "....K4bbbb3K....",
                "...K4bbWbbb3K...",
                "...K4bWbbWb3K...",
                "..K4bWbbWbbb3K..",
                "..K4bbbWbbbb2K..",
                "..K4bbWbbbba2K..",
                "...K3bbbbba2K...",
                "...K3bbbbaa2K...",
                "....K2bbaa2K....",
                ".....K2222K.....",
                "....K333222K....",
                ".....KKKKKK.....",
                "................"), List.of(
                "....KKKK....",
                "...K4444K...",
                "..K4bbbb3K..",
                "..K4bbWb3K..",
                ".K4bbWbbb3K.",
                ".K4bWbbWb3K.",
                ".K4bbbWba2K.",
                "..K3bbba2K..",
                "..K3bbaa2K..",
                "...K2222K...",
                "..K333222K..",
                "...KKKKKK..."));
    }

    private static void link(List<MenuIconMap> maps) {
        add(maps, "link", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "........KKKK....",
                ".......K4443K...",
                "......K432233K..",
                ".....K432KK333K.",
                "....KKKKK..K42K.",
                "...K4K43K..K42K.",
                "..K43K333KK432K.",
                ".K432KK333K32K..",
                ".K42K..K32K2K...",
                ".K42K..KKKKK....",
                ".K333KK432K.....",
                "..K334432K......",
                "...K3222K.......",
                "....KKKK........",
                "................"), List.of(
                "......KKKK..",
                ".....K4443K.",
                "....K432233K",
                "....K42KK42K",
                "..KKKK2KK42K",
                ".K44K3K4432K",
                "K4322K3K22K.",
                "K42KK4KKKK..",
                "K42KK42K....",
                "K334432K....",
                ".K3222K.....",
                "..KKKK......"));
    }

    private static void rotate(List<MenuIconMap> maps) {
        add(maps, "rotate", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                "........K.......",
                ".......K3K......",
                "......KK43K.....",
                ".....K44333K....",
                "....K422332K....",
                "...K42KK42K.....",
                "..K42K.K3K......",
                "..K42K..K..KK...",
                "..K42K....K43K..",
                "..K32K....K42K..",
                "...K33KKKK42K...",
                "....K344442K....",
                ".....K3222K.....",
                "......KKKK......",
                "................"), List.of(
                "......K.....",
                ".....K3K....",
                "....KK43K...",
                "...K44333K..",
                "..K432332K..",
                ".K432K42K...",
                ".K42KK3KKK..",
                ".K42K.KK43K.",
                ".K333KK432K.",
                "..K334432K..",
                "...K3222K...",
                "....KKKK...."));
    }

    private static void favorite(List<MenuIconMap> maps) {
        add(maps, "favorite", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '2', MenuIconPalette.GOLD_2,
                '3', MenuIconPalette.GOLD_3,
                '4', MenuIconPalette.GOLD_4), List.of(
                "................",
                ".......KK.......",
                "......K43K......",
                "......K42K......",
                ".....K4333K.....",
                "..KKKK4332KKKK..",
                ".K344433334443K.",
                "..K3333333332K..",
                "...K33333332K...",
                "....K433332K....",
                "...K43322333K...",
                "...K432KK332K...",
                "..K432K..K333K..",
                "..K32K....K32K..",
                "...KK......KK...",
                "................"), List.of(
                ".....KK.....",
                "....K43K....",
                "....K42K....",
                ".KKK4333KKK.",
                "K3443333443K",
                ".K33333332K.",
                "..K433332K..",
                "..K432232K..",
                ".K432KK333K.",
                ".K32K..K32K.",
                "..KK....KK..",
                "............"));
    }

    private static void reset(List<MenuIconMap> maps) {
        add(maps, "reset", Map.of(
                'K', MenuIconPalette.OUTLINE,
                'W', MenuIconPalette.WHITE,
                '2', MenuIconPalette.STEEL_2,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "................",
                ".......K........",
                "......K3K.......",
                ".....K42KK......",
                "....K43343K.....",
                "....K333223K....",
                ".....K32KK33K...",
                "......KKK.K43K..",
                "...KK.KWWKK42K..",
                "..K43KKWWKK42K..",
                "..K32K.KK.K42K..",
                "...K33KKKK42K...",
                "....K344442K....",
                ".....K3222K.....",
                "......KKKK......",
                "................"), List.of(
                ".....K......",
                "....K3K.....",
                "...K42KK....",
                "..K43343K...",
                "..K333233K..",
                "...K32K333K.",
                "..KKK3KK42K.",
                ".K43KKWK42K.",
                ".K333KK432K.",
                "..K334432K..",
                "...K3222K...",
                "....KKKK...."));
    }

    private static void chevronRight(List<MenuIconMap> maps) {
        addSmall(maps, "chevron_right", Map.of(
                'K', MenuIconPalette.OUTLINE,
                '3', MenuIconPalette.STEEL_3,
                '4', MenuIconPalette.STEEL_4), List.of(
                "............",
                "....KK......",
                "....K4K.....",
                "....K44K....",
                ".....K44K...",
                "......K4K...",
                ".....K33K...",
                "....K33K....",
                "....K3K.....",
                "....KK......",
                "............",
                "............"));
    }

    public record MenuIconMap(String name, Map<Character, MenuIconPalette> legend, List<String> rows) {
        public int size() {
            return rows.size();
        }

        public boolean small() {
            return name.startsWith(SMALL_PREFIX);
        }

        public char cell(int x, int y) {
            return rows.get(y).charAt(x);
        }

        public int argb(int x, int y) {
            char cell = cell(x, y);
            if (cell == TRANSPARENT) {
                return 0;
            }
            MenuIconPalette color = legend.get(cell);
            if (color == null) {
                throw new IllegalStateException("Icon " + name + " uses undefined legend key '" + cell + "' at " + x + "," + y);
            }
            return color.argb();
        }

        public BufferedImage render() {
            int size = size();
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    image.setRGB(x, y, argb(x, y));
                }
            }
            return image;
        }
    }
}
