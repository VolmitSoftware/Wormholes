package art.arcane.wormholes.menu;

@FunctionalInterface
public interface MenuConfirmFactory {
    MenuConfirmRequest request(MenuContext context, MenuHandler onConfirm);
}
