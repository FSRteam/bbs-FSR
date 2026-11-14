package mchorse.bbs_mod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueBooleanInverse;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.settings.values.numeric.ValueLong;
import mchorse.bbs_mod.settings.values.ui.ValueColors;
import mchorse.bbs_mod.settings.values.ui.ValueEditorLayout;
import mchorse.bbs_mod.settings.values.ui.ValueIKDebug;
import mchorse.bbs_mod.settings.values.ui.ValueLanguage;
import mchorse.bbs_mod.settings.values.ui.ValueMotionPath;
import mchorse.bbs_mod.settings.values.ui.ValueOnionSkin;
import mchorse.bbs_mod.settings.values.ui.ValuePhysicsDebug;
import mchorse.bbs_mod.settings.values.ui.ValueOrder;
import mchorse.bbs_mod.settings.values.ui.ValueStringKeys;
import mchorse.bbs_mod.settings.values.ui.ValueVideoSettings;
import mchorse.bbs_mod.settings.values.ui.ValueExportChannelLayout;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.colors.Oklab;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_mod.utils.keyframes.KeyframeStyle;
import mchorse.bbs_mod.settings.values.ui.ValueKeyframeStyle;

public class BBSSettings {

	public static final String DEFAULT_FFMPEG_ARGUMENTS = ValueVideoSettings.DEFAULT_FFMPEG_ARGUMENTS;
	public static final String DEFAULT_AUDIO_FFMPEG_ARGUMENTS = ValueVideoSettings.DEFAULT_AUDIO_FFMPEG_ARGUMENTS;
	public static final String DEFAULT_MUX_FFMPEG_ARGUMENTS = mchorse.bbs_mod.utils.VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS;
	private static final String LEGACY_MUX_FFMPEG_ARGUMENTS =
		"-y -i %VIDEO% -i %AUDIO_TRACK% -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k -shortest %NAME%.mp4";

	public static ValueColors favoriteColors;
	public static ValueColors recentColors;
	public static mchorse.bbs_mod.settings.values.core.ValueRecentData recentData;
	public static ValueStringKeys disabledSheets;
	public static mchorse.bbs_mod.settings.values.ui.ValueTrackStyles trackStyles;
	public static ValueStringKeys disabledMorphFormCategories;
	public static ValueInt textureCellSize;
	public static ValueString textureSort;
	public static mchorse.bbs_mod.settings.values.core.ValueLinkList texturePins;
	public static ValueLanguage language;
	public static ValueInt primaryColor;
	public static ValueInt stencilHighlightColor;
	public static ValueBoolean enableTrackpadIncrements;
	public static ValueBoolean enableTrackpadScrolling;
	public static ValueFloat userIntefaceScale;
	public static ValueBoolean pixelArtSmoothing;
	public static ValueString themeId;
	public static ValueBoolean accentFollowsTheme;
	public static ValueBoolean motionEnabled;
	public static ValueFloat motionSpeed;
	public static ValueString motionEasing;
	public static ValueBoolean showTooltips;
	public static ValueInt tooltipStyle;
	public static ValueBoolean textEntryOpenAnim;
	public static ValueBoolean filmEditorLayoutTransitionEnabled;
	public static ValueBoolean dashboardAutoHideTaskbar;
	public static ValueInt taskbarSide;
	public static ValueInt dashboardTaskbarHideDelay;
	public static ValueBoolean editorVerticalLinesTimeline;
	public static ValueFloat fov;
	public static ValueBoolean hsvColorPicker;
	public static ValueBoolean colorPickerHsvTab;
	public static ValueBoolean forceQwerty;
	public static ValueBoolean freezeModels;
	public static ValueBoolean freezeFormAnimations;
	public static ValueBoolean listModelPreview;
	/** Replay frustum culling (upstream {@code e08d8ac92}); read as the {@code frustum_culling} config key. */
	public static ValueBoolean frustumCulling;
	public static ValueBoolean morphingFocusSearch;
	public static ValueFloat axesScale;
	public static ValueFloat axesThickness;
	public static ValueBoolean axesKeepScreenSize;
	public static ValueBoolean gizmoKeepScreenSize;
	public static ValueFloat gizmoPlaneSize;
	public static ValueBoolean gizmoShowTranslate;
	public static ValueBoolean gizmoShowScale;
	public static ValueBoolean gizmoShowRotate;
	public static ValueBoolean gizmoShowViewRotate;
	public static ValueBoolean gizmoShowSphere;
	public static ValueBoolean rotate3dSphere;
	public static ValueInt rotate3dSphereMode;
	public static ValueBoolean rotateHideRings;
	public static ValueBoolean hideInactiveHandles;
	public static ValueFloat snapTranslate;
	public static ValueFloat snapRotate;
	public static ValueFloat snapScale;
	public static ValueInt gizmoHoverTolerance;
	public static ValueFloat gizmoOpacity;
	public static ValueBoolean uniformScale;
	public static ValueBoolean clickSound;
	public static ValueBoolean gizmos;
	public static ValueBoolean defaultLocalTransform;
	public static ValueInt transformSpace;
	public static ValueBoolean transformHotkeys3dRay;
	public static ValueBoolean poseMirrorEdit;
	public static ValueBoolean poseAlternateInvert;
	public static ValueBoolean poseShowDisabledBones;
	public static ValueOrder translateHotkeyOrder;
	public static ValueOrder scaleHotkeyOrder;
	public static ValueOrder rotateHotkeyOrder;
	public static ValueFloat trackballSensitivity;

	public static ValueBoolean enableCursorRendering;
	public static ValueBoolean enableMouseButtonRendering;
	public static ValueBoolean enableKeystrokeRendering;
	public static ValueInt keystrokeOffset;
	public static ValueInt keystrokeMode;

	public static ValueLink backgroundImage;
	public static ValueInt backgroundColor;

	public static ValueBoolean chromaSkyEnabled;
	public static ValueInt chromaSkyColor;
	public static ValueBoolean chromaSkyTerrain;
	public static ValueFloat chromaSkyBillboard;

	public static ValueInt scrollbarWidth;
	public static ValueFloat scrollingSensitivity;
	public static ValueFloat scrollingSensitivityHorizontal;
	public static ValueBoolean scrollingSmoothness;
	public static ValueFloat scrollingSmoothnessIntensity;
	public static ValueBoolean scrollingDisableSmoothnessInEditors;
	public static ValueBoolean scrollingUseThemeCurve;
	public static ValueString scrollingMotionEasing;

	public static ValueBoolean multiskinMultiThreaded;

	public static ValueString videoEncoderPath;
	public static ValueBoolean videoEncoderLog;
	public static ValueBoolean worldExportResizeWindow;
	public static ValueInt videoWidth;
	public static ValueInt videoHeight;
	public static ValueInt videoFrameRate;
	public static ValueBoolean videoLimitFrameRate;
	public static ValueString videoExportPath;
	public static ValueString videoExportFilenameFormat;
	public static ValueBoolean videoExportAudio;
	/** Persisted stable channel-layout id; invalid/legacy values normalize to mono. */
	public static ValueString videoAudioLayout;
	public static ValueBoolean videoExportMinecraftSounds;
	public static ValueBoolean videoMuteAudioWhileRender;
	public static ValueInt videoMotionBlur;
	public static ValueInt videoHeldFrames;
	public static ValueFloat videoDelay;
	public static ValueBoolean videoOpenFolderAfterExport;
	public static ValueBoolean videoPlaySoundAfterExport;
	public static ValueString videoArguments;
	public static ValueString videoArgumentsAudio;
	public static ValueString videoArgumentsMux;
	public static ValueVideoSettings videoSettings;

