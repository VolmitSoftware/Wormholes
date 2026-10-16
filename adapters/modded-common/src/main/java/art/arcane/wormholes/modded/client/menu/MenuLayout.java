package art.arcane.wormholes.modded.client.menu;

public final class MenuLayout {
    public static final int HEADER_H = 33;
    public static final int HEADER_CTX_H = 38;
    public static final int TITLE_Y = 12;
    public static final int CONTEXT_Y = 25;
    public static final int FEEDBACK_H = 12;
    public static final int FOOTER_H = 33;
    public static final int FOOTER_BUTTON_INSET = 27;
    public static final int MARGIN = 8;
    public static final int MARGIN_MIN = 5;
    public static final int SCROLLBAR_RESERVE = 14;
    public static final int ROW_1 = 24;
    public static final int ROW_2 = 28;
    public static final int BTN_H = 20;
    public static final int PAD = 2;
    public static final int GAP_S = 4;
    public static final int GAP_M = 8;
    public static final int ICON = 16;
    public static final int SMALL_ICON = 12;
    public static final int LINE_H = 9;
    public static final int ROW_W_SETTINGS = 310;
    public static final int ROW_W_LIST = 280;
    public static final int TWO_COLUMN_MIN_W = 416;
    public static final int TWO_COLUMN_MIN_ROWS = 8;
    public static final int COLUMN_W = 200;
    public static final int COLUMN_GAP = 8;
    public static final int SIDE_BUTTON = 20;
    public static final int TOGGLE_W = 60;
    public static final int CYCLE_W = 120;
    public static final int CAPTURE_W = 120;
    public static final int CHOICE_MIN_W = 60;
    public static final int SLIDER_W = 100;
    public static final int SLIDER_MIN_W = 50;
    public static final int STEP_W = 20;
    public static final int CONTROL_GAP = 2;
    public static final int TEXT_MIN_W = 60;
    public static final int LABEL_MIN = 48;
    public static final int FOOTER_SINGLE_W = 200;
    public static final int FOOTER_PAIR_W = 150;
    public static final int FOOTER_SIDE_W = 98;
    public static final int CONFIRM_BUTTON_W = 150;
    public static final int CONFIRM_GAP = 4;
    public static final int CONFIRM_WRAP_W = 280;
    public static final int CONFIRM_WRAP_INSET = 50;
    public static final int CONFIRM_MAX_LINES = 15;
    public static final int PICKER_W = 200;
    public static final int LIST_PADDING = 4;

    private MenuLayout() {
    }

    public static Grid grid(int screenWidth, boolean listRows, int rowCount) {
        if (screenWidth >= TWO_COLUMN_MIN_W && !listRows && rowCount >= TWO_COLUMN_MIN_ROWS) {
            int columnWidth = Math.min(COLUMN_W, (screenWidth - 2 * (MARGIN + SCROLLBAR_RESERVE) - COLUMN_GAP) / 2);
            return new Grid(2, columnWidth, 2 * columnWidth + COLUMN_GAP);
        }
        int preferred = listRows ? ROW_W_LIST : ROW_W_SETTINGS;
        int width = Math.max(0, Math.min(preferred, screenWidth - 2 * (MARGIN_MIN + SCROLLBAR_RESERVE)));
        return new Grid(1, width, width);
    }

    public static Header header(boolean hasContext, int screenHeight) {
        boolean fits = screenHeight - HEADER_CTX_H - FOOTER_H >= 3 * ROW_1;
        return hasContext && fits ? new Header(HEADER_CTX_H, true) : new Header(HEADER_H, false);
    }

    public static int feedbackY(Header header) {
        return header.height() + GAP_S;
    }

    public static int listTop(Header header, boolean feedback) {
        return feedback ? feedbackY(header) + FEEDBACK_H + GAP_S : header.height();
    }

    public static int listBottom(int screenHeight) {
        return screenHeight - FOOTER_H;
    }

    public static int rowHeight(boolean twoLine) {
        return twoLine ? ROW_2 : ROW_1;
    }

