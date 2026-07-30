package mchorse.bbs_mod.ui.film.replays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.Waveform;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraUtils;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.ik.ModelIKRuntime;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsConfig;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsIO;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.overlays.UIAnimationToPoseOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.overlays.UIKeyframeSheetFilterOverlayPanel;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.renderers.TimelineRulerRenderer;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.RayTracing;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

public class UIReplaysEditor extends UIElement {

    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static final Map<String, Icon> ICONS = new HashMap<>();
    private static String lastFilm = "";
    private static int lastReplay;

    public UIReplaysListPanel replaysList;
    public UIReplayPropertiesPanel replayProperties;

    private static final int CATEGORY_BAR_WIDTH = 20;

    public UIElement iconBar;
    public Map<ReplayCategory, UIIcon> tabButtons = new HashMap<>();
    private ReplayCategory category = ReplayCategory.PLAYER;

    /* «All tracks» view: shows every category's tracks at once, bypassing the category filter. */
    private UIIcon allToggle;
    private boolean allMode;

    /* Keyframes */
    public UIKeyframeEditor keyframeEditor;

    /* Clips */
    private UIFilmPanel filmPanel;
    private Film film;
    private Replay replay;
    private boolean settingReplay;
    private Pair<Form, String> pendingPick;
    private long pendingPickGeneration;
    /**
     * Monotonically identifies the latest keyframe-editor rebuild.  Hierarchy
     * mutations are deferred while a mouse dispatch is active, so the field
     * can temporarily point at an editor which is not mounted yet.
     */
    private long keyframeEditorGeneration;
    private boolean keyframeEditorResetPending;
    private boolean timelineVisible = true;
    private boolean propertiesVisible = true;
    private Set<String> keys = new LinkedHashSet<>();
    private final Map<String, Set<String>> expandedPoseTabsByReplay = new HashMap<>();

    public enum ReplayCategory {
        PLAYER(
                Icons.PLAYER,
                L10n.lang("bbs.ui.film.replays.category.player"),
                L10n.lang("bbs.ui.film.replays.category.player.tooltip")
        ),
        MODEL(
                Icons.BLOCK,
                L10n.lang("bbs.ui.film.replays.category.model"),
                L10n.lang("bbs.ui.film.replays.category.model.tooltip")
        ),
        POSE(
                Icons.POSE,
                L10n.lang("bbs.ui.film.replays.category.pose"),
                L10n.lang("bbs.ui.film.replays.category.pose.tooltip")
        ),
        IK(
                Icons.LIMB,
                L10n.lang("bbs.ui.film.replays.category.ik"),
                L10n.lang("bbs.ui.film.replays.category.ik.tooltip")
        ),
        PHYSICS(
                Icons.DROP,
                L10n.lang("bbs.ui.film.replays.category.physics"),
                L10n.lang("bbs.ui.film.replays.category.physics.tooltip")
        );

        public final Icon icon;
        public final IKey label;
        public final IKey tooltip;

        private ReplayCategory(Icon icon, IKey label, IKey tooltip) {
            this.icon = icon;
            this.label = label;
            this.tooltip = tooltip;
        }
    }

    static {
        COLORS.put("x", Colors.RED);
        COLORS.put("y", Colors.GREEN);
        COLORS.put("z", Colors.BLUE);
        COLORS.put("vX", Colors.RED);
        COLORS.put("vY", Colors.GREEN);
        COLORS.put("vZ", Colors.BLUE);
        COLORS.put("yaw", Colors.YELLOW);
        COLORS.put("pitch", Colors.CYAN);
        COLORS.put("bodyYaw", Colors.MAGENTA);

        COLORS.put("stick_lx", Colors.RED);
        COLORS.put("stick_ly", Colors.GREEN);
        COLORS.put("stick_rx", Colors.RED);
        COLORS.put("stick_ry", Colors.GREEN);
        COLORS.put("trigger_l", Colors.RED);
        COLORS.put("trigger_r", Colors.GREEN);
        COLORS.put("extra1_x", Colors.RED);
        COLORS.put("extra1_y", Colors.GREEN);
        COLORS.put("extra2_x", Colors.RED);
        COLORS.put("extra2_y", Colors.GREEN);

        COLORS.put("visible", Colors.WHITE & Colors.RGB);
        COLORS.put("pose", Colors.RED);
        COLORS.put("pose_overlay", Colors.ORANGE);
        COLORS.put("transform", Colors.GREEN);
        COLORS.put("transform_overlay", 0xaaff00);
        COLORS.put("color", Colors.INACTIVE);
        COLORS.put("lighting", Colors.YELLOW);
        COLORS.put("shape_keys", Colors.PINK);
        COLORS.put("actions", Colors.MAGENTA);

        COLORS.put("item_main_hand", Colors.ORANGE);
        COLORS.put("item_off_hand", Colors.ORANGE);
        COLORS.put("item_head", Colors.ORANGE);
        COLORS.put("item_chest", Colors.ORANGE);
        COLORS.put("item_legs", Colors.ORANGE);
        COLORS.put("item_feet", Colors.ORANGE);

        COLORS.put("user1", Colors.RED);
        COLORS.put("user2", Colors.ORANGE);
        COLORS.put("user3", Colors.GREEN);
        COLORS.put("user4", Colors.BLUE);
        COLORS.put("user5", Colors.RED);
        COLORS.put("user6", Colors.ORANGE);

        COLORS.put("frequency", Colors.RED);
        COLORS.put("count", Colors.GREEN);

        COLORS.put("settings", Colors.MAGENTA);
        COLORS.put("offset_x", Colors.RED);
        COLORS.put("offset_y", Colors.GREEN);
        COLORS.put("offset_z", Colors.BLUE);

        ICONS.put("x", Icons.X);
        ICONS.put("y", Icons.Y);
        ICONS.put("z", Icons.Z);

        ICONS.put("pitch", Icons.VERTICAL);
        ICONS.put("headYaw", Icons.HORIZONTAL);

        ICONS.put("visible", Icons.VISIBLE);
        ICONS.put("texture", Icons.MATERIAL);
        ICONS.put("pose", Icons.POSE);
        ICONS.put("transform", Icons.ALL_DIRECTIONS);
        ICONS.put("color", Icons.BUCKET);
        ICONS.put("lighting", Icons.LIGHT);
        ICONS.put("actions", Icons.CONVERT);
        ICONS.put("shape_keys", Icons.HEART_ALT);
        ICONS.put("text", Icons.FONT);

        ICONS.put("stick_lx", Icons.LEFT_STICK);
        ICONS.put("stick_rx", Icons.RIGHT_STICK);
        ICONS.put("trigger_l", Icons.TRIGGER);
        ICONS.put("extra1_x", Icons.CURVES);
        ICONS.put("extra2_x", Icons.CURVES);
        ICONS.put("item_main_hand", Icons.LIMB);

        ICONS.put("user1", Icons.PARTICLE);

        ICONS.put("paused", Icons.TIME);
        ICONS.put("frequency", Icons.STOPWATCH);
        ICONS.put("count", Icons.BUCKET);

        ICONS.put("settings", Icons.GEAR);
    }