	public static ValueFloat editorCameraSpeed;
	public static ValueFloat editorCameraAngleSpeed;
	public static ValueInt duration;
	public static ValueBoolean editorLoop;
	public static ValueInt editorJump;
	public static ValueInt editorGuidesColor;
	public static ValueBoolean editorRuleOfThirds;
	public static ValueBoolean editorCenterLines;
	public static ValueBoolean editorCrosshair;
	public static ValueBoolean editorShowAllSoundGuides;
	public static ValueBoolean editorSeconds;
	public static ValueBoolean editorTimelineGrid;
	public static ValueString keyframeDefaultInterpolation;
	public static ValueBoolean keyframePreview;
	public static ValueInt editorPeriodicSave;
	public static ValueBoolean editorHorizontalFlight;
	public static ValueBoolean editorOrbitMovementRequiresFlight;
	public static ValueBoolean editorOrbitCenterMarker;
	public static ValueBoolean editorOrbitGizmo;
	public static ValueFloat editorOrbitGizmoScale;
	public static ValueBoolean editorOrbitAxisOrtho;
	public static ValueBoolean editorOrbitTeleportOnSwitch;
	public static ValueFloat editorCameraSmoothness;
	public static ValueBoolean editorFlightFreeLook;
	public static ValueInt editorCameraMode;
	public static ValueBoolean editorPlayerFollowsCamera;
	public static ValueEditorLayout editorLayoutSettings;
	public static ValueOnionSkin editorOnionSkin;
	public static ValueMotionPath editorMotionPath;
	public static ValueIKDebug ikDebug;
	public static ValuePhysicsDebug physicsDebug;
	public static ValueBoolean editorSnapToMarkers;
	public static ValueBoolean editorSnapToTicks;
    public static ValueBoolean autoKeyframe;
	/** Snapping to the film's own markers — unlike {@link #editorSnapToMarkers}, which is the ruler's notches. */
	public static ValueBoolean editorSnapToFilmMarkers;
	public static ValueBoolean editorClipPreview;
	public static ValueBoolean editorRewind;
	public static ValueBoolean editorRestartOnSeek;
	public static ValueBoolean editorStopPlaybackOnScrub;
	/** Rebase an anchor's local transform when its actor/attachment target changes. */
	public static ValueBoolean anchorKeepTransform;
	public static ValueBoolean editorPreviewIconsAutoHide;
	public static ValueBoolean editorHorizontalClipEditor;
	public static ValueBoolean editorMinutesBackup;
	public static ValueBoolean editorResizablePanels;
	public static ValueInt editorKeyframePanelWidth;
	public static ValueInt editorTrackWidth;
	public static ValueInt keyframeDefaultShape;
	public static ValueKeyframeStyle keyframeDefaultStyle;
	public static ValueInt editorPreviewSizeMode;
	public static ValueInt editorPreviewCustomWidth;
	public static ValueInt editorPreviewCustomHeight;
	public static ValueFloat editorPreviewResolutionScale;
	public static ValueBoolean editorClipAutoName;
	public static ValueBoolean editorKeepFrameOnExit;
	public static ValueBoolean editorPreviewSelectionHud;
	public static ValueBoolean profilerOverlay;
	public static ValueBoolean framePoseCache;

	public static ValueFloat recordingCountdown;
	public static ValueBoolean recordingSwipeDamage;
	public static ValueBoolean editorReplayTabs;
	public static ValueBoolean recordingOverlays;
	public static ValueInt recordingPoseTransformOverlays;
	public static ValueInt recordingPoseOverlays;
	public static ValueInt recordingTransformOverlays;
	public static ValueBoolean recordingCameraPreview;
	public static ValueBoolean recordingTeleport;

	public static ValueBoolean updateEnabled;
	public static ValueInt updateChannel;
	public static ValueInt updateInterval;
	public static ValueString updateSkippedVersion;
	public static ValueLong updateLastCheck;
	public static ValueBoolean updateCheckTrigger;

	public static ValueBoolean renderAllModelBlocks;
	public static ValueBoolean clickModelBlocks;

	public static ValueString entitySelectorsPropertyWhitelist;

	public static ValueBoolean damageControl;

	public static ValueFloat backgroundBrightness;
	public static ValueInt secondaryColor;
	public static ValueInt formCellSize;
	public static ValueBoolean interfaceShadows;
	public static ValueBoolean interfaceHighlights;
	public static ValueFloat overlayBackgroundOpacity;
	public static ValueBoolean overlayGradientBorder;
	public static ValueBoolean interfaceGlow;
	public static ValueBoolean interfaceBlur;
	public static ValueInt interfaceBlurRadius;

	public static ValueBoolean shaderCurvesEnabled;
	public static ValueBoolean translucencyQueue;

	public static ValueBoolean audioWaveformVisibleInPreview;
	public static ValueBoolean audioWaveformVisibleInKeyframes;
	public static ValueInt audioWaveformDensity;
	public static ValueFloat audioWaveformWidth;
	public static ValueInt audioWaveformHeight;
	public static ValueBoolean audioWaveformFilename;
	public static ValueBoolean audioWaveformTime;
	public static ValueBoolean audioWaveformPreviewCombined;

	public static ValueString cdnUrl;
	public static ValueString cdnToken;

	private static final float DEFAULT_BACKGROUND_BRIGHTNESS = 1F;
	private static final float MIN_BACKGROUND_BRIGHTNESS = 0.5F;
	private static final float MAX_BACKGROUND_BRIGHTNESS = 1.5F;
	private static final float IDENTITY_BRIGHTNESS = 1F;
	private static final float BRIGHTNESS_EPSILON = 0.001F;
	private static final int DEFAULT_PRIMARY_COLOR = 0xff3242;
	private static final int DEFAULT_SECONDARY_COLOR = 0x171b22;
	private static final float[] SURFACE_OFFSETS = {-0.066F, -0.044F, -0.022F, 0F, 0.022F, 0.054F};
	private static final Oklab SURFACE_OKLAB = new Oklab();
	private static final int[] SURFACES = new int[SURFACE_OFFSETS.length];
	private static int surfaceSource = -1;
	private static boolean lightSurfaces;
	private static final float DEFAULT_OVERLAY_BACKGROUND_OPACITY = 0.5F;
	private static final String DEFAULT_MOTION_EASING = "sine_out";
	private static final String[] LEGACY_MOTION_EASINGS = {
		"sine_out", "sine_out", "linear", "sine_out", "exp_out"
	};
	/** Legacy int values of the removed "theme" setting, kept for migration. */
	private static final int LEGACY_LIGHT_THEME = 0;

	public static int primaryColor() {
		return primaryColor(Colors.A50);
	}

	public static int primaryColor(int alpha) {
		return withAlpha(primaryColorBase(), alpha);
	}

	/**
	 * The effective accent color: the theme's accent while "accent follows
	 * theme" is enabled, the user-picked color otherwise.
	 */
	private static int primaryColorBase() {
		if (accentFollowsTheme == null || accentFollowsTheme.get()) {
			return ThemeManager.current().accentPrimary;
		}

		return primaryColor == null ? DEFAULT_PRIMARY_COLOR : primaryColor.get();
	}

	/** RGB-only accent (no alpha), safe to compose with {@code | Colors.Axx}. */
	public static int accentColorRGB() {
		return primaryColorBase() & Colors.RGB;
	}

	public static boolean isLightTheme() {
		if (hasCustomSurfaces()) {
			buildSurfaces();
			return lightSurfaces;
		}

		return ThemeManager.current().light;
	}

