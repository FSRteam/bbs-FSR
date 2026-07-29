package mchorse.bbs_mod;

import java.util.HashSet;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
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
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;

public class BBSSettings {

	public static final String DEFAULT_FFMPEG_ARGUMENTS = ValueVideoSettings.DEFAULT_FFMPEG_ARGUMENTS;
	public static final String DEFAULT_AUDIO_FFMPEG_ARGUMENTS = ValueVideoSettings.DEFAULT_AUDIO_FFMPEG_ARGUMENTS;
	public static final String DEFAULT_MUX_FFMPEG_ARGUMENTS = mchorse.bbs_mod.utils.VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS;
	private static final String LEGACY_MUX_FFMPEG_ARGUMENTS =
		"-y -i %VIDEO% -i %AUDIO_TRACK% -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k -shortest %NAME%.mp4";

	public static ValueColors favoriteColors;
	public static ValueColors recentColors;
	public static ValueStringKeys disabledSheets;
	public static ValueStringKeys disabledMorphFormCategories;
	public static ValueLanguage language;
	public static ValueInt primaryColor;
	public static ValueInt stencilHighlightColor;
	public static ValueBoolean enableTrackpadIncrements;
	public static ValueBoolean enableTrackpadScrolling;
	public static ValueFloat userIntefaceScale;
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
	public static ValueInt dashboardTaskbarHideDelay;
	public static ValueBoolean editorVerticalLinesTimeline;
	public static ValueFloat fov;
	public static ValueBoolean hsvColorPicker;
	public static ValueBoolean forceQwerty;
	public static ValueBoolean freezeModels;
	public static ValueBoolean listModelPreview;
	public static ValueBoolean morphingFocusSearch;
	public static ValueFloat axesScale;
	public static ValueFloat axesThickness;
	public static ValueBoolean axesKeepScreenSize;
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
	public static ValueBoolean scrollingDisableSmoothnessInEditors;

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
	public static ValueInt editorPeriodicSave;
	public static ValueBoolean editorHorizontalFlight;
	public static ValueBoolean editorOrbitMovementRequiresFlight;
	public static ValueBoolean editorOrbitCenterMarker;
	public static ValueBoolean editorOrbitTeleportOnSwitch;
	public static ValueFloat editorCameraSmoothness;
	public static ValueInt editorCameraMode;
	public static ValueBoolean editorPlayerFollowsCamera;
	public static ValueEditorLayout editorLayoutSettings;
	public static ValueOnionSkin editorOnionSkin;
	public static ValueMotionPath editorMotionPath;
	public static ValueIKDebug ikDebug;
	public static ValuePhysicsDebug physicsDebug;
	public static ValueBoolean editorSnapToMarkers;
	public static ValueBoolean editorClipPreview;
	public static ValueBoolean editorRewind;
	public static ValueBoolean editorHorizontalClipEditor;
	public static ValueBoolean editorMinutesBackup;
	public static ValueBoolean editorResizablePanels;
	public static ValueInt editorTrackWidth;
	public static ValueInt keyframeDefaultShape;
	public static ValueInt editorPreviewSizeMode;
	public static ValueInt editorPreviewCustomWidth;
	public static ValueInt editorPreviewCustomHeight;
	public static ValueFloat editorPreviewResolutionScale;
	public static ValueBoolean editorClipAutoName;

	public static ValueFloat recordingCountdown;
	public static ValueBoolean recordingSwipeDamage;
	public static ValueBoolean editorReplayTabs;
	public static ValueBoolean recordingOverlays;
	public static ValueInt recordingPoseTransformOverlays;
	public static ValueBoolean recordingCameraPreview;

	public static ValueBoolean renderAllModelBlocks;
	public static ValueBoolean clickModelBlocks;

	public static ValueString entitySelectorsPropertyWhitelist;

	public static ValueBoolean damageControl;

	public static ValueFloat backgroundBrightness;
	public static ValueBoolean interfaceShadows;