    public static Row row(Control control, int x, int y, int width, int height, int sideCount, int labelWidth, int valueWidth) {
        int contentLeft = x + PAD;
        int contentRight = x + width - PAD;
        int sideX = sideCount > 0 ? contentRight - sideCount * SIDE_BUTTON - (sideCount - 1) * GAP_S : contentRight;
        int mainRight = sideCount > 0 ? sideX - GAP_S : contentRight;
        int labelX = contentLeft + ICON + GAP_S;
        int controlY = y + (height - BTN_H) / 2;
        int textY = height == ROW_2 ? y + 4 : y + (height - 8) / 2;
        int controlX;
        int controlWidth;
        int labelRight;
        if (control == Control.ROW) {
            controlX = x;
            controlWidth = mainRight - x;
            labelRight = mainRight;
        } else if (control == Control.NONE) {
            controlX = mainRight;
            controlWidth = 0;
            labelRight = mainRight;
        } else {
            controlWidth = controlWidth(control, mainRight - labelX, labelWidth, valueWidth);
            controlX = mainRight - controlWidth;
            labelRight = controlX - GAP_S;
        }
        return new Row(contentLeft, y + (height - ICON) / 2, labelX, labelRight, textY, y + 15,
            new Rect(controlX, controlY, controlWidth, BTN_H), sideX, new Rect(x, y + 1, mainRight - x, height - 2));
    }

    public static Footer footer(int screenWidth, int screenHeight, int span, boolean paging, int pageLabelWidth, boolean primary) {
        int y = screenHeight - FOOTER_BUTTON_INSET;
        int centerX = screenWidth / 2;
        if (!paging) {
            if (!primary) {
                int width = Math.min(FOOTER_SINGLE_W, span);
                return new Footer(new Rect(centerX - width / 2, y, width, BTN_H), null, null, null, null);
            }
            int width = Math.min(FOOTER_PAIR_W, (span - GAP_M) / 2);
            int left = centerX - width - GAP_M / 2;
            return new Footer(new Rect(left, y, width, BTN_H), null, null, null, new Rect(left + width + GAP_M, y, width, BTN_H));
        }
        int pagingWidth = 2 * STEP_W + 2 * GAP_S + pageLabelWidth;
        int sideWidth = Math.max(0, Math.min(FOOTER_SIDE_W, (span - pagingWidth - 2 * GAP_M) / 2));
        int left = centerX - span / 2;
        int pagingLeft = centerX - pagingWidth / 2;
        Rect previous = new Rect(pagingLeft, y, STEP_W, BTN_H);
        Rect label = new Rect(previous.right() + GAP_S, y, pageLabelWidth, BTN_H);
        Rect next = new Rect(label.right() + GAP_S, y, STEP_W, BTN_H);
        Rect primaryRect = primary ? new Rect(left + span - sideWidth, y, sideWidth, BTN_H) : null;
        return new Footer(new Rect(left, y, sideWidth, BTN_H), previous, label, next, primaryRect);
    }

    public static Confirm confirm(int screenWidth, int screenHeight, int lineCount) {
        int lines = Math.min(lineCount, CONFIRM_MAX_LINES);
        int wrapWidth = Math.max(0, Math.min(screenWidth - CONFIRM_WRAP_INSET, CONFIRM_WRAP_W));
        int buttonWidth = Math.max(0, Math.min(CONFIRM_BUTTON_W, (screenWidth - 2 * MARGIN_MIN - CONFIRM_GAP) / 2));
        int blockHeight = lines * LINE_H + GAP_M + BTN_H;
        int textTop = Math.max(0, (screenHeight - blockHeight) / 2);
        int buttonY = textTop + lines * LINE_H + GAP_M;
        int centerX = screenWidth / 2;
        Rect confirm = new Rect(centerX - buttonWidth - CONFIRM_GAP / 2, buttonY, buttonWidth, BTN_H);
        Rect cancel = new Rect(centerX + CONFIRM_GAP / 2, buttonY, buttonWidth, BTN_H);
        int panelWidth = Math.max(wrapWidth, 2 * buttonWidth + CONFIRM_GAP) + 2 * GAP_M;
        Rect panel = new Rect(centerX - panelWidth / 2, textTop - GAP_M, panelWidth, blockHeight + 2 * GAP_M);
        return new Confirm(lines, wrapWidth, textTop, confirm, cancel, panel);
    }