	/** The default leaves the selected FSR theme's surface tokens in control. */
	private static boolean hasCustomSurfaces() {
		return secondaryColor != null && (secondaryColor.get() & Colors.RGB) != DEFAULT_SECONDARY_COLOR;
	}

	/** Cache the upstream perceptual ladder; render-time reads only rebuild after a color change. */
	private static void buildSurfaces() {
		int color = secondaryColor.get() & Colors.RGB;

		if (surfaceSource == color) {
			return;
		}

		SURFACE_OKLAB.set(color);

		for (int i = 0; i < SURFACES.length; i++) {
			SURFACES[i] = SURFACE_OKLAB.toRGB(MathUtils.clamp(SURFACE_OKLAB.l + SURFACE_OFFSETS[i], 0F, 1F));
		}

		lightSurfaces = SURFACE_OKLAB.l > 0.5F;
		surfaceSource = color;
	}

	private static int surface(int level, int themed) {
		if (hasCustomSurfaces()) {
			buildSurfaces();
			themed = SURFACES[level];
		}

		return applyBackgroundBrightness(themed);
	}

	private static int surfaceForeground(int themed) {
		return hasCustomSurfaces() && isLightTheme() != ThemeManager.current().light ? themed ^ Colors.RGB : themed;
	}

	private static int withAlpha(int color, int alpha) {
		return (color & Colors.RGB) | alpha;
	}

	private static float getBackgroundBrightnessFactor() {
		return backgroundBrightness == null ? DEFAULT_BACKGROUND_BRIGHTNESS : backgroundBrightness.get();
	}

	private static int applyBackgroundBrightness(int color) {
		float brightness = MathUtils.clamp(getBackgroundBrightnessFactor(), MIN_BACKGROUND_BRIGHTNESS, MAX_BACKGROUND_BRIGHTNESS);

		if (Math.abs(brightness - IDENTITY_BRIGHTNESS) < BRIGHTNESS_EPSILON) {
			return color;
		}

		int a = color & 0xff000000;
		int r = (color >> 16) & 0xff;
		int g = (color >> 8) & 0xff;
		int b = color & 0xff;

		if (brightness < 1F) {
			r = Math.round(r * brightness);
			g = Math.round(g * brightness);
			b = Math.round(b * brightness);
		}
		else {
			float factor = brightness - 1F;

			r += Math.round((255 - r) * factor);
			g += Math.round((255 - g) * factor);
			b += Math.round((255 - b) * factor);
		}

		r = MathUtils.clamp(r, 0, 255);
		g = MathUtils.clamp(g, 0, 255);
		b = MathUtils.clamp(b, 0, 255);

		return a | (r << 16) | (g << 8) | b;
	}

	public static int chromeSurface() {
		return surface(2, ThemeManager.current().surfaceChrome);
	}

	public static int baseSurface() {
		return surface(3, ThemeManager.current().surfaceBase);
	}

	public static int raisedSurface() {
		return surface(4, ThemeManager.current().surfaceRaised);
	}

	public static int deepSurface() {
		return surface(1, ThemeManager.current().surfaceDeep);
	}

	public static int sunkenSurface() {
		return surface(0, Colors.mulRGB(ThemeManager.current().surfaceDeep, 0.75F));
	}

	public static int dividerColor() {
		if (hasCustomSurfaces()) {
			buildSurfaces();
			return SURFACES[5];
		}

		return ThemeManager.current().surfaceDivider;
	}

	public static int textColor() {
		return surfaceForeground(ThemeManager.current().textPrimary);
	}

	public static int mutedTextColor() {
		return surfaceForeground(ThemeManager.current().textMuted);
	}

	public static int positiveColor() {
		return ThemeManager.current().statePositive;
	}

	public static int negativeColor() {
		return ThemeManager.current().stateNegative;
	}

	public static int warningColor() {
		return ThemeManager.current().stateWarning;
	}

	public static int activeColor() {
		return ThemeManager.current().stateActive;
	}

	public static int highlightColor() {
		return ThemeManager.current().stateHighlight;
	}

	public static int cursorColor() {
		return ThemeManager.current().stateCursor;
	}

	public static int fieldFillColor() {
		/* Field fill is a background surface like the four surface() getters, and text boxes and
		 * icon buttons sit right next to panels painted with those. Leaving it un-adjusted made
		 * those frames keep their theme colour while everything around them followed the
		 * background brightness slider. Alpha is preserved, so translucent fills still work. */
		return surface(1, ThemeManager.current().fieldFill);
	}

	public static int fieldBorderColor() {
		return hasCustomSurfaces() ? dividerColor() : ThemeManager.current().fieldBorder;
	}

	public static int tabActiveLineColor() {
		return ThemeManager.current().tabActiveLine;
	}

	public static int tabActiveGradientColor() {
		return ThemeManager.current().tabActiveGradient;
	}

	public static int areaTintColor() {
		return ThemeManager.current().areaTint;
	}

	public static int areaTintLightColor() {
		return ThemeManager.current().areaTintLight;
	}

	public static int dropFillColor() {
		return ThemeManager.current().dropFill;
	}

	public static int dropBorderColor() {
		return ThemeManager.current().dropBorder;
	}

	public static int splitterActiveColor() {
		return ThemeManager.current().splitterActive;
	}

	public static int splitterIdleColor() {
		return ThemeManager.current().splitterIdle;
	}

	public static int shadowMutedColor() {
		return ThemeManager.current().shadowMuted;
	}

	public static int trackpadScrubColor() {
		return ThemeManager.current().trackpadScrub;
	}

	public static int notificationFillColor() {
		return ThemeManager.current().notificationFill;
	}

	public static int notificationTextColor() {
		return ThemeManager.current().notificationText;
	}

	public static int selectionFillColor() {
		return ThemeManager.current().selectionFill;
	}

	public static int selectionOutlineColor() {
		return ThemeManager.current().selectionOutline;
	}

	public static int iconPressedColor() {
		return ThemeManager.current().iconPressed;
	}

	public static int iconDisabledColor() {
		return ThemeManager.current().iconDisabled;
	}

	public static int scrollbarShadowColor() {
		return ThemeManager.current().scrollbarShadow;
	}

	public static boolean showTooltipsEnabled() {
		return showTooltips == null || showTooltips.get();
	}

	public static boolean textEntryOpenAnimationEnabled() {
		return textEntryOpenAnim == null || textEntryOpenAnim.get();
	}

	public static boolean filmEditorLayoutTransitionEnabled() {
		return filmEditorLayoutTransitionEnabled == null || filmEditorLayoutTransitionEnabled.get();
	}

	public static boolean dashboardAutoHideTaskbarEnabled() {
		return dashboardAutoHideTaskbar != null && dashboardAutoHideTaskbar.get();
	}

	public static int dashboardTaskbarHideDelayMs() {
		return dashboardTaskbarHideDelay == null ? 100 : dashboardTaskbarHideDelay.get();
	}

	public static boolean verticalTimelineLinesEnabled() {
		return (editorTimelineGrid != null && editorTimelineGrid.get())
			|| (editorVerticalLinesTimeline != null && editorVerticalLinesTimeline.get());
	}

	public static boolean textShadow() {
		return (!hasCustomSurfaces() || !isLightTheme()) && ThemeManager.current().textShadow;
	}

	public static int cornerChrome() {
		return ThemeManager.current().cornerChrome;
	}

	public static int cornerPanel() {
		return ThemeManager.current().cornerPanel;
	}

	public static int cornerWidget() {
		return ThemeManager.current().cornerWidget;
	}

	public static int color(int color, int alpha) {
		return withAlpha(color, alpha);
	}

