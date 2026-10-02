-keep class art.arcane.wormholes.api.** { *; }

-keep class art.arcane.volmlib.integration.** { *; }

-keep class art.arcane.wormholes.service.WormholesIntegrationService { *; }

-keepclassmembers class * extends org.bukkit.event.Event {
    public static org.bukkit.event.HandlerList getHandlerList();
    public org.bukkit.event.HandlerList getHandlers();
}

-keep @art.arcane.volmlib.nativelib.NativeBinding interface * { *; }
-keep class art.arcane.volmlib.nativelib.**.item.ItemStackAccessImpl { public <init>(); public *; }
-dontwarn art.arcane.volmlib.nativelib.**.item.ItemStackAccessImpl
-dontwarn art.arcane.volmlib.nativelib.v26_3_R1.environment.NativeWorldEnvironmentAccess
-keep class art.arcane.volmlib.nativelib.**.player.PlayerRespawnAccessImpl { public <init>(); public *; }
-dontwarn art.arcane.volmlib.nativelib.**.player.PlayerRespawnAccessImpl
-keep class art.arcane.volmlib.nativelib.v26_2_R1.block.NativeBlockEntityAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_3_R1.block.NativeBlockEntityAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_2_R1.chunk.NativeChunkPacketAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_3_R1.chunk.NativeChunkPacketAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_2_R1.entity.NativeEntityVisibilityAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_3_R1.entity.NativeEntityVisibilityAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_2_R1.entity.NativeEntityMetadataAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_3_R1.entity.NativeEntityMetadataAccess { public <init>(); }
-dontwarn art.arcane.volmlib.nativelib.common.entity.NativeEntityMetadataAccess
-keep class art.arcane.volmlib.nativelib.v26_2_R1.map.NativeMapPixelsAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_3_R1.map.NativeMapPixelsAccess { public <init>(); }

-keep class art.arcane.volmlib.nativelib.**.scoreboard.NativeScoreboardPackets { public <init>(); }

-keep class art.arcane.volmlib.nativelib.**.chunk.NativeChunkSendRateAccessor { public <init>(); }