    public static int footerSpan(int screenWidth) {
        return Math.min(ROW_W_SETTINGS, screenWidth - 2 * MARGIN_MIN);
    }

    public static Picker picker(int screenWidth, int screenHeight, int optionCount) {
        int rowWidth = Math.max(0, Math.min(PICKER_W, screenWidth - 2 * (MARGIN + SCROLLBAR_RESERVE)));
        int panelWidth = rowWidth + 2 * SCROLLBAR_RESERVE;
        int top = HEADER_H;
        int panelHeight = Math.min(Math.max(0, listBottom(screenHeight) - top), optionCount * ROW_1 + LIST_PADDING);
        Rect panel = new Rect(screenWidth / 2 - panelWidth / 2, top, panelWidth, panelHeight);
        Rect cancel = footer(screenWidth, screenHeight, footerSpan(screenWidth), false, 0, false).back();
        return new Picker(panel, rowWidth, cancel);
    }

    private static int controlWidth(Control control, int available, int labelWidth, int valueWidth) {
        int roomBesideLabel = available - GAP_S - Math.max(LABEL_MIN, Math.min(labelWidth, available / 2));
        return switch (control) {
            case TOGGLE -> Math.min(TOGGLE_W, available);
            case CYCLE -> Math.min(available, clamp(roomBesideLabel, CHOICE_MIN_W, CYCLE_W));
            case CAPTURE -> Math.min(available, clamp(roomBesideLabel, CHOICE_MIN_W, CAPTURE_W));
            case NUMBER -> Math.min(available, 2 * STEP_W + 2 * CONTROL_GAP
                + clamp(roomBesideLabel - 2 * STEP_W - 2 * CONTROL_GAP, Math.max(SLIDER_MIN_W, valueWidth + 2 * PAD), SLIDER_W));
            case TEXT -> {
                int labelShare = labelWidth <= 0 ? 0 : Math.min(labelWidth, available * 2 / 5) + GAP_S;
                yield Math.min(available, Math.max(TEXT_MIN_W, available - labelShare));
            }
            case ROW, NONE -> available;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum Control {
        ROW, NONE, TOGGLE, CYCLE, NUMBER, TEXT, CAPTURE
    }

    public record Grid(int columns, int columnWidth, int rowWidth) {
    }

    public record Header(int height, boolean context) {
    }

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centerX() {
            return x + width / 2;
        }

        public int centerY() {
            return y + height / 2;
        }

        public boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }

        public boolean intersects(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }
    }

    public record Row(int iconX, int iconY, int labelX, int labelRight, int textY, int secondY, Rect control, int sideX, Rect entry) {
        public Rect label() {
            return new Rect(entry.x(), entry.y(), labelRight - entry.x(), entry.height());
        }

        public Rect side(int index) {
            return new Rect(sideX + index * (SIDE_BUTTON + GAP_S), control.y(), SIDE_BUTTON, BTN_H);
        }

        public Rect decrement() {
            return new Rect(control.x(), control.y(), STEP_W, BTN_H);
        }

        public Rect slider() {
            return new Rect(control.x() + STEP_W + CONTROL_GAP, control.y(), control.width() - 2 * (STEP_W + CONTROL_GAP), BTN_H);
        }

        public Rect increment() {
            return new Rect(control.right() - STEP_W, control.y(), STEP_W, BTN_H);
        }

        public Rect edit() {
            return new Rect(control.x(), control.y(), control.width() - SIDE_BUTTON - CONTROL_GAP, BTN_H);
        }

        public Rect apply() {
            return new Rect(control.right() - SIDE_BUTTON, control.y(), SIDE_BUTTON, BTN_H);
        }
    }

    public record Footer(Rect back, Rect previous, Rect label, Rect next, Rect primary) {
    }

    public record Confirm(int lines, int wrapWidth, int textTop, Rect confirm, Rect cancel, Rect panel) {
    }

    public record Picker(Rect panel, int rowWidth, Rect cancel) {
    }
}