	public static int accentOverlay(int alpha) {
		return primaryColor(alpha);
	}

	/** Render-scoped: texture and film editors set this so inputs stay readable on their panels. */
	public static boolean lightInputs = false;

	public static int inputSurface() {
		return lightInputs ? raisedSurface() : deepSurface();
	}

	public static int panelShadowOpaqueColor() {
		return Colors.A25 | (primaryColorBase() & Colors.RGB);
	}

	public static int panelShadowTransparentColor() {
		return Colors.setA(primaryColorBase(), 0F);
	}

	/** Dimming behind an overlay panel; zero keeps the underlying UI fully visible. */
	public static int overlayBackground() {
		float opacity = overlayBackgroundOpacity == null ? DEFAULT_OVERLAY_BACKGROUND_OPACITY : overlayBackgroundOpacity.get();

		return Colors.a(MathUtils.clamp(opacity, 0F, 1F));
	}

	public static boolean hasOverlayGradientBorder() {
		return hasInterfaceGlow();
	}

	public static boolean hasInterfaceGlow()
	{
		ValueBoolean glow = interfaceGlow == null ? overlayGradientBorder : interfaceGlow;

		return glow == null || glow.get();
	}

	public static int getDefaultDuration() {
		return duration == null ? 100 : duration.get();
	}

	/** Shared strength for smooth scrolling and timeline zoom; zero selects immediate movement. */
	public static float getScrollSmoothingIntensity() {
		return scrollingSmoothness.get() ? scrollingSmoothnessIntensity.get() : 0F;
	}

	public static float getFov() {
		return MathUtils.toRad(fov == null ? 70F : fov.get());
	}

	public static float getScreenSizeScale(float distance)
	{
		return getScreenSizeScale(distance, getFov());
	}

	public static float getScreenSizeScale(float distance, float fov)
	{
		float tanFov = (float) Math.tan(fov / 2F);
		float scale = (distance / 5F) * (tanFov / 0.4663F);

		return Math.max(0.0001F, scale);
	}

	public static float getAxesDistanceScale(float distance) {
		return getAxesDistanceScale(distance, getFov());
	}

	public static float getAxesDistanceScale(float distance, float fov) {
		if (axesKeepScreenSize != null && axesKeepScreenSize.get()) {
			return getScreenSizeScale(distance, fov);
		}

		return 1F;
	}

	public static boolean isHorizontalClipEditorEffective() {
		return editorHorizontalClipEditor.get();
	}

	/**
	 * Returns the user-configured default shape for newly created keyframes. Falls back to
	 * {@link KeyframeShape#SQUARE} if the stored ordinal is out of range.
	 */
	public static KeyframeStyle getDefaultKeyframeStyle() {
        if (keyframeDefaultStyle != null) return keyframeDefaultStyle.get().copy();
        KeyframeStyle style = new KeyframeStyle();
        style.setShape(getDefaultKeyframeShape());
        return style;
    }

    public static KeyframeShape getDefaultKeyframeShape() {
        if (keyframeDefaultStyle != null) return keyframeDefaultStyle.get().getShape();
		if (keyframeDefaultShape == null) {
			return KeyframeShape.SQUARE;
		}

		int index = keyframeDefaultShape.get();
		KeyframeShape[] values = KeyframeShape.values();

		return index >= 0 && index < values.length ? values[index] : KeyframeShape.SQUARE;
	}

	public static IInterp getDefaultKeyframeInterpolation() {
		if (keyframeDefaultInterpolation == null) {
			return Interpolations.LINEAR;
		}

		IInterp interp = Interpolations.MAP.get(keyframeDefaultInterpolation.get());

		return interp == null ? Interpolations.LINEAR : interp;
	}

