package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.resources.Link;

/**
 * Immutable, fully resolved UI theme. Every field is finalized at parse time
 * (including base chain inheritance), so render code only reads plain fields
 * with zero lookups and zero allocations.
 */
public class UITheme
{
    public final String id;
    public final String name;
    public final String author;
    public final String description;

    /** Whether this theme belongs to the light variant family. */
    public final boolean light;

    /* Colors, 0xAARRGGBB */
    public final int surfaceChrome;
    public final int surfaceBase;
    public final int surfaceRaised;
    public final int surfaceDeep;
    public final int surfaceDivider;
    public final int accentPrimary;
    public final int textPrimary;
    public final int textMuted;
    public final int statePositive;
    public final int stateNegative;
    public final int stateWarning;
    public final int stateActive;
    public final int stateHighlight;
    public final int stateCursor;

    /* Style toggles */
    public final boolean textShadow;
    public final boolean bevel;
    public final boolean panelShadow;

    /* Texture overrides, null = use the built-in texture */
    public final Link iconsAtlas;
    public final Link background;

    /* Motion */
    public final boolean motionEnabled;
    public final float motionSpeed;
    public final UIThemeMotion overlay;
    public final UIThemeMotion panelSwitch;
    public final UIThemeMotion hover;
    public final UIThemeMotion notification;
    public final UIThemeMotion contextMenu;
    public final UIThemeMotion scrollbar;

    UITheme(Builder builder)
    {
        this.id = builder.id;
        this.name = builder.name;
        this.author = builder.author;
        this.description = builder.description;
        this.light = builder.light;

        this.surfaceChrome = builder.surfaceChrome;
        this.surfaceBase = builder.surfaceBase;
        this.surfaceRaised = builder.surfaceRaised;
        this.surfaceDeep = builder.surfaceDeep;
        this.surfaceDivider = builder.surfaceDivider;
        this.accentPrimary = builder.accentPrimary;
        this.textPrimary = builder.textPrimary;
        this.textMuted = builder.textMuted;
        this.statePositive = builder.statePositive;
        this.stateNegative = builder.stateNegative;
        this.stateWarning = builder.stateWarning;
        this.stateActive = builder.stateActive;
        this.stateHighlight = builder.stateHighlight;
        this.stateCursor = builder.stateCursor;

        this.textShadow = builder.textShadow;
        this.bevel = builder.bevel;
        this.panelShadow = builder.panelShadow;

        this.iconsAtlas = builder.iconsAtlas;
        this.background = builder.background;

        this.motionEnabled = builder.motionEnabled;
        this.motionSpeed = builder.motionSpeed;
        this.overlay = builder.overlay;
        this.panelSwitch = builder.panelSwitch;
        this.hover = builder.hover;
        this.notification = builder.notification;
        this.contextMenu = builder.contextMenu;
        this.scrollbar = builder.scrollbar;
    }

    public static class Builder
    {
        public String id;
        public String name;
        public String author;
        public String description;
        public boolean light;

        public int surfaceChrome;
        public int surfaceBase;
        public int surfaceRaised;
        public int surfaceDeep;
        public int surfaceDivider;
        public int accentPrimary;
        public int textPrimary;
        public int textMuted;
        public int statePositive;
        public int stateNegative;
        public int stateWarning;
        public int stateActive;
        public int stateHighlight;
        public int stateCursor;

        public boolean textShadow;
        public boolean bevel;
        public boolean panelShadow;

        public Link iconsAtlas;
        public Link background;

        public boolean motionEnabled;
        public float motionSpeed;
        public UIThemeMotion overlay;
        public UIThemeMotion panelSwitch;
        public UIThemeMotion hover;
        public UIThemeMotion notification;
        public UIThemeMotion contextMenu;
        public UIThemeMotion scrollbar;

        /**
         * @param from theme to copy every field from (inheritance fallback),
         *             can be null for a blank builder
         */
        public Builder(String id, UITheme from)
        {
            this.id = id;

            if (from == null)
            {
                return;
            }

            this.name = from.name;
            this.author = from.author;
            this.description = from.description;
            this.light = from.light;

            this.surfaceChrome = from.surfaceChrome;
            this.surfaceBase = from.surfaceBase;
            this.surfaceRaised = from.surfaceRaised;
            this.surfaceDeep = from.surfaceDeep;
            this.surfaceDivider = from.surfaceDivider;
            this.accentPrimary = from.accentPrimary;
            this.textPrimary = from.textPrimary;
            this.textMuted = from.textMuted;
            this.statePositive = from.statePositive;
            this.stateNegative = from.stateNegative;
            this.stateWarning = from.stateWarning;
            this.stateActive = from.stateActive;
            this.stateHighlight = from.stateHighlight;
            this.stateCursor = from.stateCursor;

            this.textShadow = from.textShadow;
            this.bevel = from.bevel;
            this.panelShadow = from.panelShadow;

            this.iconsAtlas = from.iconsAtlas;
            this.background = from.background;

            this.motionEnabled = from.motionEnabled;
            this.motionSpeed = from.motionSpeed;
            this.overlay = from.overlay;
            this.panelSwitch = from.panelSwitch;
            this.hover = from.hover;
            this.notification = from.notification;
            this.contextMenu = from.contextMenu;
            this.scrollbar = from.scrollbar;
        }

        public UITheme build()
        {
            return new UITheme(this);
        }
    }
}
