package art.arcane.wormholes.menu;

@FunctionalInterface
public interface MenuHandler {
    MenuResult handle(MenuContext context, MenuAction action);
}
