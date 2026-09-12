package art.arcane.wormholes.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;

final class CommandRulesTest {
    @Test
    void theRulesGroupIsMountedOnTheRootCommand() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorRuntimeNode rules = findChild(engine.getRoot(), "rules");

        assertNotNull(rules);
        for (String command : List.of("export", "import", "apply", "list", "key")) {
            assertNotNull(findChild(rules, command), command);
        }
    }

    @Test
    void everyRulesSubcommandDeclaresItsName() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandRules());
        DirectorRuntimeNode root = engine.getRoot();

        assertEquals("rules", root.getDescriptor().getName());
        assertNotNull(findChild(root, "export"));
    }

    private static DirectorRuntimeNode findChild(DirectorRuntimeNode parent, String name) {
        for (DirectorRuntimeNode child : parent.getChildren()) {
            if (child.getDescriptor().getName().equals(name)) {
                return child;
            }
        }
        return null;
    }
}
