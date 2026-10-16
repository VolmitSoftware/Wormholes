package art.arcane.wormholes.menu.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

final class FakeGatewayMenuHost implements GatewayMenuHost {
    final List<String> calls = new ArrayList<>();
    GatewayView view = GatewayView.UNPAIRED;
    ImportOutcome outcome = ImportOutcome.imported();

    @Override
    public GatewayView gateway(UUID portalId) {
        return view;
    }

    @Override
    public void exportToChat(MenuViewer viewer, UUID portalId) {
        calls.add("export");
    }

    @Override
    public ImportOutcome importCode(MenuViewer viewer, UUID portalId, String code) {
        calls.add("import:" + code);
        return outcome;
    }
}