	public static boolean migrateLegacySettings(MapType root) {
		MapType appearance = root.getMap("appearance");
		MapType personalization = root.getMap("personalization");
		boolean personalizationMigrated = false;

		personalizationMigrated |= migrateLegacyValue(appearance, personalization, "primary_color");
		personalizationMigrated |= migrateLegacyValue(appearance, personalization, "tooltip_style", "theme");
		personalizationMigrated |= migrateLegacyValue(appearance, personalization, "track_width");
		personalizationMigrated |= migrateLegacyValue(appearance, personalization, "keyframe_default_shape");

		if (personalization.has("theme") && !root.getMap("skins").has("theme_id")) {
			MapType skins = root.getMap("skins");

			skins.putString("theme_id", personalization.getInt("theme") == LEGACY_LIGHT_THEME ? "light" : "dark");
			root.put("skins", skins);
			personalization.remove("theme");
			personalizationMigrated = true;
		}

		if (personalization.has("primary_color") && !root.getMap("skins").has("accent_follows_theme")) {
			if ((personalization.getInt("primary_color") & Colors.RGB) != DEFAULT_PRIMARY_COLOR) {
				MapType skins = root.getMap("skins");

				skins.putBool("accent_follows_theme", false);
				root.put("skins", skins);
				personalizationMigrated = true;
			}
		}

		if (personalizationMigrated) {
			root.put("personalization", personalization);
		}

		MapType skins = root.getMap("skins");
		boolean skinsMigrated = false;

		if (skins.has("motion_easing") && skins.get("motion_easing").isNumeric()) {
			int legacy = skins.getInt("motion_easing");
			String interpolation = legacy >= 0 && legacy < LEGACY_MOTION_EASINGS.length
				? LEGACY_MOTION_EASINGS[legacy]
				: DEFAULT_MOTION_EASING;

			skins.putString("motion_easing", interpolation);
			root.put("skins", skins);
			skinsMigrated = true;
		}

		MapType transformation = root.getMap("transformation");
		boolean transformationMigrated = false;

		transformationMigrated |= migrateLegacyValue(appearance, transformation, "axes_scale");
		transformationMigrated |= migrateLegacyValue(appearance, transformation, "axes_thickness");
		transformationMigrated |= migrateLegacyValue(appearance, transformation, "gizmos");
		transformationMigrated |= migrateLegacyValue(appearance, transformation, "transform_local_default");
		transformationMigrated |= migrateLegacyValue(transformation, transformation, "axes_keep_screen_size", "gizmo_keep_screen_size");
		transformationMigrated |= migrateLegacyValue(transformation, transformation, "rotate_3d_sphere", "gizmo_show_sphere");

		if (!transformation.has("gizmo_show_rotate") && transformation.has("rotate_hide_rings"))
		{
			transformation.putBool("gizmo_show_rotate", !transformation.getBool("rotate_hide_rings"));
			transformationMigrated = true;
		}

		if (!transformation.has("default_local") && transformation.has("transform_local_default"))
		{
			transformation.putBool("default_local", transformation.getBool("transform_local_default"));
			transformationMigrated = true;
		}

		/* The remembered space replaces the old initial-frame preference. */
		if (!transformation.has("transform_space") && transformation.has("default_local"))
		{
			transformation.putInt("transform_space", transformation.getBool("default_local") ? 0 : 3);
			transformationMigrated = true;
		}

		if (transformationMigrated) {
			root.put("transformation", transformation);
		}

		MapType video = root.getMap("video");
		MapType settings = video.getMap("settings");
		boolean videoMigrated = false;

		videoMigrated |= migrateLegacyValue(settings, video, "encoder_path");
		videoMigrated |= migrateLegacyValue(settings, video, "log");
		videoMigrated |= migrateLegacyValue(settings, video, "world_export_resize_window");
		videoMigrated |= migrateLegacyValue(settings, video, "width");
		videoMigrated |= migrateLegacyValue(settings, video, "height");
		videoMigrated |= migrateLegacyValue(settings, video, "frameRate", "frame_rate");
		videoMigrated |= migrateLegacyValue(settings, video, "exportPath", "export_path");
		videoMigrated |= migrateLegacyValue(settings, video, "audio");
		videoMigrated |= migrateLegacyValue(settings, video, "motionBlur", "motion_blur");
		videoMigrated |= migrateLegacyValue(settings, video, "heldFrames", "held_frames");
		videoMigrated |= migrateLegacyValue(settings, video, "delay");
		videoMigrated |= migrateLegacyValue(settings, video, "openFolderAfterExport", "open_folder_after_export");
		videoMigrated |= migrateLegacyValue(settings, video, "playSoundAfterExport", "play_sound_after_export");
		videoMigrated |= migrateLegacyValue(settings, video, "arguments");
		videoMigrated |= migrateLegacyValue(settings, video, "arguments_audio");

		/* Upgrade only the exact built-in templates.  A user-authored template
		 * remains theirs and is rejected explicitly if it cannot satisfy the new
		 * delivery contract. */
		if (video.has("arguments_audio")
			&& ValueVideoSettings.LEGACY_AUDIO_FFMPEG_ARGUMENTS.equals(video.getString("arguments_audio")))
		{
			video.putString("arguments_audio", DEFAULT_AUDIO_FFMPEG_ARGUMENTS);
			videoMigrated = true;
		}

		if (video.has("arguments_mux") && LEGACY_MUX_FFMPEG_ARGUMENTS.equals(video.getString("arguments_mux")))
		{
			video.putString("arguments_mux", DEFAULT_MUX_FFMPEG_ARGUMENTS);
			videoMigrated = true;
		}

		if (videoMigrated) {
			root.put("video", video);
		}

		boolean layoutMigrated = false;
		layoutMigrated |= migrateLegacyCategory(root, "editor", "camera",
			"speed", "angle_speed", "horizontal_flight", "camera_smoothness", "player_follows_camera",
			"orbit_movement_requires_flight", "orbit_center_marker", "orbit_gizmo", "orbit_gizmo_scale",
			"orbit_axis_ortho", "orbit_teleport_on_switch", "camera_mode");
		layoutMigrated |= migrateLegacyCategory(root, "editor", "viewport",
			"guides_color", "rule_of_thirds", "center_lines", "crosshair", "show_all_sound_guides",
			"preview_size_mode", "preview_custom_width", "preview_custom_height", "preview_resolution_scale",
			"clip_preview", "onion_skin", "motion_path", "ik_debug", "physics_debug");
		layoutMigrated |= migrateLegacyCategory(root, "editor", "timeline",
			"duration", "jump", "loop", "seconds", "timeline_grid", "keyframe_default_interpolation",
			"keyframe_preview", "snap_to_markers", "rewind", "restart_on_seek", "horizontal_clip_editor", "stop_playback_on_scrub");
		layoutMigrated |= migrateLegacyCategory(root, "editor", "workspace",
			"layout", "resizable_panels", "keyframe_panel_width", "periodic_save", "minutes_backup", "keep_frame_on_exit");
		/* 2.5 briefly registered these two editor values in workspace before the
		 * category split was completed. Read that shape too, without overriding a
		 * value already migrated to its final category. */
		layoutMigrated |= migrateLegacyValue(root, "workspace", "stop_playback_on_scrub", "timeline", "stop_playback_on_scrub");
		layoutMigrated |= migrateLegacyValue(root, "workspace", "preview_icons_auto_hide", "viewport", "preview_icons_auto_hide");
		layoutMigrated |= migrateLegacyValue(root, "workspace", "restart_on_seek", "timeline", "restart_on_seek");
		layoutMigrated |= migrateLegacyCategory(root, "debug", "viewport", "ik_debug", "physics_debug");
		layoutMigrated |= migrateLegacyValue(root, "personalization", "overlay_gradient_border", "personalization", "interface_glow");
		layoutMigrated |= migrateLegacyCategory(root, "personalization", "timeline", "track_width", "keyframe_default_shape");
        MapType timeline = root.getMap("timeline");
        if (timeline.has("keyframe_default_shape") && !timeline.has("keyframe_default_style")) {
            int ordinal = timeline.getInt("keyframe_default_shape");
            KeyframeStyle style = new KeyframeStyle();
            KeyframeShape[] shapes = KeyframeShape.values();
            style.setShape(ordinal >= 0 && ordinal < shapes.length ? shapes[ordinal] : KeyframeShape.SQUARE);
            MapType data = new MapType();
            style.toData(data);
            timeline.put("keyframe_default_style", data);
            root.put("timeline", timeline);
            layoutMigrated = true;
        }
		layoutMigrated |= migrateLegacyCategory(root, "appearance", "workspace", "clip_auto_name");
		layoutMigrated |= migrateLegacyValue(root, "dc", "enabled", "misc", "damage_control");
		layoutMigrated |= migrateLegacyValue(root, "shader_curves", "enabled", "misc", "shader_curves");
		layoutMigrated |= migrateLegacyValue(root, "multiskin", "multithreaded", "misc", "multiskin_multithreaded");
		layoutMigrated |= migrateLegacyValue(root, "entity_selectors", "whitelist", "misc", "entity_selectors_whitelist");
		layoutMigrated |= migrateLegacyCategory(root, "appearance", "performance", "list_model_preview", "freeze_models");
		layoutMigrated |= migrateLegacyCategory(root, "viewport", "performance", "profiler_overlay", "frame_pose_cache");
		layoutMigrated |= migrateLegacyCategory(root, "misc", "performance", "translucency_queue", "multiskin_multithreaded", "frustum_culling");
		layoutMigrated |= migrateLegacyValue(root, "recording", "pose_transform_overlays", "recording", "pose_overlays");
		layoutMigrated |= migrateLegacyValue(root, "recording", "pose_transform_overlays", "recording", "transform_overlays");

		/* Extra hotbar slots now fold under slot 0 instead of being filtered away by
		 * default. Clear that old filter once; manual filtering afterwards must survive
		 * reloads, so the flag is what keeps this from running again. */
		boolean hotbarFilterMigrated = false;

		if (!appearance.getBool("hotbar_filter_migrated")) {
			HashSet<String> slots = new HashSet<>();

			for (int i = 1; i < ReplayKeyframes.HOTBAR_SIZE; i++) {
				slots.add(ReplayKeyframes.hotbarChannelId(i));
			}

			appearance.getList("disabled_sheets").elements.removeIf(value -> value.isString() && slots.contains(value.asString()));
			appearance.putBool("hotbar_filter_migrated", true);
			root.put("appearance", appearance);
			hotbarFilterMigrated = true;
		}

		boolean replaySectionsMigrated = false;

		if (!appearance.getBool("replay_sections_filter_migrated"))
		{
			HashSet<String> revealed = new HashSet<>(ReplayKeyframes.CURATED_CHANNELS);
			revealed.addAll(Arrays.asList("leaning", "roll", "fall"));
			revealed.removeAll(Arrays.asList("yaw", "vX", "vY", "vZ"));
			appearance.getList("disabled_sheets").elements.removeIf(value -> value.isString() && revealed.contains(value.asString()));
			appearance.putBool("replay_sections_filter_migrated", true);
			root.put("appearance", appearance);
			replaySectionsMigrated = true;
		}

		return personalizationMigrated || skinsMigrated || transformationMigrated || videoMigrated || layoutMigrated || hotbarFilterMigrated || replaySectionsMigrated;
	}

	private static boolean migrateLegacyCategory(MapType root, String oldCategory, String newCategory, String... keys) {
		boolean migrated = false;

		for (String key : keys) {
			migrated |= migrateLegacyValue(root, oldCategory, key, newCategory, key);
		}

		return migrated;
	}

