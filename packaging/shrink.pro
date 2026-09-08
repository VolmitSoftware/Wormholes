-keep class art.arcane.wormholes.api.** { *; }

-keep class art.arcane.volmlib.integration.** { *; }

-keep class art.arcane.wormholes.service.WormholesIntegrationService { *; }

-keepclassmembers class * extends org.bukkit.event.Event {
    public static org.bukkit.event.HandlerList getHandlerList();
    public org.bukkit.event.HandlerList getHandlers();
}
