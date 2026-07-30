package mchorse.bbs_mod.api.plugin;

/** Manifest capability names. Structural registrations are staged and replayed by generation. */
public enum BBSPluginCapability
{
    EVENTS("events", true),
    NETWORK("network", true),
    RESOURCES("resources", true),
    SETTINGS("settings", true),
    UI_MIRROR("ui_mirror", true),
    RENDER_SURFACE("render_surface", true),
    FILM_COLLABORATION("film_collaboration", true),
    EXECUTORS("executors", true),

    FORMS("forms", true),
    CLIPS("clips", true),
    PARTICLES("particles", true),
    KEY_MAPPINGS("key_mappings", true),
    ENTITY_RENDERER("entity_renderer", true),
    BLOCK_ENTITY_RENDERER("block_entity_renderer", true),
    MINECRAFT_REGISTRY("minecraft_registry", false),
    MIXIN("mixin", false),
    ACCESS_TRANSFORMER("access_transformer", false),
    COREMOD("coremod", false),
    JNI("jni", false),
    NATIVE_LIBRARY("native_library", false);

    private final String wireName;
    private final boolean hotSafe;

    BBSPluginCapability(String wireName, boolean hotSafe)
    {
        this.wireName = wireName;
        this.hotSafe = hotSafe;
    }

    public String wireName()
    {
        return this.wireName;
    }

    public boolean hotSafe()
    {
        return this.hotSafe;
    }

    public static BBSPluginCapability fromWireName(String value)
    {
        for (BBSPluginCapability capability : values())
        {
            if (capability.wireName.equals(value))
            {
                return capability;
            }
        }

        return switch (value)
        {
            case "source_packs" -> RESOURCES;
            case "internal_events" -> EVENTS;
            case "broker" -> NETWORK;
            case "mixins" -> MIXIN;
            case "access_transformers", "at" -> ACCESS_TRANSFORMER;
            case "coremods" -> COREMOD;
            case "native" -> NATIVE_LIBRARY;
            case "key_mapping", "keybindings" -> KEY_MAPPINGS;
            case "entity_renderers" -> ENTITY_RENDERER;
            case "block_entity_renderers" -> BLOCK_ENTITY_RENDERER;
            default -> throw new IllegalArgumentException("unknown plugin capability '" + value + "'");
        };
    }
}
