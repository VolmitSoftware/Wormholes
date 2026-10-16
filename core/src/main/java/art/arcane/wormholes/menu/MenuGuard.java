package art.arcane.wormholes.menu;

@FunctionalInterface
public interface MenuGuard {
    MenuDecision check(MenuContext context, MenuAction action);

    default MenuGuard and(MenuGuard other) {
        return (context, action) -> {
            MenuDecision first = check(context, action);
            return first instanceof MenuDecision.Allow ? other.check(context, action) : first;
        };
    }
}