    public static Icon getIcon(String key) {
        String topLevel = StringUtils.fileName(key);

        return ICONS.getOrDefault(topLevel, Icons.NONE);
    }

    public static int getColor(String key) {
        String topLevel = StringUtils.fileName(key);

        if (topLevel.startsWith("pose_overlay")) {
            return COLORS.get("pose_overlay");
        }
        if (topLevel.startsWith("transform_overlay")) {
            return COLORS.get("transform_overlay");
        }

        if (COLORS.containsKey(topLevel)) {
            return COLORS.get(topLevel);
        }
        return Colors.BLUE;
    }

    /** The key a sheet is identified by in track filters (global and per-form). */
    public static String getSheetFilterKey(UIKeyframeSheet sheet) {
        if (sheet.isBoneTrack)
        {
            PerLimbService.PoseBonePath path = PerLimbService.parsePoseBonePath(sheet.id);

            if (path != null)
            {
                return path.formPath().isEmpty() ? path.bone() : path.formPath() + "/" + path.bone();
            }

            return sheet.title.get();
        }

        return StringUtils.fileName(sheet.id);
    }

    /** The form a sheet belongs to, whether it backs a form property or carries its owner directly (bones, materials, IK). */
    public static Form getSheetForm(UIKeyframeSheet sheet) {
        if (sheet.form != null) {
            return sheet.form;
        }

        return sheet.property == null ? null : FormUtils.getForm(sheet.property);
    }

    public static void renderRuler(
            UIContext context,
            UIKeyframes keyframes,
            UIClipsPanel clipsPanel,
            Clips camera,
            int clipOffset
    ) {
        Area area = keyframes.graphArea;
        int rulerBottom = TimelineRulerRenderer.getRulerBottom(area);

        if (rulerBottom <= area.y)
        {
            return;
        }

        context.batcher.clip(area.x, area.y, area.w, rulerBottom - area.y, context);

        renderRulerAudio(context, keyframes, camera, clipOffset, area, rulerBottom);
        renderRulerClipGradient(context, keyframes, clipsPanel, clipOffset, area, rulerBottom);

        context.batcher.unclip(context);
    }

    private static boolean renderRulerAudio(
            UIContext context,
            UIKeyframes keyframes,
            Clips camera,
            int clipOffset,
            Area area,
            int rulerBottom
    ) {
        if (!BBSSettings.audioWaveformVisibleInKeyframes.get()) {
            return false;
        }

        Scale scale = keyframes.getXAxis();
        boolean renderedOnce = false;
        int y = area.y + 1;
        int h = Math.max(1, rulerBottom - y - 1);

        for (Clip clip : camera.get()) {
            if (!(clip instanceof AudioClip audioClip)) {
                continue;
            }

            Link link = audioClip.audio.get();

            if (link == null) {
                continue;
            }

            SoundBuffer buffer = BBSModClient.getSounds().get(link, true);

            if (buffer == null || buffer.getWaveform() == null) {
                continue;
            }

            Waveform wave = buffer.getWaveform();
            int audioOffset = audioClip.offset.get();
            float offset = audioClip.tick.get() - clipOffset;
            int duration = Math.min((int) (wave.getDuration() * 20), clip.duration.get());
            int x1 = (int) scale.to(offset);
            int x2 = (int) scale.to(offset + duration);

            if (x2 <= area.x || x1 >= area.ex()) {
                continue;
            }

            wave.render(
                    context.batcher,
                    Colors.WHITE,
                    x1,
                    y,
                    x2 - x1,
                    h,
                    TimeUtils.toSeconds(audioOffset),
                    TimeUtils.toSeconds(audioOffset + duration)
            );

            renderedOnce = true;
        }

        return renderedOnce;
    }

    private static void renderRulerClipGradient(
            UIContext context,
            UIKeyframes keyframes,
            UIClipsPanel clipsPanel,
            int clipOffset,
            Area area,
            int rulerBottom
    ) {
        Clip clip = clipsPanel.getClip();

        if (clip == null || clip instanceof AudioClip || !BBSSettings.editorClipPreview.get()) {
            return;
        }

        Scale scale = keyframes.getXAxis();
        int x1 = (int) scale.to(clip.tick.get() - clipOffset);
        int x2 = (int) scale.to(clip.tick.get() + clip.duration.get() - clipOffset);

        if (x2 <= area.x || x1 >= area.ex()) {
            return;
        }

        int color = clipsPanel.clips.getClipFactoryData(clip).color;
        int left = Math.max(area.x, x1);
        int right = Math.min(area.ex(), x2);
        int top = area.y + 1;
        int bottom = Math.max(top + 1, rulerBottom - 1);

        context.batcher.gradientVBox(left, top, right, bottom, Colors.setA(color, 0.03F), Colors.setA(color, 0.78F));
        context.batcher.box(left, Math.max(top, bottom - 2), right, bottom, Colors.setA(color, 0.92F));
    }

