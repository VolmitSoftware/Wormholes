package art.arcane.wormholes.modded;

import art.arcane.wormholes.util.JsonDocuments;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.util.Map;
import java.util.Objects;

public enum MinecraftJsonDocuments implements JsonDocuments {
    INSTANCE;

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final TypeToken<Map<String, Object>> DOCUMENT = new TypeToken<>() { };

    @Override
    public Map<String, Object> decode(String source) {
        return Objects.requireNonNull(JSON.fromJson(source, DOCUMENT), "JSON document");
    }

    @Override
    public String encode(Map<String, Object> document) {
        return JSON.toJson(document);
    }
}
