package art.arcane.wormholes.menu;

public sealed interface MenuValue permits MenuValue.Index, MenuValue.Number, MenuValue.Text {
    static Index index(int index) {
        return new Index(index);
    }

    static Number number(double number) {
        return new Number(number);
    }

    static Text text(String text) {
        return new Text(text);
    }

    record Index(int index) implements MenuValue {
    }

    record Number(double number) implements MenuValue {
        public Number {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("number value must be finite");
            }
        }
    }

    record Text(String text) implements MenuValue {
        public Text {
            text = MenuText.text(text, "text value");
        }
    }
}
