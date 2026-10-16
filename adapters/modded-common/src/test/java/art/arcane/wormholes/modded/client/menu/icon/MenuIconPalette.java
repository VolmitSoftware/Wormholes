package art.arcane.wormholes.modded.client.menu.icon;

public enum MenuIconPalette {
    OUTLINE(0x1A1424),
    WHITE(0xFFFFFF),
    STEEL_1(0x3C3C48),
    STEEL_2(0x6E6E7E),
    STEEL_3(0xA9A9B8),
    STEEL_4(0xE4E4EE),
    VIOLET_1(0x3D1670),
    VIOLET_2(0x7A33D6),
    VIOLET_3(0xB969FF),
    VIOLET_4(0xE2C8FF),
    STONE_1(0x241A33),
    STONE_2(0x3B2C52),
    STONE_3(0x5A4778),
    STONE_4(0x7E6AA0),
    GOLD_1(0x6A3F00),
    GOLD_2(0xB57400),
    GOLD_3(0xFFB01E),
    GOLD_4(0xFFE69A),
    RED_1(0x5E1010),
    RED_2(0xA82424),
    RED_3(0xE84A4A),
    RED_4(0xFFA3A3),
    GREEN_1(0x0F4A1E),
    GREEN_2(0x1F8A38),
    GREEN_3(0x46CC5C),
    GREEN_4(0xB2F2BC),
    AQUA_1(0x0E3B48),
    AQUA_2(0x1E7C8E),
    AQUA_3(0x45C4DA),
    AQUA_4(0xB8F2FB),
    PARCHMENT_1(0x6E4E26),
    PARCHMENT_2(0xB48E55),
    PARCHMENT_3(0xE3CB97),
    PARCHMENT_4(0xFFF2D2);

    private final int rgb;

    MenuIconPalette(int rgb) {
        this.rgb = rgb;
    }

    public int rgb() {
        return rgb;
    }

    public int argb() {
        return 0xFF000000 | rgb;
    }
}