	public static ValueBoolean shaderCurvesEnabled;

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
		return ThemeManager.current().light;
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
		return applyBackgroundBrightness(ThemeManager.current().surfaceChrome);
	}

	public static int baseSurface() {
		return applyBackgroundBrightness(ThemeManager.current().surfaceBase);
	}

	public static int raisedSurface() {
		return applyBackgroundBrightness(ThemeManager.current().surfaceRaised);
	}

	public static int deepSurface() {
		return applyBackgroundBrightness(ThemeManager.current().surfaceDeep);
	}

	public static int dividerColor() {
		return ThemeManager.current().surfaceDivider;
	}

	public static int textColor() {
		return ThemeManager.current().textPrimary;
	}

	public static int mutedTextColor() {
		return ThemeManager.current().textMuted;
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
		return applyBackgroundBrightness(ThemeManager.current().fieldFill);
	}

	public static int fieldBorderColor() {
		return ThemeManager.current().fieldBorder;
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
		return ThemeManager.current().textShadow;
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

	public static int getDefaultDuration() {
		return duration == null ? 30 : duration.get();
	}

	public static float getFov() {
		return MathUtils.toRad(BBSSettings.fov.get());
	}

	public static float getAxesDistanceScale(float distance) {
		return getAxesDistanceScale(distance, getFov());
	}

	public static float getAxesDistanceScale(float distance, float fov) {
		if (axesKeepScreenSize != null && axesKeepScreenSize.get()) {
			float tanFov = (float) Math.tan(fov / 2.0);
			float scale = (distance / 5F) * (tanFov / 0.4663F);

			return Math.max(0.0001F, scale);
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
	public static KeyframeShape getDefaultKeyframeShape() {
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

		if (!transformation.has("default_local") && transformation.has("transform_local_default"))
		{
			transformation.putBool("default_local", transformation.getBool("transform_local_default"));
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

		return personalizationMigrated || skinsMigrated || transformationMigrated || videoMigrated;
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
		HashSet<String> defaultFilters = new HashSet<>();

		defaultFilters.add("item_off_hand");
		defaultFilters.add("item_head");
		defaultFilters.add("item_chest");
		defaultFilters.add("item_legs");
		defaultFilters.add("item_feet");
		defaultFilters.add("vX");
		defaultFilters.add("vY");
		defaultFilters.add("vZ");
		defaultFilters.add("grounded");
		defaultFilters.add("stick_rx");
		defaultFilters.add("stick_ry");
		defaultFilters.add("trigger_l");
		defaultFilters.add("trigger_r");
		defaultFilters.add("extra1_x");
		defaultFilters.add("extra1_y");
		defaultFilters.add("extra2_x");
		defaultFilters.add("extra2_y");

		builder.category("appearance", Icons.LAYOUT);
		builder.register(language = new ValueLanguage("language"));
		enableTrackpadIncrements = builder.getBoolean("trackpad_increments", false);
		enableTrackpadScrolling = builder.getBoolean("trackpad_scrolling", false);
		userIntefaceScale = builder.getFloat("ui_scale", 2F, 0F, 4F);
		fov = builder.getFloat("fov", 40, 0, 180);
		hsvColorPicker = builder.getBoolean("hsv_color_picker", true);
		forceQwerty = builder.getBoolean("force_qwerty", false);
		freezeModels = builder.getBoolean("freeze_models", false);
		listModelPreview = builder.getBoolean("list_model_preview", true);
		morphingFocusSearch = builder.getBoolean("morphing_focus_search", false);
		uniformScale = builder.getBoolean("uniform_scale", false);
		clickSound = builder.getBoolean("click_sound", false);
		favoriteColors = new ValueColors("favorite_colors");
		recentColors = new ValueColors("recent_colors").limit(33);
		disabledSheets = new ValueStringKeys("disabled_sheets");
		disabledSheets.set(defaultFilters);
		builder.register(favoriteColors);
		builder.register(recentColors);
		builder.register(disabledSheets);
		disabledMorphFormCategories = new ValueStringKeys("disabled_morph_form_categories");
		builder.register(disabledMorphFormCategories);
		editorClipAutoName = builder.getBoolean("clip_auto_name", true);

		builder.category("personalization", Icons.COLOR);
		backgroundBrightness = builder.getFloat("background_brightness", DEFAULT_BACKGROUND_BRIGHTNESS, MIN_BACKGROUND_BRIGHTNESS, MAX_BACKGROUND_BRIGHTNESS);
		interfaceShadows = builder.getBoolean("interface_shadows", true);
		primaryColor = builder.getInt("primary_color", DEFAULT_PRIMARY_COLOR).color();
		stencilHighlightColor = builder.getInt("stencil_highlight_color", 0x2EFFFFFF).colorAlpha();
		editorTrackWidth = builder.getInt("track_width", 2, 1, 10);
		keyframeDefaultShape = builder.getInt("keyframe_default_shape", 0, 0, KeyframeShape.values().length - 1);

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
		axesScale = builder.getFloat("axes_scale", 2F, 0F, 10F);
		axesThickness = builder.getFloat("axes_thickness", 0.35F, 0.25F, 3F);
		axesKeepScreenSize = builder.getBoolean("axes_keep_screen_size", true);
		rotate3dSphere = builder.getBoolean("rotate_3d_sphere", true);
		rotate3dSphereMode = builder.getInt("rotate_3d_sphere_mode", 0);
		rotateHideRings = builder.getBoolean("rotate_hide_rings", false);
		hideInactiveHandles = builder.getBoolean("hide_inactive_handles", true);
		snapTranslate = builder.getFloat("snap_translate", 1F, 0.001F, 100F);
		snapRotate = builder.getFloat("snap_rotate", 5F, 0.001F, 90F);
		snapScale = builder.getFloat("snap_scale", 0.1F, 0.001F, 10F);
		gizmoHoverTolerance = builder.getInt("gizmo_hover_tolerance", 8, 0, 40);
		gizmoOpacity = builder.getFloat("gizmo_opacity", 1F, 0.05F, 1F);
		defaultLocalTransform = builder.getBoolean("default_local", false);
		transformHotkeys3dRay = builder.getBoolean("hotkeys_3d_ray", true);
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
		trackballSensitivity = builder.getFloat("trackball_sensitivity", 1F, 0.05F, 2F);

		builder.category("tutorials", Icons.HELP);
		enableCursorRendering = builder.getBoolean("cursor", false);
		enableMouseButtonRendering = builder.getBoolean("mouse_buttons", false);
		enableKeystrokeRendering = builder.getBoolean("keystrokes", false);
		keystrokeOffset = builder.getInt("keystrokes_offset", 10, 0, 20);
		keystrokeMode = builder.getInt("keystrokes_position", 1);

		builder.category("background", Icons.IMAGE);
		backgroundImage = builder.getRL("image", null);
		backgroundColor = builder.getInt("color", 0x7b000000).colorAlpha();

		builder.category("chroma_sky", Icons.GLOBE);
		chromaSkyEnabled = builder.getBoolean("enabled", false);
		chromaSkyColor = builder.getInt("color", Colors.A75).color();
		chromaSkyTerrain = builder.getBoolean("terrain", true);
		chromaSkyBillboard = builder.getFloat("billboard", 0F, 0F, 256F);

		builder.category("scrollbars", Icons.VERTICAL);
		scrollbarWidth = builder.getInt("width", 4, 2, 10);
		scrollingSensitivity = builder.getFloat("sensitivity", 3F, 0F, 10F);
		scrollingSensitivityHorizontal = builder.getFloat("sensitivity_horizontal", 3F, 0F, 10F);
		scrollingSmoothness = builder.getBoolean("smoothness", true);
		scrollingDisableSmoothnessInEditors = builder.getBoolean("disable_smoothness_in_editors", false);

		builder.category("multiskin", Icons.USER);
		multiskinMultiThreaded = builder.getBoolean("multithreaded", true);

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
		builder.category("editor", Icons.EDITOR);
		editorCameraSpeed = builder.getFloat("speed", 1F, 0.1F, 100F);
		editorCameraAngleSpeed = builder.getFloat("angle_speed", 1F, 0.1F, 100F);
		duration = builder.getInt("duration", 30, 1, 1000);
		editorJump = builder.getInt("jump", 5, 1, 1000);
		editorLoop = builder.getBoolean("loop", false);
		editorGuidesColor = builder.getInt("guides_color", 0xcccc0000).colorAlpha();
		editorRuleOfThirds = builder.getBoolean("rule_of_thirds", false);
		editorCenterLines = builder.getBoolean("center_lines", false);
		editorCrosshair = builder.getBoolean("crosshair", false);
		editorShowAllSoundGuides = builder.getBoolean("show_all_sound_guides", false);
		editorSeconds = builder.getBoolean("seconds", false);
		editorTimelineGrid = builder.getBoolean("timeline_grid", false);
		keyframeDefaultInterpolation = builder.getString("keyframe_default_interpolation", Interpolations.LINEAR.getKey());
		editorPeriodicSave = builder.getInt("periodic_save", 60, 0, 3600);
		editorHorizontalFlight = builder.getBoolean("horizontal_flight", false);
		editorOrbitMovementRequiresFlight = builder.getBoolean("orbit_movement_requires_flight", true);
		editorOrbitCenterMarker = builder.getBoolean("orbit_center_marker", false);
		editorOrbitTeleportOnSwitch = builder.getBoolean("orbit_teleport_on_switch", true);
		editorCameraSmoothness = builder.getFloat("camera_smoothness", 0.1F, 0F, 0.95F);
		editorCameraMode = builder.getInt("camera_mode", 0, 0, 5);
		editorCameraMode.invisible();
		editorPlayerFollowsCamera = builder.getBoolean("player_follows_camera", false);
		builder.register(editorLayoutSettings = new ValueEditorLayout("layout"));
		builder.register(editorOnionSkin = new ValueOnionSkin("onion_skin"));
		builder.register(editorMotionPath = new ValueMotionPath("motion_path"));
		builder.register(ikDebug = new ValueIKDebug("ik_debug"));
		builder.register(physicsDebug = new ValuePhysicsDebug("physics_debug"));
		editorSnapToMarkers = builder.getBoolean("snap_to_markers", false);
		editorClipPreview = builder.getBoolean("clip_preview", true);
		editorRewind = builder.getBoolean("rewind", true);
		editorHorizontalClipEditor = builder.getBoolean("horizontal_clip_editor", true);
		editorMinutesBackup = builder.getBoolean("minutes_backup", true);
		editorResizablePanels = builder.getBoolean("resizable_panels", true);
		editorPreviewSizeMode = builder.getInt("preview_size_mode", 0, 0, 2);
		editorPreviewCustomWidth = builder.getInt("preview_custom_width", 1280, 2, 16384);
		editorPreviewCustomHeight = builder.getInt("preview_custom_height", 720, 2, 16384);
		editorPreviewResolutionScale = builder.getFloat("preview_resolution_scale", 2F, 1F, 3F);

		builder.category("recording", Icons.FILM);
		recordingCountdown = builder.getFloat("countdown", 1.5F, 0F, 30F);
		recordingSwipeDamage = builder.getBoolean("swipe_damage", false);
		recordingOverlays = builder.getBoolean("overlays", true);
		editorReplayTabs = builder.getBoolean("replay_tabs", true);
		recordingPoseTransformOverlays = builder.getInt("pose_transform_overlays", 0, 0, 42);
		recordingCameraPreview = builder.getBoolean("camera_preview", true);

		builder.category("model_blocks", Icons.BLOCK);
		renderAllModelBlocks = builder.getBoolean("render_all", true);
		clickModelBlocks = builder.getBoolean("click", true);

		builder.category("entity_selectors", Icons.POINTER);
		entitySelectorsPropertyWhitelist = builder.getString("whitelist", "CustomName,Name");

		builder.category("dc", Icons.EXCLAMATION);
		damageControl = builder.getBoolean("enabled", true);

		builder.category("shader_curves", Icons.CURVES);
		shaderCurvesEnabled = builder.getBoolean("enabled", true);

		builder.category("audio", Icons.SOUND);
		audioWaveformVisibleInPreview = builder.getBoolean("waveform_visible_preview", true);
		audioWaveformVisibleInKeyframes = builder.getBoolean("waveform_visible_keyframes", true);
		audioWaveformDensity = builder.getInt("waveform_density", 20, 10, 100);
		audioWaveformWidth = builder.getFloat("waveform_width", 0.8F, 0F, 1F);
		audioWaveformHeight = builder.getInt("waveform_height", 24, 10, 40);
		audioWaveformFilename = builder.getBoolean("waveform_filename", false);
		audioWaveformTime = builder.getBoolean("waveform_time", false);
		audioWaveformPreviewCombined = builder.getBoolean("waveform_preview_combined", false);

		builder.category("cdn", Icons.SERVER);
		cdnUrl = builder.getString("url", "");
		cdnToken = builder.getString("token", "");
	}
}
