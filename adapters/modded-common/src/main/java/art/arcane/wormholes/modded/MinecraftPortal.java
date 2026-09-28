package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.PortalSettingsTarget;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleDocumentCodec;
import art.arcane.wormholes.portal.TravelCurrencyAmount;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.lod.LodProfile;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;

import art.arcane.wormholes.access.PortalAdmissionPolicy;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.access.PortalPermissionKey;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpSettingsCodec;
import art.arcane.wormholes.portal.ProjectionMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public final class MinecraftPortal extends Portal implements PortalSettingsTarget {
    private final PortalGeometry geometry;
    private final String worldKey;
    private final Map<String, Object> values;
    private boolean open = true;
    private DestinationPolicy meshPolicy;

    public MinecraftPortal(Definition definition) {
        super(definition.state().id(), definition.state().origin());
        restore(definition.state());
        geometry = definition.geometry();
        worldKey = definition.worldKey();
        values = new LinkedHashMap<>(definition.values());
        meshPolicy = DestinationPolicy.decode(values.get("mesh.policy") instanceof String encoded ? encoded : "");
        values.putIfAbsent("access.permissionKey", PortalPermissionKey.sanitize(definition.state().name()));
        if (!definition.state().explicitFrame()) {
            applyFrame(PortalFrame.derive(geometry.getArea(), getDirection()));
        }
        setNetworkViewDepth(getNetworkViewDepth());
        setNetworkViewLateralPad(getNetworkViewLateralPad());
        setNetworkViewHeartbeatTicks(getNetworkViewHeartbeatTicks());
        setNetworkViewEntityIntervalTicks(getNetworkViewEntityIntervalTicks());
        setNetworkViewUnsubscribeGraceSeconds(getNetworkViewUnsubscribeGraceSeconds());
        setActivationRange(getActivationRange());
        setAmbientColor(getAmbientColor());
    }

    public static MinecraftPortal read(Map<String, Object> values) {
        PortalGeometry geometry = new PortalGeometry();
        Map<String, Object> structure = PortalStateCodec.object(values, "structure");
        PortalStateCodec.readGeometry(structure, geometry);
        return new MinecraftPortal(new Definition(PortalStateCodec.read(values), geometry,
            PortalStateCodec.string(structure, "worldKey"), values));
    }

    public Map<String, Object> write() {
        Map<String, Object> result = new LinkedHashMap<>(values);
        PortalStateCodec.write(result, new State(getId(), getOrigin(), getName(), getFrame(), true));
        result.put("structure", PortalStateCodec.writeGeometry(worldKey, geometry));
        return result;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    public PortalGeometry getGeometry() {
        return geometry;
    }

    public String getWorldKey() {
        return worldKey;
    }

    public UUID getOwner() {
        return UUID.fromString(PortalStateCodec.string(values, "owner"));
    }

    public PortalType getType() {
        return PortalType.valueOf(PortalStateCodec.string(values, "type"));
    }

    public UUID getDestinationId() {
        if (!(values.get("tunnel") instanceof Map<?, ?> tunnel) || !(tunnel.get("destination") instanceof String id)) {
            return null;
        }
        return UUID.fromString(id);
    }

    public String getTunnelType() {
        return values.get("tunnel") instanceof Map<?, ?> tunnel ? String.valueOf(tunnel.get("type")) : "";
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public void setFrame(PortalFrame frame) {
        applyFrame(frame);
    }

    public ProjectionMode getProjectionMode() {
        return "OFF".equals(values.get("projectionMode")) ? ProjectionMode.OFF : ProjectionMode.ON;
    }

    public RuleDocument ruleDocument(RulesConfig limits) {
        return values.containsKey("rules.document")
            ? RuleDocumentCodec.fromJson(PortalStateCodec.object(values, "rules.document"), limits) : RuleDocument.EMPTY;
    }

    public void setRuleDocument(RuleDocument document) {
        if (document.isInert()) {
            values.remove("rules.document");
        } else {
            values.put("rules.document", RuleDocumentCodec.toJson(document));
        }
    }

    public Map<String, Object> ruleCharges() {
        return values.containsKey("rules.charges") ? PortalStateCodec.object(values, "rules.charges") : Map.of();
    }

    public void setRuleCharges(Map<String, Object> charges) {
        if (charges.isEmpty()) {
            values.remove("rules.charges");
        } else {
            values.put("rules.charges", Map.copyOf(charges));
        }
    }

    public void setItemTravelCost(String encoded) {
        int quantity = values.get("travelCost") instanceof Map<?, ?> price && "VANILLA".equals(price.get("type"))
            && price.get("quantity") instanceof Number number ? number.intValue() : 1;
        values.put("travelCost", Map.of("type", "VANILLA", "item", Objects.requireNonNull(encoded),
            "quantity", ExactItemPayment.clampQuantity(quantity)));
    }

    public void setTravelCostQuantity(int quantity) {
        if (values.get("travelCost") instanceof Map<?, ?> price && "VANILLA".equals(price.get("type"))) {
            values.put("travelCost", Map.of("type", "VANILLA", "item", price.get("item"),
                "quantity", ExactItemPayment.clampQuantity(quantity)));
        }
    }

    public void setCurrencyTravelCost(String amount) {
        values.put("travelCost", Map.of("type", "VAULT", "amount", TravelCurrencyAmount.parse(amount).toPlainString()));
    }

    public void clearTravelCost() {
        values.remove("travelCost");
    }

    public void setProjectionMode(ProjectionMode mode) {
        values.put("projectionMode", mode.name());
    }

    public boolean isMirrorMode() {
        return flag("mirrorMode", false);
    }

    public void setMirrorMode(boolean enabled) {
        if (enabled && isManaged()) {
            return;
        }
        values.put("mirrorMode", enabled);
        if (enabled) {
            if (getType() == PortalType.RTP) {
                values.put("type", PortalType.PORTAL.name());
            }
            values.remove("tunnel");
        }
    }

    public boolean isOutgoingTraversalsEnabled() {
        return flag("outgoingTraversalsEnabled", true);
    }

    public void setOutgoingTraversalsEnabled(boolean enabled) {
        values.put("outgoingTraversalsEnabled", enabled);
    }

    public boolean isIncomingTraversalsEnabled() {
        return flag("incomingTraversalsEnabled", true);
    }

    public void setIncomingTraversalsEnabled(boolean enabled) {
        values.put("incomingTraversalsEnabled", enabled);
    }

    public boolean isManaged() {
        return getDimensionalKind().isManagedPortal();
    }

    public DimensionalPortalKind getDimensionalKind() {
        return DimensionalPortalKind.fromName(String.valueOf(values.getOrDefault("dimensionalPortalKind", "NONE")));
    }

    public void setDimensionalKind(DimensionalPortalKind kind) {
        values.put("dimensionalPortalKind", Objects.requireNonNull(kind).name());
    }

    public UUID getCounterpartId() {
        return values.get("dimensionalCounterpartId") instanceof String id ? UUID.fromString(id) : null;
    }

    public void setCounterpartId(UUID id) {
        if (id == null) {
            values.remove("dimensionalCounterpartId");
        } else {
            values.put("dimensionalCounterpartId", id.toString());
        }
    }

    public boolean canManage(UUID playerId, boolean administrator) {
        PortalRole role = role(playerId);
        return administrator || role != null && role.manages()
            || !getId().equals(getOwner()) && getOwner().equals(playerId);
    }

    public boolean allows(UUID playerId, boolean bypass, Predicate<String> permissions, boolean nameAlias) {
        Map<String, Object> roles = roles();
        boolean whitelist = false;
        for (Object value : roles.values()) {
            PortalRole role = PortalRole.fromName(String.valueOf(value));
            if (role != null && role.trusted()) {
                whitelist = true;
                break;
            }
        }
        boolean groupAllowed = false;
        if (values.get("access.groups") instanceof List<?> groups) {
            for (Object group : groups) {
                if (group instanceof String node && permissions.test(node)) {
                    groupAllowed = true;
                    break;
                }
            }
        }
        String nameKey = sanitize(getName());
        String key = String.valueOf(values.getOrDefault("access.permissionKey", nameKey));
        boolean hasPermission = permissions.test("wormholes.portal." + key)
            || nameAlias && !key.equals(nameKey) && permissions.test("wormholes.portal." + nameKey);
        boolean permissionAllowed = "WHITELIST".equals(values.get("permissionMode")) ? hasPermission : !hasPermission;
        return PortalAdmissionPolicy.allows(new PortalAdmissionPolicy.Admission(playerId, getOwner(), role(playerId),
            bypass, true, groupAllowed, whitelist, permissionAllowed));
    }

    @Override
    public int getNetworkViewDepth() {
        return intValue("networkViewDepth", 64);
    }

    @Override
    public void setNetworkViewDepth(int value) {
        values.put("networkViewDepth", Math.max(1, Math.min(128, value)));
    }

    @Override
    public int getNetworkViewLateralPad() {
        return intValue("networkViewLateralPad", 48);
    }

    @Override
    public void setNetworkViewLateralPad(int value) {
        values.put("networkViewLateralPad", Math.max(0, Math.min(64, value)));
    }

    @Override
    public int getNetworkViewHeartbeatTicks() {
        return intValue("networkViewHeartbeatTicks", 60);
    }

    @Override
    public void setNetworkViewHeartbeatTicks(int value) {
        values.put("networkViewHeartbeatTicks", Math.max(2, Math.min(600, value)));
    }

    @Override
    public int getNetworkViewEntityIntervalTicks() {
        return intValue("networkViewEntityIntervalTicks", 10);
    }

    @Override
    public void setNetworkViewEntityIntervalTicks(int value) {
        values.put("networkViewEntityIntervalTicks", Math.max(2, Math.min(600, value)));
    }

    @Override
    public int getNetworkViewUnsubscribeGraceSeconds() {
        return intValue("networkViewUnsubscribeGraceSeconds", 30);
    }

    @Override
    public void setNetworkViewUnsubscribeGraceSeconds(int value) {
        values.put("networkViewUnsubscribeGraceSeconds", Math.max(5, Math.min(600, value)));
    }

    @Override
    public int getAmbientColor() {
        return intValue("ambientColor", 0xB969FF);
    }

    @Override
    public void setAmbientColor(int value) {
        values.put("ambientColor", Math.max(0, Math.min(0xFFFFFF, value)));
    }

    @Override
    public boolean isBlackoutBackground() {
        return flag("blackoutBackground", false);
    }

    @Override
    public void setBlackoutBackground(boolean enabled) {
        values.put("blackoutBackground", enabled);
    }

    @Override
    public boolean isSettingsSyncEnabled() {
        return flag("settingsSyncEnabled", true);
    }

    @Override
    public void setSettingsSyncEnabled(boolean enabled) {
        values.put("settingsSyncEnabled", enabled);
    }

    @Override
    public BlackoutColor getBlackoutColor() {
        return enumValue(BlackoutColor.class, "blackoutColor", BlackoutColor.BLACK);
    }

    @Override
    public void setBlackoutColor(BlackoutColor value) {
        values.put("blackoutColor", (value == null ? BlackoutColor.BLACK : value).name());
    }

    @Override
    public ProjectionRenderMode getRenderMode() {
        return enumValue(ProjectionRenderMode.class, "renderMode", ProjectionRenderMode.VENTICULAR);
    }

    @Override
    public void setRenderMode(ProjectionRenderMode value) {
        values.put("renderMode", (value == null ? ProjectionRenderMode.VENTICULAR : value).name());
    }

    @Override
    public AmbientParticleStyle getAmbientStyle() {
        return enumValue(AmbientParticleStyle.class, "ambientStyle", AmbientParticleStyle.SPARKS);
    }

    @Override
    public void setAmbientStyle(AmbientParticleStyle value) {
        values.put("ambientStyle", (value == null ? AmbientParticleStyle.SPARKS : value).name());
    }

    @Override
    public PortalPermissionMode getPermissionMode() {
        return enumValue(PortalPermissionMode.class, "permissionMode", PortalPermissionMode.BLACKLIST);
    }

    @Override
    public void setPermissionMode(PortalPermissionMode value) {
        values.put("permissionMode", (value == null ? PortalPermissionMode.BLACKLIST : value).name());
    }

    @Override
    public MirrorRotation getMirrorRotation() {
        return MirrorRotation.fromDegrees(intValue("mirrorRotationDegrees", 0)).coherentFor(getFrame());
    }

    @Override
    public void setMirrorRotation(MirrorRotation rotation) {
        values.put("mirrorRotationDegrees", (rotation == null ? MirrorRotation.DEGREES_0 : rotation).coherentFor(getFrame()).getDegrees());
    }

    @Override
    public int getActivationRange() {
        return intValue("activationRange", 0);
    }

    @Override
    public void setActivationRange(int value) {
        values.put("activationRange", value <= 0 ? 0 : Math.max(8, Math.min(256, value)));
    }

    @Override
    public String getNetworkViewFallbackBlock() {
        return String.valueOf(values.getOrDefault("networkViewFallbackBlock", "minecraft:air"));
    }

    @Override
    public void setNetworkViewFallbackBlock(String value) {
        String normalized = value == null || value.isBlank() ? "minecraft:air" : value.trim();
        try {
            normalized = BlockStateParser.serialize(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, normalized, false).blockState());
        } catch (CommandSyntaxException error) {
            normalized = "minecraft:air";
        }
        values.put("networkViewFallbackBlock", normalized);
    }

    @Override
    public String getSurfaceSkin() {
        return String.valueOf(values.getOrDefault("surfaceSkin", ""));
    }

    @Override
    public void setSurfaceSkin(String skin) {
        values.put("surfaceSkin", PortalSurfaceSkins.normalizeSkin(skin));
    }

    public String getDestinationServer() {
        return values.get("tunnel") instanceof Map<?, ?> tunnel && tunnel.get("server") instanceof String server ? server : null;
    }

    private int intValue(String key, int fallback) {
        return values.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String key, E fallback) {
        if (values.get(key) instanceof String name) {
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException error) {
                return fallback;
            }
        }
        return fallback;
    }

    public DestinationPolicy meshPolicy() {
        return meshPolicy;
    }

    public void setMeshPolicy(String encoded) {
        meshPolicy = DestinationPolicy.decode(encoded);
        if (encoded == null) {
            values.remove("mesh.policy");
        } else {
            values.put("mesh.policy", encoded);
        }
    }

    public void setNexusValue(String key, Object value) {
        if (value == null) {
            values.remove("nexus." + key);
        } else {
            values.put("nexus." + key, value);
        }
    }

    public NetworkViewQuality getNetworkViewQuality() {
        return flag("networkViewCustomSelected", false) ? NetworkViewQuality.CUSTOM : NetworkViewQuality.from(
            getNetworkViewDepth(), getNetworkViewHeartbeatTicks(), getNetworkViewEntityIntervalTicks(), getNetworkViewUnsubscribeGraceSeconds());
    }

    public void setNetworkViewQuality(NetworkViewQuality quality) {
        values.put("networkViewCustomSelected", quality == NetworkViewQuality.CUSTOM);
        if (quality != NetworkViewQuality.CUSTOM) {
            setNetworkViewDepth(quality.getDepth());
            setNetworkViewHeartbeatTicks(quality.getHeartbeatTicks());
            setNetworkViewEntityIntervalTicks(quality.getEntityIntervalTicks());
            setNetworkViewUnsubscribeGraceSeconds(quality.getUnsubscribeGraceSeconds());
        }
    }

    public void setAtmosphereMode(AtmosphereMode mode) {
        setOverride("fidelity.atmosphere", mode == null ? null : mode.configName());
    }

    public void setAcousticsProfile(AcousticsProfile profile) {
        setOverride("fidelity.acoustics", profile == null ? null : profile.configName());
    }

    public void setLodProfile(LodProfile profile) {
        setOverride("fidelity.lod", profile == null ? null : profile.configName());
    }

    public void setBlockEntities(Boolean enabled) {
        setOverride("fidelity.block_entities", enabled);
    }

    public void setMomentum(MomentumPolicy policy) {
        setOverride("transit.momentum", policy == null ? null : policy.encode());
    }

    public void setOrientation(OrientationPolicy policy) {
        setOverride("transit.orientation", policy == null ? null : policy.name());
    }

    public void setMembrane(boolean enabled) {
        values.put("transit.membrane", enabled);
    }

    public void setBounce(boolean enabled) {
        values.put("transit.bounce", enabled);
    }

    public void setTransitionProfile(TransitionProfile profile) {
        setOverride("transit.profile", profile == null || profile.isNone() ? null : profile.encode());
    }

    private void setOverride(String key, Object value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    public Object setting(String key) {
        return values.get(key);
    }

    void link(MinecraftPortal destination) {
        values.put("tunnel", Map.of("type", worldKey.equals(destination.worldKey) ? "LOCAL" : "DIMENSIONAL",
            "destination", destination.getId().toString()));
    }

    public boolean linkRemote(String serverName, UUID destinationId) {
        if (isMirrorMode() || isManaged() || getType() == PortalType.RTP) {
            return false;
        }
        values.remove("dimensionalCounterpartId");
        values.put("tunnel", Map.of("type", "UNIVERSAL", "server", Objects.requireNonNull(serverName),
            "destination", Objects.requireNonNull(destinationId).toString()));
        return true;
    }

    void unlink() {
        values.remove("tunnel");
    }

    public Map<UUID, PortalRole> getRoles() {
        Map<UUID, PortalRole> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : roles().entrySet()) {
            PortalRole role = PortalRole.fromName(String.valueOf(entry.getValue()));
            if (role != null) {
                result.put(UUID.fromString(entry.getKey()), role);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public void setRole(UUID playerId, PortalRole role) {
        Objects.requireNonNull(playerId, "playerId");
        if (playerId.equals(getOwner())) {
            throw new IllegalArgumentException("The portal owner cannot have an access override");
        }
        Map<String, Object> changed = new LinkedHashMap<>(roles());
        if (role == null) {
            changed.remove(playerId.toString());
        } else {
            changed.put(playerId.toString(), role.name());
        }
        values.put("access.roles", changed);
    }

    public void setOwner(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        values.put("access.transferredFrom", getOwner().toString());
        values.put("owner", owner.toString());
        Map<String, Object> changed = new LinkedHashMap<>(roles());
        changed.remove(owner.toString());
        values.put("access.roles", changed);
    }

    public void setType(PortalType type) {
        if (isManaged()) {
            throw new IllegalStateException("Managed doorway type cannot be changed");
        }
        PortalType previous = getType();
        Objects.requireNonNull(type);
        if ((previous == PortalType.GATEWAY) != (type == PortalType.GATEWAY) || previous == PortalType.RTP || type == PortalType.RTP) {
            values.remove("tunnel");
        }
        values.put("type", type.name());
        if (type == PortalType.RTP) {
            values.put("mirrorMode", false);
            values.remove("tunnel");
        }
    }

    public void setRtpSettings(RtpSettings settings) {
        values.put("rtp", RtpSettingsCodec.writeSettings(Objects.requireNonNull(settings)));
    }

    public String getPermissionKey() {
        return String.valueOf(values.get("access.permissionKey"));
    }

    public void setPermissionKey(String key) {
        if (!PortalPermissionKey.isValid(key)) {
            throw new IllegalArgumentException("Invalid portal permission key");
        }
        values.put("access.permissionKey", key);
    }

    public List<String> getGroups() {
        List<String> groups = new ArrayList<>();
        if (values.get("access.groups") instanceof List<?> stored) {
            for (Object group : stored) {
                if (group instanceof String node && !node.isBlank() && !groups.contains(node)) {
                    groups.add(node);
                }
            }
        }
        return List.copyOf(groups);
    }

    public boolean addGroup(String node) {
        String requested = node == null ? "" : node.trim();
        List<String> groups = new ArrayList<>(getGroups());
        if (requested.isEmpty() || groups.contains(requested)) {
            return false;
        }
        groups.add(requested);
        values.put("access.groups", groups);
        return true;
    }

    public boolean clearGroups() {
        if (getGroups().isEmpty()) {
            return false;
        }
        values.put("access.groups", new ArrayList<>());
        return true;
    }

    public boolean isListed() {
        return flag("access.listed", true);
    }

    public void setListed(boolean listed) {
        values.put("access.listed", listed);
    }

    public PortalRole role(UUID playerId) {
        return PortalRole.fromName(String.valueOf(roles().get(playerId.toString())));
    }

    private Map<String, Object> roles() {
        return values.get("access.roles") instanceof Map<?, ?> ? PortalStateCodec.object(values, "access.roles") : Map.of();
    }

    private boolean flag(String key, boolean fallback) {
        return values.get(key) instanceof Boolean enabled ? enabled : fallback;
    }

    private static String sanitize(String name) {
        return PortalPermissionKey.sanitize(name);
    }

    public record Definition(State state, PortalGeometry geometry, String worldKey, Map<String, Object> values) {
    }
}