	private static boolean migrateLegacyValue(MapType root, String oldCategory, String oldKey, String newCategory, String newKey) {
		MapType oldMap = root.getMap(oldCategory);
		MapType newMap = root.getMap(newCategory);

		if (newMap.has(newKey) || !oldMap.has(oldKey)) {
			return false;
		}

		newMap.put(newKey, oldMap.get(oldKey).copy());
		root.put(newCategory, newMap);

		return true;
	}

	private static boolean migrateLegacyValue(MapType oldCategory, MapType newCategory, String key) {
		return migrateLegacyValue(oldCategory, newCategory, key, key);
	}

	private static boolean migrateLegacyValue(MapType oldCategory, MapType newCategory, String oldKey, String newKey) {
		if (newCategory.has(newKey) || !oldCategory.has(oldKey)) {
			return false;
		}

		newCategory.put(newKey, oldCategory.get(oldKey).copy());

		return true;
	}

	public static void register(SettingsBuilder builder) {
		/* Shared replay sections fold the curated channels instead of hiding them. */
		HashSet<String> defaultFilters = new HashSet<>(Arrays.asList("yaw", "vX", "vY", "vZ"));

		builder.category("appearance", Icons.LAYOUT);
		taskbarSide = builder.getInt("taskbar_side", 0);
		formCellSize = builder.getInt("form_cell_size", 60, 40, 140).slider();
		formCellSize.invisible();
		builder.register(language = new ValueLanguage("language"));
		enableTrackpadIncrements = builder.getBoolean("trackpad_increments", false);
		enableTrackpadScrolling = builder.getBoolean("trackpad_scrolling", false);
		userIntefaceScale = builder.getFloat("ui_scale", 2F, 0F, 4F).slider(0.25D);
		pixelArtSmoothing = builder.getBoolean("pixel_art_smoothing", true);
		fov = builder.getFloat("fov", 70, 0, 180);
		colorPickerHsvTab = builder.getBoolean("hsv_color_picker", true);
		colorPickerHsvTab.invisible();
		hsvColorPicker = colorPickerHsvTab;
		forceQwerty = builder.getBoolean("force_qwerty", false);
		freezeFormAnimations = builder.getBoolean("freeze_form_animations", false);
		morphingFocusSearch = builder.getBoolean("morphing_focus_search", false);
		uniformScale = builder.getBoolean("uniform_scale", false);
		clickSound = builder.getBoolean("click_sound", false);
		textureCellSize = builder.getInt("texture_cell_size", 80, 40, 200).slider();
		textureSort = builder.getString("texture_sort", "name");
		textureCellSize.invisible();
		textureSort.invisible();
		texturePins = new mchorse.bbs_mod.settings.values.core.ValueLinkList("texture_pins", List.of(mchorse.bbs_mod.resources.Link.assets("textures/")));
		texturePins.invisible();
		favoriteColors = new ValueColors("favorite_colors");
		recentColors = new ValueColors("recent_colors").limit(33);
		recentData = new mchorse.bbs_mod.settings.values.core.ValueRecentData("recent_data").limit(20);
		recentData.invisible();
		disabledSheets = new ValueStringKeys("disabled_sheets", defaultFilters);
		builder.register(favoriteColors);
		builder.register(recentColors);
		builder.register(recentData);
		builder.register(disabledSheets);
		builder.getBoolean("hotbar_filter_migrated", true).invisible();
		builder.getBoolean("replay_sections_filter_migrated", true).invisible();
		builder.register(texturePins);
		trackStyles = new mchorse.bbs_mod.settings.values.ui.ValueTrackStyles("track_styles");
		builder.register(trackStyles);
		disabledMorphFormCategories = new ValueStringKeys("disabled_morph_form_categories");
		builder.register(disabledMorphFormCategories);

		builder.category("personalization", Icons.COLOR);
		secondaryColor = builder.getInt("secondary_color", DEFAULT_SECONDARY_COLOR).color();
		backgroundBrightness = builder.getFloat("background_brightness", DEFAULT_BACKGROUND_BRIGHTNESS, MIN_BACKGROUND_BRIGHTNESS, MAX_BACKGROUND_BRIGHTNESS).slider();
		interfaceShadows = builder.getBoolean("interface_shadows", true);
		interfaceHighlights = builder.getBoolean("interface_highlights", false);
		overlayBackgroundOpacity = builder.getFloat("overlay_background_opacity", DEFAULT_OVERLAY_BACKGROUND_OPACITY, 0F, 1F).slider();
		interfaceGlow = builder.getBoolean("interface_glow", true);
		overlayGradientBorder = interfaceGlow;
		/* Dual kawase background blur: off by default — the framebuffer ping-pong needs an
		 * in-game Iris/Sodium smoke before it can be FSR's default (upstream ships it on). */
		interfaceBlur = builder.getBoolean("interface_blur", false);
		interfaceBlurRadius = builder.getInt("interface_blur_radius", 12, 1, 30).slider();
		primaryColor = builder.getInt("primary_color", DEFAULT_PRIMARY_COLOR).color();
		stencilHighlightColor = builder.getInt("stencil_highlight_color", 0x2EFFFFFF).colorAlpha();

		builder.category("skins", Icons.BRUSH);
		themeId = builder.getString("theme_id", ThemeManager.DEFAULT_THEME_ID);
		accentFollowsTheme = builder.getBoolean("accent_follows_theme", true);
		motionEnabled = builder.getBoolean("motion_enabled", true);
		motionSpeed = builder.getFloat("motion_speed", 1F, 0.5F, 2F);
		motionEasing = builder.getString("motion_easing", DEFAULT_MOTION_EASING);

		builder.category("refreshed", Icons.REFRESH);
		showTooltips = builder.getBoolean("show_tooltips", true);
		tooltipStyle = builder.getInt("tooltip_style", 0, 0, 2);
		textEntryOpenAnim = builder.getBoolean("text_entry_open_anim", true);
		filmEditorLayoutTransitionEnabled = builder.getBoolean("film_editor_layout_transition_enabled", true);
		dashboardAutoHideTaskbar = builder.getBoolean("dashboard_auto_hide_taskbar", false);
		dashboardTaskbarHideDelay = builder.getInt("dashboard_taskbar_hide_delay", 100, 100, 15000);
		editorVerticalLinesTimeline = builder.getBoolean("vertical_lines_timeline", false);

		builder.category("transformation", Icons.SCALE);
		gizmos = builder.getBoolean("gizmos", true);
		axesScale = builder.getFloat("axes_scale", 2F, 0F, 10F).slider();
		axesThickness = builder.getFloat("axes_thickness", 0.35F, 0.25F, 3F).slider();
		gizmoKeepScreenSize = builder.getBoolean("gizmo_keep_screen_size", true);
		axesKeepScreenSize = gizmoKeepScreenSize;
		gizmoPlaneSize = builder.getFloat("gizmo_plane_size", 2F, 0.25F, 3F).slider();
		gizmoShowTranslate = builder.getBoolean("gizmo_show_translate", true);
		gizmoShowScale = builder.getBoolean("gizmo_show_scale", true);
		gizmoShowRotate = builder.getBoolean("gizmo_show_rotate", true);
		gizmoShowViewRotate = builder.getBoolean("gizmo_show_view_rotate", true);
		gizmoShowSphere = builder.getBoolean("gizmo_show_sphere", true);
		rotate3dSphere = gizmoShowSphere;
		rotate3dSphereMode = builder.getInt("rotate_3d_sphere_mode", 0);
		rotateHideRings = new ValueBooleanInverse("rotate_hide_rings", gizmoShowRotate);
		hideInactiveHandles = builder.getBoolean("hide_inactive_handles", true);
		snapTranslate = builder.getFloat("snap_translate", 1F, 0.001F, 100F);
		snapRotate = builder.getFloat("snap_rotate", 5F, 0.001F, 90F);
		snapScale = builder.getFloat("snap_scale", 0.1F, 0.001F, 10F);
		gizmoHoverTolerance = builder.getInt("gizmo_hover_tolerance", 4, 0, 40).slider();
		gizmoOpacity = builder.getFloat("gizmo_opacity", 1F, 0.05F, 1F).slider();
		defaultLocalTransform = builder.getBoolean("default_local", false);
		defaultLocalTransform.invisible();
		transformSpace = builder.getInt("transform_space", 3);
		transformSpace.invisible();
		transformHotkeys3dRay = builder.getBoolean("hotkeys_3d_ray", true);
		transformHotkeys3dRay.invisible();
		poseMirrorEdit = builder.getBoolean("pose_mirror_edit", false);
		poseMirrorEdit.invisible();
		poseAlternateInvert = builder.getBoolean("pose_alternate_invert", false);
		poseAlternateInvert.invisible();
		poseShowDisabledBones = builder.getBoolean("pose_show_disabled_bones", false);
		translateHotkeyOrder = new ValueOrder("translate_hotkey_order", "screen", "x", "y", "z");
		builder.register(translateHotkeyOrder);
		scaleHotkeyOrder = new ValueOrder("scale_hotkey_order", "all", "x", "y", "z");
		builder.register(scaleHotkeyOrder);
		rotateHotkeyOrder = new ValueOrder("rotate_hotkey_order", "view", "sphere", "x", "y", "z");
		builder.register(rotateHotkeyOrder);
		trackballSensitivity = builder.getFloat("trackball_sensitivity", 1F, 0.05F, 2F).slider();

		builder.category("tutorials", Icons.HELP);
		enableCursorRendering = builder.getBoolean("cursor", false);
		enableMouseButtonRendering = builder.getBoolean("mouse_buttons", false);
		enableKeystrokeRendering = builder.getBoolean("keystrokes", false);
		keystrokeOffset = builder.getInt("keystrokes_offset", 10, 0, 20).slider();
		keystrokeMode = builder.getInt("keystrokes_position", 1);

		builder.category("background", Icons.IMAGE);
		backgroundImage = builder.getRL("image", null);
		backgroundColor = builder.getInt("color", 0xbf0c0c0c).colorAlpha();

		builder.category("chroma_sky", Icons.GLOBE);
		chromaSkyEnabled = builder.getBoolean("enabled", false);
		chromaSkyColor = builder.getInt("color", Colors.A75).color();
		chromaSkyTerrain = builder.getBoolean("terrain", true);
		chromaSkyBillboard = builder.getFloat("billboard", 0F, 0F, 256F);

		builder.category("scrollbars", Icons.VERTICAL);
		scrollbarWidth = builder.getInt("width", 4, 2, 10).slider();
		scrollingSensitivity = builder.getFloat("sensitivity", 3F, 0F, 10F).slider();
		scrollingSensitivityHorizontal = builder.getFloat("sensitivity_horizontal", 3F, 0F, 10F).slider();
		scrollingSmoothness = builder.getBoolean("smoothness", true);
		scrollingSmoothnessIntensity = builder.getFloat("smoothness_intensity", 0.75F, 0F, 2F).slider();
		scrollingDisableSmoothnessInEditors = builder.getBoolean("disable_smoothness_in_editors", true);
		scrollingUseThemeCurve = builder.getBoolean("use_theme_curve", true);
		scrollingMotionEasing = builder.getString("motion_easing", DEFAULT_MOTION_EASING);

		builder.category("video", Icons.VIDEO_CAMERA);
		videoEncoderPath = builder.getString("encoder_path", "ffmpeg");
		videoEncoderLog = builder.getBoolean("log", true);
		worldExportResizeWindow = builder.getBoolean("world_export_resize_window", false);
		videoWidth = builder.getInt("width", 1280, 2, 8096);
		videoHeight = builder.getInt("height", 720, 2, 8096);
		videoFrameRate = builder.getInt("frame_rate", 60, 10, 1000);
		videoLimitFrameRate = builder.getBoolean("limit_frame_rate", false);
		videoExportPath = builder.getString("export_path", "");
		videoExportFilenameFormat = builder.getString("filename_format", "{datetime}");
		videoExportAudio = builder.getBoolean("audio", false);
		videoAudioLayout = new ValueExportChannelLayout("audio_channel_layout");
		builder.register(videoAudioLayout);
		videoExportMinecraftSounds = builder.getBoolean("minecraft_sounds", false);
		videoMuteAudioWhileRender = builder.getBoolean("mute_audio_while_render", false);
		videoMotionBlur = builder.getInt("motion_blur", 0, 0, 6);
		videoHeldFrames = builder.getInt("held_frames", 1, 1, 1000);
		videoDelay = builder.getFloat("delay", 0.5F, 0F, 30F);
		videoOpenFolderAfterExport = builder.getBoolean("open_folder_after_export", false);
		videoPlaySoundAfterExport = builder.getBoolean("play_sound_after_export", true);
		videoArguments = builder.getString("arguments", DEFAULT_FFMPEG_ARGUMENTS);
		videoArgumentsAudio = builder.getString("arguments_audio", DEFAULT_AUDIO_FFMPEG_ARGUMENTS);
		videoArgumentsMux = builder.getString("arguments_mux", DEFAULT_MUX_FFMPEG_ARGUMENTS);
		videoSettings = ValueVideoSettings.bridge(
			"settings",
			videoArguments,
			videoArgumentsAudio,
			videoExportAudio,
			videoWidth,
			videoHeight,
			videoFrameRate,
			videoMotionBlur,
			videoHeldFrames,
			videoDelay,
			videoExportPath,
			videoOpenFolderAfterExport,
			videoPlaySoundAfterExport,
			videoAudioLayout
		);

		/* Camera editor */
		builder.category("camera", Icons.CAMERA);
		editorCameraSpeed = builder.getFloat("speed", 1F, 0.1F, 100F);
		editorCameraAngleSpeed = builder.getFloat("angle_speed", 1F, 0.1F, 100F);
		editorHorizontalFlight = builder.getBoolean("horizontal_flight", false);
		editorFlightFreeLook = builder.getBoolean("flight_free_look", false);
		editorOrbitMovementRequiresFlight = builder.getBoolean("orbit_movement_requires_flight", true);
		editorOrbitCenterMarker = builder.getBoolean("orbit_center_marker", false);
		editorOrbitGizmo = builder.getBoolean("orbit_gizmo", true);
		editorOrbitGizmoScale = builder.getFloat("orbit_gizmo_scale", 0.75F, 0.5F, 2F).slider();
		editorOrbitAxisOrtho = builder.getBoolean("orbit_axis_ortho", true);
		editorOrbitTeleportOnSwitch = builder.getBoolean("orbit_teleport_on_switch", true);
		editorCameraSmoothness = builder.getFloat("camera_smoothness", 0.1F, 0F, 0.95F).slider();
		editorCameraMode = builder.getInt("camera_mode", 0, 0, 5);
		editorCameraMode.invisible();
		editorPlayerFollowsCamera = builder.getBoolean("player_follows_camera", false);

		builder.category("viewport", Icons.FRUSTUM);
		editorGuidesColor = builder.getInt("guides_color", 0x7fffffff).colorAlpha();
		editorRuleOfThirds = builder.getBoolean("rule_of_thirds", false);
		editorCenterLines = builder.getBoolean("center_lines", false);
		editorCrosshair = builder.getBoolean("crosshair", false);
		editorShowAllSoundGuides = builder.getBoolean("show_all_sound_guides", false);
		editorClipPreview = builder.getBoolean("clip_preview", true);
		editorPreviewSizeMode = builder.getInt("preview_size_mode", 2, 0, 2);
		editorPreviewSelectionHud = builder.getBoolean("preview_selection_hud", true);
		editorPreviewCustomWidth = builder.getInt("preview_custom_width", 1280, 2, 16384);
		editorPreviewCustomHeight = builder.getInt("preview_custom_height", 720, 2, 16384);
		editorPreviewResolutionScale = builder.getFloat("preview_resolution_scale", 2F, 1F, 3F).slider();
		editorPreviewIconsAutoHide = builder.getBoolean("preview_icons_auto_hide", true);
		builder.register(editorOnionSkin = new ValueOnionSkin("onion_skin"));
		builder.register(editorMotionPath = new ValueMotionPath("motion_path"));
		builder.register(ikDebug = new ValueIKDebug("ik_debug"));
		builder.register(physicsDebug = new ValuePhysicsDebug("physics_debug"));

		builder.category("performance", Icons.PROCESSOR);
		listModelPreview = builder.getBoolean("list_model_preview", true);
		freezeModels = builder.getBoolean("freeze_models", false);
		translucencyQueue = builder.getBoolean("translucency_queue", false);
		multiskinMultiThreaded = builder.getBoolean("multiskin_multithreaded", true);
		frustumCulling = builder.getBoolean("frustum_culling", true);
		profilerOverlay = builder.getBoolean("profiler_overlay", false);
		framePoseCache = builder.getBoolean("frame_pose_cache", true);
		framePoseCache.invisible();

		builder.category("timeline", Icons.TIME);
		duration = builder.getInt("duration", 100, 1, 1000);
		editorJump = builder.getInt("jump", 5, 1, 1000);
		editorLoop = builder.getBoolean("loop", false);
		editorSeconds = builder.getBoolean("seconds", false);
		editorTimelineGrid = builder.getBoolean("timeline_grid", true);
		keyframeDefaultInterpolation = builder.getString("keyframe_default_interpolation", Interpolations.LINEAR.getKey());
		keyframePreview = builder.getBoolean("keyframe_preview", true);
		editorTrackWidth = builder.getInt("track_width", 2, 1, 10).slider();
		builder.register(keyframeDefaultStyle = new ValueKeyframeStyle("keyframe_default_style"));
        keyframeDefaultShape = builder.getInt("keyframe_default_shape", 0, 0, KeyframeShape.values().length - 1);
        keyframeDefaultShape.invisible();
        keyframeDefaultShape.postCallback((value, flag) -> {
            KeyframeStyle style = keyframeDefaultStyle.get().copy();
            style.setShape(KeyframeShape.values()[keyframeDefaultShape.get()]);
            keyframeDefaultStyle.set(style, flag);
        });
		editorSnapToMarkers = builder.getBoolean("snap_to_markers", false);
		editorSnapToTicks = builder.getBoolean("snap_to_ticks", true);
        autoKeyframe = builder.getBoolean("auto_keyframe", false);
		editorSnapToFilmMarkers = builder.getBoolean("snap_to_film_markers", true);
		editorRewind = builder.getBoolean("rewind", true);
		editorHorizontalClipEditor = builder.getBoolean("horizontal_clip_editor", false);
		editorStopPlaybackOnScrub = builder.getBoolean("stop_playback_on_scrub", true);
		editorRestartOnSeek = builder.getBoolean("restart_on_seek", false);
		anchorKeepTransform = builder.getBoolean("anchor_keep_transform", false);

		builder.category("workspace", Icons.EDITOR);
		builder.register(editorLayoutSettings = new ValueEditorLayout("layout"));
		editorPeriodicSave = builder.getInt("periodic_save", 60, 0, 3600);
		editorMinutesBackup = builder.getBoolean("minutes_backup", true);
		editorResizablePanels = builder.getBoolean("resizable_panels", true);
		editorKeyframePanelWidth = builder.getInt("keyframe_panel_width", 140, 80, 480);
		editorClipAutoName = builder.getBoolean("clip_auto_name", true);
		editorKeepFrameOnExit = builder.getBoolean("keep_frame_on_exit", false);

		builder.category("recording", Icons.FILM);
		recordingCountdown = builder.getFloat("countdown", 1.5F, 0F, 30F);
		recordingSwipeDamage = builder.getBoolean("swipe_damage", false);
		recordingOverlays = builder.getBoolean("overlays", true);
		editorReplayTabs = builder.getBoolean("replay_tabs", true);
		recordingPoseOverlays = builder.getInt("pose_overlays", 0, 0, 42);
		recordingTransformOverlays = builder.getInt("transform_overlays", 0, 0, 42);
		recordingPoseTransformOverlays = builder.getInt("pose_transform_overlays", 0, 0, 42);
		recordingPoseTransformOverlays.invisible();
		recordingPoseTransformOverlays.postCallback((value, flag) -> {
			recordingPoseOverlays.set(recordingPoseTransformOverlays.get());
			recordingTransformOverlays.set(recordingPoseTransformOverlays.get());
		});
		recordingCameraPreview = builder.getBoolean("camera_preview", true);
		recordingTeleport = builder.getBoolean("teleport", true);

		builder.category("model_blocks", Icons.BLOCK);
		renderAllModelBlocks = builder.getBoolean("render_all", true);
		clickModelBlocks = builder.getBoolean("click", true);

		builder.category("misc", Icons.MORE);
		entitySelectorsPropertyWhitelist = builder.getString("entity_selectors_whitelist", "CustomName,Name");
		damageControl = builder.getBoolean("damage_control", true);
		shaderCurvesEnabled = builder.getBoolean("shader_curves", true);

		builder.category("audio", Icons.SOUND);
		audioWaveformVisibleInPreview = builder.getBoolean("waveform_visible_preview", true);
		audioWaveformVisibleInKeyframes = builder.getBoolean("waveform_visible_keyframes", true);
		audioWaveformDensity = builder.getInt("waveform_density", 20, 10, 100).slider();
		audioWaveformWidth = builder.getFloat("waveform_width", 0.8F, 0F, 1F).slider();
		audioWaveformHeight = builder.getInt("waveform_height", 24, 10, 40).slider();
		audioWaveformFilename = builder.getBoolean("waveform_filename", false);
		audioWaveformTime = builder.getBoolean("waveform_time", false);
		audioWaveformPreviewCombined = builder.getBoolean("waveform_preview_combined", false);

		builder.category("cdn", Icons.SERVER);
		cdnUrl = builder.getString("url", "");
		cdnToken = builder.getString("token", "");

		builder.category("fsr_updates", Icons.DOWNLOAD);
		updateEnabled = builder.getBoolean("update_enabled", true);
		updateChannel = builder.getInt("update_channel", 0, 0, 1);
		updateInterval = builder.getInt("update_interval", 2, 0, 4);
		updateSkippedVersion = builder.getString("update_skipped_version", "");
		updateSkippedVersion.invisible();
		updateLastCheck = new ValueLong("update_last_check", 0L);
		updateLastCheck.invisible();
		builder.register(updateLastCheck);
		/* Invisible trigger value whose settings row hosts the "check now"
		 * button and the live update status line. */
		updateCheckTrigger = builder.getBoolean("update_check_trigger", false);
	}
}