    public UIReplaysEditor(UIFilmPanel filmPanel) {
        this.filmPanel = filmPanel;
        this.replayProperties = new UIReplayPropertiesPanel(filmPanel);
        this.replaysList = new UIReplaysListPanel(filmPanel, (l) ->
        {
            Replay selected = l.isEmpty() ? null : l.get(0);

            if (selected != this.replay)
            {
                this.setReplay(selected, false, OrbitReaction.SWITCH);
            }
        }, this.replayProperties.getFormConsumer());
        this.replayProperties.attachReplayList(this.replaysList.replays);

        this.iconBar = new UIElement();
        this.iconBar.relative(this).x(0).y(0).w(CATEGORY_BAR_WIDTH).h(1F).column(0).stretch();

        this.iconBar.add(
                new UIRenderable(context -> {
                    Area area = this.iconBar.area;

                    context.batcher.box(area.x, area.y, area.ex(), area.ey(), BBSSettings.chromeSurface());

                    /* Highlight the active category on the left edge. */
                    UIIcon activeIcon = this.showAllTracks() ? this.allToggle : this.tabButtons.get(this.category);

                    if (activeIcon != null && activeIcon.getParent() != null) {
                        UIDashboardPanels.renderHighlight(context.batcher, activeIcon.area, Direction.LEFT);
                    }
                })
        );

        for (ReplayCategory category : ReplayCategory.values()) {
            UIIcon button = new UIIcon(category.icon, b -> this.setCategory(category));

            button.tooltip(category.tooltip, Direction.RIGHT);
            this.iconBar.add(button);
            this.tabButtons.put(category, button);
        }

        /* «All tracks» toggle, pinned to the bottom of the category bar. */
        this.allToggle = new UIIcon(Icons.LIST, b -> this.setAllTracks());
        this.allToggle.tooltip(UIKeys.FILM_REPLAY_ALL_TRACKS, Direction.RIGHT);
        this.layoutBottomToggles();

        this.setCategory(ReplayCategory.PLAYER);

        this.keys()
                .register(Keys.REPLAYS_TAB_1, () -> this.setCategoryByPosition(0))
                .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys()
                .register(Keys.REPLAYS_TAB_2, () -> this.setCategoryByPosition(1))
                .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys()
                .register(Keys.REPLAYS_TAB_3, () -> this.setCategoryByPosition(2))
                .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys()
                .register(Keys.REPLAYS_TAB_4, () -> this.setCategoryByPosition(3))
                .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys()
                .register(Keys.REPLAYS_TAB_5, () -> this.setCategoryByPosition(4))
                .category(UIKeys.FILM_REPLAY_TITLE);

        this.add(this.iconBar, this.allToggle);
        this.markContainer();
    }

    private void setCategory(ReplayCategory c) {
        this.category = c;
        this.allMode = false;
        this.updateChannelsList();
    }

    /** Show every category's tracks at once, bypassing the category filter. */
    private void setAllTracks() {
        this.allMode = true;
        this.updateChannelsList();
    }

    /** Pin the «all tracks» toggle to the bottom of the category bar. */
    private void layoutBottomToggles() {
        this.allToggle.relative(this).x(0).y(1F, -20).wh(CATEGORY_BAR_WIDTH, 20);
    }

    /**
     * Select the category sitting at the given visual position in the tab bar. The IK and physics tabs are only
     * present when the record has IK / physics, so a fixed key-to-category mapping would point past the gap; the
     * number keys instead follow the tabs as the user sees them.
     */
    private void setCategoryByPosition(int index)
    {
        List<ReplayCategory> present = new ArrayList<>();

        for (ReplayCategory category : ReplayCategory.values())
        {
            UIIcon button = this.tabButtons.get(category);

            if (button != null && button.getParent() != null)
            {
                present.add(category);
            }
        }

        present.sort(Comparator.comparingInt((c) -> this.tabButtons.get(c).area.y));

        if (index >= 0 && index < present.size())
        {
            this.setCategory(present.get(index));
        }
    }

    public ReplayCategory getCategory() {
        return this.category;
    }

    public void pickPlayerCategory()
    {
        if (this.category != ReplayCategory.PLAYER)
        {
            this.setCategory(ReplayCategory.PLAYER);
        }
    }

    public void setFilm(Film film) {
        this.savePoseTabState(this.replay);
        this.expandedPoseTabsByReplay.clear();
        this.film = film;

        if (film != null) {
            List<Replay> replays = film.replays.getList();
            int index = film.getId().equals(lastFilm) ? lastReplay : 0;

            if (!CollectionUtils.inRange(replays, index)) {
                index = 0;
            }

            this.replaysList.replays.refreshReplayList();
            this.setReplay(replays.isEmpty() ? null : replays.get(index), true, OrbitReaction.SWITCH);
        }
    }

    public Replay getReplay() {
        return this.replay;
    }

    public void setReplay(Replay replay) {
        this.setReplay(replay, true, OrbitReaction.SWITCH);
    }

    public void setReplay(Replay replay, boolean select, OrbitReaction orbit) {
        /* Guard against re-entry: scrollToReplay() below picks the replay in the list,
         * which fires the list's selection callback and calls setReplay() again. The
         * outermost call owns the orbit reaction, so the nested call is redundant and
         * must not override it (otherwise undo would teleport via the SWITCH callback). */
        if (this.settingReplay) {
            return;
        }

        this.settingReplay = true;

        try {
            this.savePoseTabState(this.replay);
            this.replay = replay;

            if (orbit == OrbitReaction.RESET) {
                this.filmPanel.getController().resetOrbit();
            }
            else if (orbit == OrbitReaction.SWITCH && replay != null && BBSSettings.editorOrbitTeleportOnSwitch.get())
            {
                this.filmPanel.getController().orbit.teleportPivotToReplay();
            }

            this.replayProperties.setReplay(replay);
            this.filmPanel.actionEditor.setClips(replay == null ? null : replay.actions);
            this.updateChannelsList();

            if (select && replay != null) {
                this.replaysList.replays.scrollToReplay(replay, false);
            }
        }
        finally {
            this.settingReplay = false;
        }
    }

