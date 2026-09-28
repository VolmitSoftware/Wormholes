package art.arcane.wormholes.util;

import java.util.Map;

public interface JsonDocuments {
    Map<String, Object> decode(String source);

    String encode(Map<String, Object> document);
}