    public void moveReplay(double x, double y, double z) {
        if (this.replay != null) {
            int cursor = this.filmPanel.getCursor();

            this.replay.keyframes.x.insert(cursor, x);
            this.replay.keyframes.y.insert(cursor, y);
            this.replay.keyframes.z.insert(cursor, z);
        }
    }

    public void updateChannelsList() {
        UIKeyframeEditor previousEditor = this.keyframeEditor;
        UIKeyframes lastEditor = previousEditor != null ? previousEditor.view : null;
        boolean resetView = lastEditor == null || this.keyframeEditorResetPending;
        long editorGeneration = this.keyframeEditorGeneration == Long.MAX_VALUE
                ? 1L
                : this.keyframeEditorGeneration + 1L;

        this.keyframeEditorGeneration = editorGeneration;
        this.keyframeEditor = null;
        this.keyframeEditorResetPending = false;

        if (this.replay == null) {
            this.replaceKeyframeEditor(previousEditor, null, editorGeneration, false);

            return;
        }

        this.updateIKTab();
        this.updatePhysicsTab();

        List<UIKeyframeSheet> sheets = new ArrayList<>();
        Map<UIKeyframeSheet, List<UIKeyframeSheet>> poseTabs = new HashMap<>();
        Map<UIKeyframeSheet, Integer> poseTabDepths = new HashMap<>();

        this.collectCuratedSheets(sheets);
        this.collectFormPropertySheets(sheets, poseTabs, poseTabDepths);
        this.collectIKSheets(sheets);
        this.collectPhysicsSheets(sheets);

        this.keys.clear();
        Map<String, Integer> keyToColor = new HashMap<>();
        Map<String, String> keyToLabel = new HashMap<>();

        for (UIKeyframeSheet sheet : sheets) {
            String filterKey = getSheetFilterKey(sheet);

            this.keys.add(filterKey);
            keyToColor.put(filterKey, sheet.color);
            keyToLabel.put(filterKey, sheet.title.get());
        }

        Set<String> disabled = BBSSettings.disabledSheets.get();

        sheets.removeIf(v -> {
            String filterKey = getSheetFilterKey(v);
            for (String s : disabled) {
                if (filterKey.equals(s) || v.id.equals(s) || v.id.endsWith("/" + s)) {
                    return true;
                }
            }

            Form owner = getSheetForm(v);

            if (owner != null) {
                Set<String> ownerDisabled = owner.disabledTracks.get();

                return ownerDisabled.contains(Form.DISABLED_ALL) || ownerDisabled.contains(filterKey);
            }

            return false;
        });

        Set<UIKeyframeSheet> kept = new LinkedHashSet<>(sheets);

        poseTabs.entrySet().removeIf((entry) ->
        {
            entry.getValue().removeIf((child) -> !kept.contains(child));

            return !kept.contains(entry.getKey()) || entry.getValue().isEmpty();
        });
        poseTabDepths.keySet().retainAll(kept);

        Form lastForm = null;

        for (UIKeyframeSheet sheet : sheets) {
            Form form = getSheetForm(sheet);

            if (!Objects.equals(lastForm, form)) {
                sheet.separator = true;
            }

            lastForm = form;
        }

        if (!sheets.isEmpty()) {
            this.keyframeEditor = new UIKeyframeEditor(consumer
                    -> new UIFilmKeyframes(this.filmPanel.cameraEditor, consumer).absolute()
            )
                    .target(this.filmPanel.editArea)
                    .editPanelTopOffset(this.filmPanel::getEditPanelTopOffsetPx);
            this.keyframeEditorResetPending = resetView;
            UIKeyframeEditor editor = this.keyframeEditor;
            UIKeyframes view = editor.view;
            Replay replayForEditor = this.replay;

            editor.relative(this).x(CATEGORY_BAR_WIDTH).y(0).w(1F, -CATEGORY_BAR_WIDTH).h(1F);
            editor.setUndoId("replay_keyframe_editor");
            editor.setTimelineVisible(this.timelineVisible);
            editor.setPropertiesVisible(this.propertiesVisible);

            /* Reset */
            if (lastEditor != null) {
                view.copyViewport(lastEditor);
            }

            view.rulerRenderer(context ->
                    renderRuler(context, view, this.filmPanel.cameraEditor, this.film.camera, 0)
            );
            view.duration(() -> this.film.camera.calculateDuration());
            view.context(menu -> {
                /* The old view can render for one frame after the replay is removed.  Do not
                 * let a delayed context-menu callback read the replacement editor state. */
                if (this.keyframeEditor != editor || this.replay != replayForEditor || replayForEditor == null) {
                    return;
                }

                int mouseY = this.getContext().mouseY;
                UIKeyframeSheet sheet = view.getGraph().getSheet(mouseY);

                if (replayForEditor.form.get() instanceof ModelForm modelForm) {
                    if (sheet != null
                            && sheet.channel.getFactory() == KeyframeFactories.POSE
                            && sheet.id.equals("pose")) {
                        menu.action(Icons.POSE, UIKeys.FILM_REPLAY_CONTEXT_ANIMATION_TO_KEYFRAMES, () -> {
                            ModelInstance model = ModelFormRenderer.getModel(modelForm);

                            if (model != null) {
                                UIOverlay.addOverlay(
                                        this.getContext(),
                                                new UIAnimationToPoseOverlayPanel(
                                                (animationKey, onlyKeyframes, length, step) -> {
                                                    if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                                                        return;
                                                    }

                                                    int current = this.filmPanel.getCursor();
                                                    IEntity entity = this.filmPanel.getController().getCurrentEntity();

                                                    UIReplaysEditorUtils.animationToPoseKeyframes(
                                                            editor,
                                                            sheet,
                                                            modelForm,
                                                            entity,
                                                            current,
                                                            animationKey,
                                                            onlyKeyframes,
                                                            length,
                                                            step
                                                    );
                                                },
                                                modelForm,
                                                sheet
                                        ),
                                        200,
                                        197
                                );
                            }
                        });
                    }

                    List<String> controllers = ModelIKRuntime.getControllers(ModelFormRenderer.getModel(modelForm));

                    if (!controllers.isEmpty()) {
                        menu.action(Icons.CLOSE, UIKeys.FILM_REPLAY_CONTEXT_CLEAR_IK, () -> {
                            if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                                return;
                            }

                            UIReplaysEditorUtils.clearIKTracks(replayForEditor, modelForm);
                            this.updateChannelsList();
                        });
                    }
                }

                boolean isPoseTrack
                        = sheet != null
                        && sheet.channel.getFactory() == KeyframeFactories.POSE
                        && (sheet.id.equals("pose")
                        || sheet.id.endsWith(FormUtils.PATH_SEPARATOR + "pose"))
                        && !sheet.id.contains("pose_overlay");

                Form sheetForm = sheet == null ? null : getSheetForm(sheet);
                boolean limbTracksOn = sheetForm instanceof PoseForm poseForm && poseForm.getBoneTracks().get();

                if (isPoseTrack && sheet.selection.hasAny() && limbTracksOn) {
                    menu.action(Icons.LIMB, UIKeys.FILM_REPLAY_CONTEXT_POSES_TO_LIMBS, () -> {
                        if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                            return;
                        }

                        UIReplaysEditorUtils.posesToLimbTracks(replayForEditor, sheet);

                        sheet.selection.removeSelected();
                        this.updateChannelsList();
                    });
                }

                if (view.getGraph() instanceof UIKeyframeDopeSheet) {
                    menu.action(Icons.FILTER, UIKeys.FILM_REPLAY_FILTER_SHEETS, () -> {
                        if (this.keyframeEditor != editor || this.replay != replayForEditor || replayForEditor == null) {
                            return;
                        }

                        Set<String> disabledSet = BBSSettings.disabledSheets.get();
                        UIKeyframeSheetFilterOverlayPanel panel = new UIKeyframeSheetFilterOverlayPanel(
                                disabledSet,
                                this.keys,
                                keyToColor,
                                keyToLabel
                        );

                        UIOverlay.addOverlay(this.getContext(), panel, 240, 0.9F);

                        panel.onClose(e -> {
                            if (this.keyframeEditor != editor || this.replay != replayForEditor || replayForEditor == null) {
                                return;
                            }

                            BBSSettings.disabledSheets.set(disabledSet);
                            this.updateChannelsList();
                        });
                    });
                }
            });

            for (UIKeyframeSheet sheet : sheets) {
                view.addSheet(sheet);
            }

            Set<String> expandedPoseIds = this.expandedPoseTabsByReplay.getOrDefault(
                this.replay == null ? "" : this.replay.getId(),
                Collections.emptySet()
            );
            view.getDopeSheet().configurePoseTabs(poseTabs, poseTabDepths, expandedPoseIds);

        }

        UIKeyframeEditor editor = this.keyframeEditor;

        this.replaceKeyframeEditor(previousEditor, editor, editorGeneration, resetView);
    }

    private void replaceKeyframeEditor(
            UIKeyframeEditor previous,
            UIKeyframeEditor replacement,
            long generation,
            boolean resetView
    ) {
        Runnable mutation = () -> {
            if (previous != null && previous.getParent() == this) {
                this.remove(previous);
            }

            if (generation != this.keyframeEditorGeneration || this.keyframeEditor != replacement) {
                return;
            }

            for (UIKeyframeEditor mounted : new ArrayList<>(this.getChildren(UIKeyframeEditor.class))) {
                if (mounted != replacement && mounted.getParent() == this) {
                    this.remove(mounted);
                }
            }

            if (replacement != null && replacement.getParent() != this) {
                this.add(replacement);
            }

            if (replacement != null) {
                /* Category bar and its bottom toggle stay on top of the timeline. */
                if (this.iconBar.getParent() != null) {
                    this.iconBar.removeFromParent();
                }
                if (this.allToggle.getParent() != null) {
                    this.allToggle.removeFromParent();
                }
                this.add(this.iconBar, this.allToggle);
            }

            this.resize();

            if (replacement != null && resetView) {
                replacement.view.resetView();

                if (generation == this.keyframeEditorGeneration && this.keyframeEditor == replacement) {
                    this.keyframeEditorResetPending = false;
                }
            }
        };
        UIContext context = this.getContext();

        if (context == null) {
            mutation.run();
        } else if (previous == null) {
            context.menu.runAfterHierarchyMutation(mutation);
        } else {
            context.menu.runAfterHierarchyMutation(mutation, previous);
        }
    }

    /** All-tracks view: transient toggle, or forced on when the replay tabs setting is disabled. */
    private boolean showAllTracks() {
        return this.allMode || !BBSSettings.editorReplayTabs.get();
    }

    private void collectCuratedSheets(List<UIKeyframeSheet> sheets) {
        if (!this.showAllTracks() && this.category != ReplayCategory.PLAYER) {
            return;
        }

        for (String key : ReplayKeyframes.CURATED_CHANNELS) {
            BaseValue value = this.replay.keyframes.get(key);
            KeyframeChannel channel = (KeyframeChannel) value;

            sheets.add(
                    new UIKeyframeSheet(getColor(key), false, channel, null).icon(ICONS.get(key))
            );
        }
    }

    private void collectFormPropertySheets(
            List<UIKeyframeSheet> sheets,
            Map<UIKeyframeSheet, List<UIKeyframeSheet>> poseTabs,
            Map<UIKeyframeSheet, Integer> poseTabDepths
    ) {
        Form lastForm = null;
        List<UIKeyframeSheet> formSheets = new ArrayList<>();

        for (String key : FormUtils.collectPropertyPaths(this.replay.form.get())) {
            KeyframeChannel property = this.replay.properties.getOrCreate(this.replay.form.get(), key);
            String name = StringUtils.fileName(key);
            boolean isPose
                    = name.startsWith("transform")
                    || name.startsWith("pose")
                    || name.startsWith("pose_overlay")
                    || name.startsWith("shape_keys");

            if (property != null
                    && (this.showAllTracks()
                    || (this.category == ReplayCategory.MODEL && !isPose)
                    || (this.category == ReplayCategory.POSE && isPose))) {
                BaseValueBasic formProperty = FormUtils.getProperty(this.replay.form.get(), key);
                Form form
                        = formProperty.getParent() instanceof Form ? (Form) formProperty.getParent() : null;

                if (form != lastForm) {
                    if (lastForm != null) {
                        this.flushForm(sheets, formSheets, lastForm, poseTabs, poseTabDepths);
                    }

                    lastForm = form;
                }

                UIKeyframeSheet sheet = new UIKeyframeSheet(
                        getColor(key),
                        false,
                        property,
                        formProperty
                );

                formSheets.add(sheet.icon(getIcon(key)));
            }
        }

        if (lastForm != null) {
            this.flushForm(sheets, formSheets, lastForm, poseTabs, poseTabDepths);
        }
    }

    /** IK tracks live in their own category; they are not form properties, so collect them by walking the form tree. */
    private void collectIKSheets(List<UIKeyframeSheet> sheets) {
        if (!this.showAllTracks() && this.category != ReplayCategory.IK) {
            return;
        }

        this.collectIKSheets(sheets, this.replay.form.get());
    }

    private void collectIKSheets(List<UIKeyframeSheet> sheets, Form form) {
        if (form == null) {
            return;
        }

        if (form instanceof ModelForm modelForm) {
            UIReplaysEditorUtils.addIKControlSheet(modelForm, this.replay.properties, sheets);
            UIReplaysEditorUtils.addIKTargetSheets(modelForm, this.replay.properties, sheets);
            UIReplaysEditorUtils.addPoleTargetSheets(modelForm, this.replay.properties, sheets);
        }

        for (BodyPart part : form.parts.getAllTyped()) {
            this.collectIKSheets(sheets, part.getForm());
        }
    }

    /** Physics tracks live in their own category; like IK they are not form properties, so collect them by walking the form tree. */
    private void collectPhysicsSheets(List<UIKeyframeSheet> sheets) {
        if (!this.showAllTracks() && this.category != ReplayCategory.PHYSICS) {
            return;
        }

        this.collectPhysicsSheets(sheets, this.replay.form.get());
    }

    private void collectPhysicsSheets(List<UIKeyframeSheet> sheets, Form form) {
        if (form == null) {
            return;
        }

        if (form instanceof ModelForm modelForm) {
            UIReplaysEditorUtils.addPhysicsControlSheet(modelForm, this.replay.properties, sheets);
            UIReplaysEditorUtils.addWindControlSheet(modelForm, this.replay.properties, sheets);
            UIReplaysEditorUtils.addPhysicsTargetSheets(modelForm, this.replay.properties, sheets);
        }

        for (BodyPart part : form.parts.getAllTyped()) {
            this.collectPhysicsSheets(sheets, part.getForm());
        }
    }

    /** Show the IK tab only when the record actually has IK; bounce an active IK category back to Model when it does not. */
    private void updateIKTab() {
        UIIcon button = this.tabButtons.get(ReplayCategory.IK);

        if (button == null) {
            return;
        }

        boolean hasIK = this.formHasIK(this.replay.form.get());
        boolean present = button.getParent() != null;

        if (hasIK && !present) {
            this.iconBar.add(button);
            this.iconBar.resize();
        }
        else if (!hasIK && present) {
            button.removeFromParent();
            this.iconBar.resize();
        }

        if (!hasIK && this.category == ReplayCategory.IK) {
            this.category = ReplayCategory.MODEL;
        }
    }

    private boolean formHasIK(Form form) {
        if (form == null) {
            return false;
        }

        if (form instanceof ModelForm modelForm) {
            ModelInstance model = ModelFormRenderer.getModel(modelForm);

            if (model != null) {
                model.form = modelForm;

                if (!ModelIKRuntime.getControllers(model).isEmpty()) {
                    return true;
                }
            }
        }

        for (BodyPart part : form.parts.getAllTyped()) {
            if (this.formHasIK(part.getForm())) {
                return true;
            }
        }

        return false;
    }

    /** Show the Physics tab only when the record actually has physics chains; bounce an active Physics category back to Model when it does not. */
    private void updatePhysicsTab() {
        UIIcon button = this.tabButtons.get(ReplayCategory.PHYSICS);

        if (button == null) {
            return;
        }

        boolean hasPhysics = this.formHasPhysics(this.replay.form.get());
        boolean present = button.getParent() != null;

        if (hasPhysics && !present) {
            this.iconBar.add(button);
            this.iconBar.resize();
        }
        else if (!hasPhysics && present) {
            button.removeFromParent();
            this.iconBar.resize();
        }

        if (!hasPhysics && this.category == ReplayCategory.PHYSICS) {
            this.category = ReplayCategory.MODEL;
        }
    }

    private boolean formHasPhysics(Form form) {
        if (form == null) {
            return false;
        }

        if (form instanceof ModelForm modelForm && modelForm.physics.get() instanceof MapType map) {
            ModelPhysicsConfig config = ModelPhysicsIO.fromData(map);

            if (config != null && config.bones() != null && !config.bones().isEmpty()) {
                return true;
            }
        }

        for (BodyPart part : form.parts.getAllTyped()) {
            if (this.formHasPhysics(part.getForm())) {
                return true;
            }
        }

        return false;
    }

    private void flushForm(
            List<UIKeyframeSheet> sheets,
            List<UIKeyframeSheet> formSheets,
            Form form,
            Map<UIKeyframeSheet, List<UIKeyframeSheet>> poseTabs,
            Map<UIKeyframeSheet, Integer> poseTabDepths
    ) {
        String path = FormUtils.getPath(form);
        String poseId = path.isEmpty() ? "pose" : path + FormUtils.PATH_SEPARATOR + "pose";
        UIKeyframeSheet poseSheet = null;

        for (UIKeyframeSheet sheet : formSheets)
        {
            if (poseId.equals(sheet.id) && sheet.channel.getFactory() == KeyframeFactories.POSE)
            {
                poseSheet = sheet;
                break;
            }
        }

        List<UIKeyframeSheet> orderedFormSheets = new ArrayList<>(formSheets);
        formSheets.clear();

        if ((this.showAllTracks() || this.category == ReplayCategory.MODEL)
                && form instanceof AbstractSoundForm soundForm) {
            UIReplaysEditorUtils.addSoundSheets(soundForm, this.replay.properties, orderedFormSheets);
        }

        if ((this.showAllTracks() || this.category == ReplayCategory.MODEL)
                && form instanceof ModelForm modelForm) {
            List<UIKeyframeSheet> materialSheets = new ArrayList<>();
            UIReplaysEditorUtils.addMaterialTextureSheets(modelForm, this.replay.properties, materialSheets);
            orderedFormSheets.addAll(materialSheets);
        }

        if ((this.showAllTracks() || this.category == ReplayCategory.POSE)
                && form instanceof PoseForm) {
            List<UIKeyframeSheet> boneSheets = new ArrayList<>();
            Map<String, Integer> depthBySheetId = new HashMap<>();
            UIReplaysEditorUtils.addBoneTrackSheets(form, this.replay.properties, boneSheets, depthBySheetId);

            for (UIKeyframeSheet boneSheet : boneSheets)
            {
                Integer depth = depthBySheetId.get(boneSheet.id);
                poseTabDepths.put(boneSheet, depth == null ? 0 : depth);
            }

            if (poseSheet != null && !boneSheets.isEmpty())
            {
                poseTabs.put(poseSheet, boneSheets);

                int poseIndex = orderedFormSheets.indexOf(poseSheet);

                if (poseIndex >= 0)
                {
                    orderedFormSheets.addAll(poseIndex + 1, boneSheets);
                }
                else
                {
                    orderedFormSheets.addAll(boneSheets);
                }
            }
            else
            {
                orderedFormSheets.addAll(boneSheets);
            }
        }

        sheets.addAll(orderedFormSheets);
    }

    private void savePoseTabState(Replay replay)
    {
        if (replay == null || this.keyframeEditor == null)
        {
            return;
        }

        this.expandedPoseTabsByReplay.put(replay.getId(), this.keyframeEditor.view.getDopeSheet().getExpandedPoseTabIds());
    }

    /**
     * Re-applies keyframe parameters panel position (e.g. after layout lock
     * toggle).
     */
    public void refreshEditPanelOffset() {
        if (this.keyframeEditor != null) {
            this.keyframeEditor.refreshEditPanelOffset();
        }

        if (this.replaysList != null)
        {
            this.replaysList.refreshEditPanelOffset();
        }

        if (this.replayProperties != null)
        {
            this.replayProperties.refreshEditPanelOffset();
        }
    }

    public void setTimelineVisible(boolean visible)
    {
        this.timelineVisible = visible;

        if (this.keyframeEditor != null)
        {
            this.keyframeEditor.setTimelineVisible(visible);
        }
    }

    public void setPropertiesVisible(boolean visible)
    {
        this.propertiesVisible = visible;

        if (this.keyframeEditor != null)
        {
            this.keyframeEditor.setPropertiesVisible(visible);
        }
    }

    public void pickForm(Form form, String bone) {
        this.pickFormBone(form, bone, false);
    }

    /** Route deferred gizmo-sphere picks through the same modifier gestures as direct bone picks. */
    public void pickFormWithOffers(UIContext context, Form form, String bone) {
        UIReplaysEditorUtils.pickFormWithOffers(context, new Pair<>(form, bone), this::pickFormBone);
    }

    /**
     * Picking a model bone in the viewport is a pose edit, but the pose/bone tracks
     * only exist in the {@link ReplayCategory#POSE} category. So when another category
     * is open, jump to Pose first before delegating to the shared pick logic — otherwise
     * the click finds no pose sheet in the current graph and silently does nothing,
     * forcing a manual tab switch. With all tracks shown the pose sheets are already
     * in the graph, so no switch is forced.
     */
    private void pickFormBone(Form form, String bone, boolean insert) {
        if (form instanceof PoseForm && bone != null && !bone.isEmpty() && !this.showAllTracks() && this.category != ReplayCategory.POSE) {
            this.setCategory(ReplayCategory.POSE);
        }

        UIReplaysEditorUtils.pickForm(this.keyframeEditor, this.filmPanel, form, bone, insert);
    }

    /** Preserve the legacy direct-addon JVM descriptor. */
    public void releaseViewport(UIContext context, boolean dragged)
    {
        this.releaseViewport(context, dragged, this.pendingPickGeneration);
    }

    public void releaseViewport(UIContext context, boolean dragged, long generation)
    {
        if (generation == 0L || this.pendingPickGeneration != generation)
        {
            return;
        }

        Pair<Form, String> pending = this.pendingPick;

        this.pendingPick = null;
        this.pendingPickGeneration = 0L;

        if (pending == null || dragged || context.mouseButton != 0)
        {
            return;
        }

        if (!this.isVisible())
        {
            this.filmPanel.showPanel(this);
        }

        UIReplaysEditorUtils.pickFormWithOffers(context, pending, this::pickFormBone);
    }

    public void cancelViewportPick(long generation)
    {
        if (generation != 0L && this.pendingPickGeneration == generation)
        {
            this.pendingPick = null;
            this.pendingPickGeneration = 0L;
        }
    }

    public boolean clickViewport(UIContext context, Area area) {
        boolean inside = area.isInside(context);
        StencilFormFramebuffer stencil = this.filmPanel.getController().getStencil();

        if (this.filmPanel.isFlying() && inside) {
            if (context.mouseButton == 0 && this.filmPanel.getController().orbit.enabled) {
                long generation = this.filmPanel.getController().orbit.startGesture(context);

                return generation != 0L;
            }
            if (context.mouseButton == 2) {
                if (this.filmPanel.getController().orbit.enabled) {
                    long generation = this.filmPanel.getController().orbit.startGesture(context);

                    return generation != 0L;
                } else {
                    long generation = this.filmPanel.dashboard.orbitUI.startGesture(context);

                    return generation != 0L;
                }
            }
        }

        if (this.filmPanel.isFlying()) {
            return false;
        }

        if (inside && context.mouseButton == 2 && this.filmPanel.getController().orbit.enabled) {
            long generation = this.filmPanel.getController().orbit.startGesture(context);

            return generation != 0L;
        }

        if (stencil != null && stencil.hasPicked()) {
            if (inside && context.mouseButton == 0
                    && this.filmPanel.getController().startViewportSoundGuide(context)) {
                return true;
            }

            if (inside && context.mouseButton == 0
                    && this.filmPanel.getController().startViewportGizmo(context)) {
                return true;
            }

            Pair<Form, String> pair = stencil.getPicked();

            if (pair != null && (context.mouseButton < 2 || (context.mouseButton == 2 && Window.isCtrlPressed()))) {
                if (!this.isVisible()) {
                    this.filmPanel.showPanel(this);
                }

                if (UIReplaysEditorUtils.pickFormWithOffers(context, pair, this::pickFormBone)) {
                    return true;
                }
            }
        } else if (context.mouseButton == 1 && this.isVisible()) {
            Level world = Minecraft.getInstance().level;
            Camera camera = this.filmPanel.getCamera();

            BlockHitResult blockHitResult = RayTracing.rayTrace(
                    world,
                    RayTracing.fromVector3d(camera.position),
                    RayTracing.fromVector3f(
                            CameraUtils.getMouseDirection(
                                    camera.projection,
                                    camera.view,
                                    context.mouseX,
                                    context.mouseY,
                                    area.x,
                                    area.y,
                                    area.w,
                                    area.h
                            )
                    ),
                    256F
            );

            if (blockHitResult.getType() != HitResult.Type.MISS) {
                Vector3d vec = new Vector3d(
                        blockHitResult.getLocation().x,
                        blockHitResult.getLocation().y,
                        blockHitResult.getLocation().z
                );

                if (Window.isShiftPressed()) {
                    vec = new Vector3d(
                            Math.floor(vec.x) + 0.5D,
                            Math.round(vec.y),
                            Math.floor(vec.z) + 0.5D
                    );
                }

                final Vector3d finalVec = vec;

                context.replaceContextMenu(menu -> {
                    float pitch = 0F;
                    float yaw = MathUtils.toDeg(camera.rotation.y);

                    menu.action(Icons.ADD, UIKeys.FILM_REPLAY_CONTEXT_ADD, ()
                            -> this.replaysList.replays.addReplay(finalVec, pitch, yaw)
                    );
                    menu.action(Icons.POINTER, UIKeys.FILM_REPLAY_CONTEXT_MOVE_HERE, ()
                            -> this.moveReplay(finalVec.x, finalVec.y, finalVec.z)
                    );
                });

                return true;
            }
        }

        if (inside && context.mouseButton == 0 && this.filmPanel.getController().orbit.enabled) {
            long generation = this.filmPanel.getController().orbit.startGesture(context);

            if (generation != 0L) {
                this.pendingPick = stencil != null && stencil.hasPicked() ? stencil.getPicked() : null;
                this.pendingPickGeneration = generation;
            }

            return generation != 0L;
        }

        return false;
    }

    public void close() {
        if (this.film != null) {
            lastFilm = this.film.getId();
            Replay r = this.getReplay();

            lastReplay = r == null ? 0 : CollectionUtils.getIndex(this.film.replays.getList(), r);
        }
    }

    public void teleport() {
        if (this.filmPanel.getData() == null) {
            return;
        }

        Replay replay = this.getReplay();

        if (replay != null) {
            int tick = this.filmPanel.getCursor();
            double x = replay.keyframes.x.interpolate(tick);
            double y = replay.keyframes.y.interpolate(tick);
            double z = replay.keyframes.z.interpolate(tick);
            float yaw = replay.keyframes.yaw.interpolate(tick).floatValue();
            float headYaw = replay.keyframes.headYaw.interpolate(tick).floatValue();
            float bodyYaw = replay.keyframes.bodyYaw.interpolate(tick).floatValue();
            float pitch = replay.keyframes.pitch.interpolate(tick).floatValue();
            LocalPlayer player = Minecraft.getInstance().player;

            PlayerUtils.teleport(x, y, z, headYaw, pitch);
            player.setYRot(yaw);
            player.setYHeadRot(headYaw);
            player.setYBodyRot(bodyYaw);
            player.setXRot(pitch);
        }
    }

    @Override
    public void render(UIContext context) {
        /* Hide category bar while the "edit track" overlay is open */
        boolean barVisible = this.timelineVisible
                && (this.keyframeEditor == null || !this.keyframeEditor.view.isEditing());

        this.iconBar.setVisible(barVisible);
        this.allToggle.setVisible(barVisible);

        UIReplaysEditorUtils.configureFilmHotkeyDrag(this.filmPanel, context);

        super.render(context);
    }

    @Override
    public void resize() {
        super.resize();

        this.layoutBottomToggles();
    }

    @Override
    public void applyUndoData(MapType data) {
        super.applyUndoData(data);

        List<Integer> selection = DataStorageUtils.intListFromData(data.getList("selection"));
        List<Integer> currentIndices = this.replaysList.replays.getCurrentIndices();

        this.setReplay(
                CollectionUtils.getSafe(this.film.replays.getList(), data.getInt("replay")),
                true,
                OrbitReaction.KEEP
        );

        currentIndices.clear();
        currentIndices.addAll(selection);
        this.replaysList.replays.update();
    }

    @Override
    public void collectUndoData(MapType data) {
        super.collectUndoData(data);

        int index = CollectionUtils.getIndex(this.film.replays.getList(), this.getReplay());

        data.putInt("replay", index);
        data.put(
                "selection",
                DataStorageUtils.intListToData(this.replaysList.replays.getCurrentIndices())
        );
    }

    /**
     * How the orbit camera should react when the selected replay is set.
     */
    public enum OrbitReaction {
        /** Reset the orbit camera to its default position. */
        RESET,
        /** Treat it as a user switching replays — teleport the pivot onto the replay if the setting allows. */
        SWITCH,
        /** Leave the orbit camera untouched (used when restoring selection during undo/redo). */
        KEEP
    }
}
