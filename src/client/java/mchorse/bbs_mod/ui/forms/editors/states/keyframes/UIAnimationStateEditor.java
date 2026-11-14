package mchorse.bbs_mod.ui.forms.editors.states.keyframes;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.ui.forms.editors.UIForms;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCategoryBar;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.utils.Direction;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.IPosedForm;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.forms.states.AnimationState;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.film.replays.overlays.UIAnimationToPoseOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.overlays.UIKeyframeSheetFilterOverlayPanel;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.items.FoldState;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class UIAnimationStateEditor extends UIElement
{
    private static final int CATEGORY_BAR_WIDTH = 20;
    public UIKeyframeEditor keyframeEditor;

    public UIFormEditor editor;
    public UIElement editArea;
    public final UIForms bodyParts;

    private final UIElement sidebar = new UIElement();
    private final UIElement timelineArea = new UIElement()
    {
        @Override
        protected void afterResizeApplied()
        {
            UIAnimationStateEditor.this.layoutCategoryBar();
        }
    };
    private final UITimelineCategoryBar categoryBar = new UITimelineCategoryBar(0);
    private final UIElement partHeader = new UIElement()
    {
        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            return this.area.isInside(context);
        }
    };
    private final Map<TrackCategory, UIIcon> categoryButtons = new LinkedHashMap<>();
    private final List<TrackCategory> visibleCategories = new ArrayList<>();
    private TrackCategory category = TrackCategory.FORM;
    private boolean allMode = true;
    private final UISection bodyPartsSection = new UISection(L10n.lang("bbs.ui.film.replays.body_parts"));
    private Form root;
    private String selectedPart = "";

    private AnimationState state;
    private Set<String> keys = new LinkedHashSet<>();
    private int poseOverlayCount;
    private int transformOverlayCount;

    /** Track rows the user has unfolded right now; handed to the dope sheet, which folds them in place. */
    private final FoldState<String> expandedTabs = new FoldState<>();

    public UIAnimationStateEditor(UIFormEditor editor)
    {
        this.editor = editor;
        this.setUndoId("form_animation_state_editor");
        this.bodyParts = new UIForms(list ->
        {
            if (!list.isEmpty())
            {
                this.selectPart(list.get(0).getPath());
            }
        });
        this.sidebar.relative(this).x(BBSSettings.editorLayoutSettings.getStateEditorSizeH()).wTo(this.area, 1F).h(1F);
        this.timelineArea.relative(this).y(1F).anchorY(1F).wTo(this.sidebar.area)
            .h(BBSSettings.editorLayoutSettings.getStateEditorSizeV());

        this.categoryBar.relative(this.timelineArea);
        UIIcon all = new UIIcon(Icons.LIST, button -> this.setCategory(null));
        all.tooltip(UIKeys.FILM_REPLAY_ALL_TRACKS, Direction.RIGHT);
        all.highlight(() -> this.allMode, Direction.LEFT);
        this.categoryBar.add(all);

        for (TrackCategory category : TrackCategories.values())
        {
            if (category == TrackCategory.REPLAY) continue;
            UIIcon button = new UIIcon(category.icon, b -> this.setCategory(category));
            button.tooltip(category.tooltip, Direction.RIGHT);
            button.highlight(() -> !this.allMode && this.category == category, Direction.LEFT);
            this.categoryButtons.put(category, button);
        }

        TrackCategories.registerShortcuts(this.keys(), () -> this.visibleCategories, this::setCategory);

        this.partHeader.relative(this.timelineArea).x(CATEGORY_BAR_WIDTH).w(120).h(20);
        this.partHeader.add(new UIRenderable(context -> this.partHeader.area.render(context.batcher, BBSSettings.baseSurface())));
        UILabel partName = new UILabel(this::getSelectedPartName).labelAnchor(0F, 0.5F);
        partName.relative(this.partHeader).x(5).w(1F, -10).h(1F);
        partName.tooltip(() -> L10n.lang("bbs.ui.film.replays.selected_body_part").format(this.getSelectedPartName()).get());
        this.partHeader.add(partName);
        this.timelineArea.add(this.categoryBar, this.partHeader);

        this.bodyPartsSection.relative(this.sidebar).x(3).y(1F, -3).w(1F, -6).anchorY(1F);
        this.bodyPartsSection.fields.add(this.bodyParts);
        this.bodyPartsSection.setVisible(false);

        this.editArea = new UIElement();
        this.editArea.relative(this.sidebar).w(1F).hTo(this.bodyPartsSection.area);
        /* The section's natural height determines how much space remains for properties. */
        this.sidebar.add(this.bodyPartsSection, this.editArea);

        UIDraggable draggable = new UIDraggable((context) ->
        {
            float fx = (context.mouseX - this.area.x) / (float) this.area.w;
            float fy = (this.area.ey() - context.mouseY) / (float) this.area.h;

            BBSSettings.editorLayoutSettings.setStateEditorSizeV(fy);
            BBSSettings.editorLayoutSettings.setStateEditorSizeH(fx);

            this.timelineArea.h(BBSSettings.editorLayoutSettings.getStateEditorSizeV());
            this.sidebar.x(BBSSettings.editorLayoutSettings.getStateEditorSizeH());
            this.getParent().resize();
        });
        draggable.cursors(GLFW.GLFW_CROSSHAIR_CURSOR, GLFW.GLFW_CROSSHAIR_CURSOR);

        draggable.reference(() -> new Vector2i(this.sidebar.area.x, this.timelineArea.area.y));
        draggable.rendering((context) ->
        {
            int size = 5;
            int x = this.sidebar.area.x + 3;
            int y = this.timelineArea.area.y + 3;

            context.batcher.box(x, y, x + 1, y + size, Colors.WHITE);
            context.batcher.box(x, y - 1, x + size, y, Colors.WHITE);

            x = this.sidebar.area.x - 3;
            y = this.timelineArea.area.y + 3;

            context.batcher.box(x - 1, y, x, y + size, Colors.WHITE);
            context.batcher.box(x - size, y - 1, x, y, Colors.WHITE);
        });

        draggable.hoverOnly().relative(this.timelineArea).x(1F).w(40).h(6).anchorX(0.5F);

        this.add(this.sidebar, this.timelineArea, draggable);
    }

    public AnimationState getState()
    {
        return this.state;
    }

    public UIPropTransform getEditableTransform()
    {
        return UIReplaysEditorUtils.getEditableTransform(this.keyframeEditor);
    }

    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        UIPropTransform transform = this.getEditableTransform();
        GizmoDrag drag = this.buildGizmoDrag(transform, context.getTransition());

        return transform != null && Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, transform, drag);
    }

    private void setCategory(TrackCategory category)
    {
        this.allMode = category == null;
        if (category != null) this.category = category;
        this.setState(this.state);
    }

    public String getSelectedPartName()
    {
        for (UIForms.FormEntry entry : this.bodyParts.getList())
            if (entry.getPath().equals(this.selectedPart)) return entry.toString();
        return "";
    }

    public String getSelectedPart() { return this.selectedPart; }

    public void setState(AnimationState state)
    {
        UIKeyframes lastEditor = null;

        if (this.keyframeEditor != null)
        {
            lastEditor = this.keyframeEditor.view;

            this.keyframeEditor.removeFromParent();
            this.keyframeEditor = null;
        }

        this.state = state;
        if (this.root != this.editor.form)
        {
            this.root = this.editor.form;
            this.selectedPart = "";
            this.expandedTabs.collapseAll();
        }
        if (this.root != null)
        {
            this.bodyParts.setForm(this.root);
            this.selectedPart = this.bodyParts.setCurrentPath(this.selectedPart);
        }
        this.bodyPartsSection.setVisible(state != null);
        this.poseOverlayCount = BBSSettings.recordingPoseOverlays.get();
        this.transformOverlayCount = BBSSettings.recordingTransformOverlays.get();

        if (this.state == null)
        {
            return;
        }

        List<UIKeyframeSheet> sheets = new ArrayList<>();
        List<TrackDescriptor> catalog = new ArrayList<>();
        for (TrackDescriptor track : TrackCatalog.forPart(this.editor.form, this.state.properties, this.selectedPart))
            if (!track.kind().isSolver()) catalog.add(track);
        this.visibleCategories.clear();
        for (TrackCategory candidate : TrackCategories.values())
        {
            UIIcon button = this.categoryButtons.get(candidate);
            if (button == null) continue;
            button.removeFromParent();
            if (candidate == TrackCategory.FORM || candidate == TrackCategory.POSE
                || catalog.stream().anyMatch(track -> TrackCategories.categoryOf(track.id(), true) == candidate))
            {
                this.visibleCategories.add(candidate);
                this.categoryBar.add(button);
            }
        }
        if (!this.visibleCategories.contains(this.category)) this.category = TrackCategory.FORM;
        UIReplaysEditorUtils.buildSheets(catalog, sheets);

        this.keys.clear();
        Map<String, Integer> keyToColor = new HashMap<>();
        Map<String, String> keyToLabel = new HashMap<>();

        for (UIKeyframeSheet sheet : sheets)
        {
            String filterKey = UIReplaysEditor.getSheetFilterKey(sheet);

            this.keys.add(filterKey);
            keyToColor.put(filterKey, sheet.color);
            keyToLabel.put(filterKey, sheet.title.get());
        }

        sheets.removeIf((v) -> v.id.equals("anchor"));
        sheets.removeIf(sheet -> !this.allMode && UIReplaysEditor.categoryOf(sheet) != this.category);

        /* The state isn't empty by itself - so if the filter empties it, the timeline has to stay (see below). */
        boolean hadTracks = !sheets.isEmpty();

        sheets.removeIf((v) ->
        {
            String filterKey = UIReplaysEditor.getSheetFilterKey(v);

            for (String s : BBSSettings.disabledSheets.get())
            {
                if (filterKey.equals(s) || v.id.equals(s) || v.id.endsWith("/" + s))
                {
                    return true;
                }
            }

            Form owner = UIReplaysEditor.getSheetForm(v);

            if (owner != null)
            {
                Set<String> ownerDisabled = owner.disabledTracks.get();

                return ownerDisabled.contains(Form.DISABLED_ALL) || ownerDisabled.contains(filterKey);
            }

            return false;
        });

        UIReplaysEditorUtils.pruneTree(sheets);

        /*
         * Filtering every track off used to drop the timeline itself, and the track filter lives in its
         * context menu - so "disable all" locked the user out of the only way back. Keep the (empty)
         * timeline whenever the state had tracks before the filter ran; the dope sheet says why it's blank.
         */
        if (!sheets.isEmpty() || hadTracks || !this.allMode)
        {
            this.keyframeEditor = new UIKeyframeEditor((consumer) -> new UIAnimationStateKeyframes(this.editor, consumer)).target(this.editArea);
            this.keyframeEditor.relative(this.timelineArea).x(CATEGORY_BAR_WIDTH).w(1F, -CATEGORY_BAR_WIDTH).h(1F);
            this.keyframeEditor.setUndoId("form_animation_state_keyframe_editor");
            this.keyframeEditor.view.getDopeSheet().setEmptyState(UIKeys.KEYFRAMES_EMPTY_FILTERED, UIKeys.KEYFRAMES_EMPTY_FILTERED_HINT);

            /* Reset */
            if (lastEditor != null)
            {
                this.keyframeEditor.view.copyViewport(lastEditor);
            }

            this.keyframeEditor.view.duration(() -> this.state.duration.get());
            this.keyframeEditor.view.context((menu) ->
            {
                UIKeyframeSheet sheet = this.keyframeEditor.view.getGraph().getSheet(this.getContext().mouseY);
                UIReplaysEditorUtils.addOverlayTrackAction(menu, sheet, parent ->
                {
                    this.expandedTabs.set(parent.toKey(), true);
                    this.setState(this.state);
                });
                menu.action(Icons.KEY, UIKeys.FILM_AUTO_KEYFRAME, BBSSettings.autoKeyframe.get(),
                    () -> BBSSettings.autoKeyframe.set(!BBSSettings.autoKeyframe.get()));

                IPosedForm posedForm = sheet == null ? null : sheet.getPosedForm();

                if (posedForm != null && sheet.selection.hasAny() && posedForm.hasBoneTracks())
                {
                    menu.action(Icons.LIMB, UIKeys.FILM_REPLAY_CONTEXT_POSES_TO_LIMBS, () ->
                    {
                        if (UIReplaysEditorUtils.posesToLimbTracks(this.state.properties, sheet))
                        {
                            this.expandedTabs.set(sheet.id, true);
                            this.setState(this.state);
                        }
                    });
                }

                ModelForm poseModelForm = sheet == null ? null : sheet.getPoseForm();

                if (poseModelForm != null)
                {
                    menu.action(Icons.POSE, UIKeys.FILM_REPLAY_CONTEXT_ANIMATION_TO_KEYFRAMES, () ->
                    {
                        ModelInstance model = ModelFormRenderer.getModel(poseModelForm);

                        if (model != null)
                        {
                            UIOverlay.addOverlay(this.getContext(), new UIAnimationToPoseOverlayPanel((animationKey, onlyKeyframes, length, step) ->
                            {
                                UIContext context = this.getContext();
                                float current = this.editor.getKeyframeCursor(context == null ? 0F : context.getTransition());
                                IEntity entity = this.editor.renderer.getTargetEntity();

                                UIReplaysEditorUtils.animationToPoseKeyframes(this.keyframeEditor, sheet, poseModelForm, entity, current, animationKey, onlyKeyframes, length, step);
                            }, poseModelForm, sheet), 200, 197);
                        }
                    });
                }

                if (this.keyframeEditor.view.getGraph() instanceof UIKeyframeDopeSheet)
                {
                    menu.action(Icons.FILTER, UIKeys.FILM_REPLAY_FILTER_SHEETS, () ->
                    {
                        UIKeyframeSheetFilterOverlayPanel panel = new UIKeyframeSheetFilterOverlayPanel(
                            BBSSettings.disabledSheets.get(),
                            this.keys,
                            keyToColor,
                            keyToLabel
                        );

                        UIOverlay.addOverlay(this.getContext(), panel, 240, 0.9F);

                        panel.onClose((e) ->
                        {
                            this.setState(this.state);
                            BBSSettings.disabledSheets.set(BBSSettings.disabledSheets.get());
                        });
                    });
                }
            });

            for (UIKeyframeSheet sheet : sheets)
            {
                this.keyframeEditor.view.addSheet(sheet);
            }

            this.keyframeEditor.view.getDopeSheet().setExpanded(this.expandedTabs);

            this.timelineArea.add(this.keyframeEditor);
            this.categoryBar.removeFromParent();
            this.partHeader.removeFromParent();
            this.timelineArea.add(this.categoryBar, this.partHeader);
        }

        this.resize();

        if (this.keyframeEditor != null && lastEditor == null)
        {
            this.keyframeEditor.view.resetView();
        }
    }

    public void selectPart(String path)
    {
        if (!this.selectedPart.equals(path))
        {
            this.selectedPart = path;
            this.setState(this.state);
        }
    }

    private void selectForm(Form form)
    {
        if (form != null) this.selectPart(FormUtils.getPath(form));
    }

    private void layoutCategoryBar()
    {
        int width = this.categoryBar.getWidthForHeight(this.timelineArea.area.h);
        this.categoryBar.w(width);
        this.partHeader.x(width);
        if (this.keyframeEditor != null) this.keyframeEditor.x(width).w(1F, -width);
    }

    @Override
    public void resize()
    {
        int maxHeight = Math.min(160, this.getFlex().getH() / 2);
        this.bodyParts.h(Math.max(1, Math.min(this.bodyParts.getList().size() * this.bodyParts.scroll.scrollItemSize, maxHeight)));
        this.editArea.hTo(this.bodyPartsSection.isVisible() ? this.bodyPartsSection.area : this.sidebar.area,
            this.bodyPartsSection.isVisible() ? 0F : 1F);
        super.resize();
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);
        data.putString("body_part", this.selectedPart);
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);
        this.root = this.editor.form;
        this.selectedPart = data.getString("body_part");
        this.setState(this.state);
    }

    public boolean clickViewport(UIContext context, StencilFormFramebuffer stencil)
    {
        if (stencil.hasPicked() && this.state != null)
        {
            Pair<Form, String> pair = stencil.getPicked();

            if (pair != null)
            {
                return UIReplaysEditorUtils.pickFormWithOffers(context, pair, (form, bone, insert) ->
                {
                    this.selectForm(form);
                    UIReplaysEditorUtils.pickForm(this.keyframeEditor, this.editor, form, bone, insert);
                });
            }
        }

        return false;
    }

    public void pickFormFromViewport(Form form, String bone)
    {
        if (Window.isAltPressed() && !Window.isCtrlPressed()) UIReplaysEditorUtils.offerAdjacent(this.getContext(), form, bone, (pickedBone) -> this.pickForm(form, pickedBone));
        else if (Window.isShiftPressed()) UIReplaysEditorUtils.offerHierarchy(this.getContext(), form, bone, (pickedBone) -> this.pickForm(form, pickedBone));
        else this.pickForm(form, bone);
    }

    public void pickForm(Form form, String bone)
    {
        this.selectForm(form);
        UIReplaysEditorUtils.pickForm(this.keyframeEditor, this.editor, form, bone, false);
    }

    private GizmoDrag buildGizmoDrag(UIPropTransform transform, float transition)
    {
        if (transform == null || transform.getTransform() == null)
        {
            return null;
        }

        GizmoDrag drag = GizmoDrag.fromRenderedGizmo(this.editor.renderer.camera, this.editor.renderer.area);

        if (drag != null)
        {
            drag.setGlobalAxes(this.editor.renderer.getSceneAxes());

            float tick = this.editor.getSamplingTick();
            Supplier<Matrix4f> rotationSampler = () ->
            {
                this.editor.applyStateForSampling(tick);

                Matrix4f origin = this.getOriginMatrix(transition);

                return origin == null ? new Matrix4f() : MatrixStackUtils.stripScale(this.editor.renderer.toSceneMatrix(origin));
            };

            this.editor.applyStateForSampling(tick);

            boolean mobPoseBone = this.isMobPoseBoneTrack();
            Matrix3f translateJacobian = mobPoseBone ? this.getMobTranslateJacobian(transition) : null;

            if (!mobPoseBone)
            {
                translateJacobian = GizmoDrag.computeTranslateJacobian(
                    transform.getTransform(),
                    () ->
                    {
                        this.editor.applyStateForSampling(tick);

                        Matrix4f origin = this.getOrigin(transition);

                        return origin == null ? new Vector3f() : this.editor.renderer.toSceneMatrix(origin).getTranslation(new Vector3f());
                    }
                );
            }

            drag.setJacobian(GizmoDrag.resolveTranslateJacobian(translateJacobian, mobPoseBone));
            drag.modelPartTranslate(mobPoseBone);
            drag.setRotateAxes(GizmoDrag.computeRotateAxes(
                transform.getTransform(),
                rotationSampler
            ));
            this.editor.applyStateForSampling(tick);
            drag.setFrameAxes(
                this.editor.renderer.toSceneMatrix(this.getOriginMatrix(transition)),
                this.editor.renderer.toSceneMatrix(this.getParentOriginMatrix(transition))
            );
            drag.setAdditiveRotationBase(this.poseRotationBase(transition));
            this.editor.applyStateForSampling(tick);
        }

        return drag;
    }

    private Vector3f poseRotationBase(float transition)
    {
        if (!(this.keyframeEditor.editor instanceof UIPoseKeyframeFactory))
        {
            return null;
        }

        UIKeyframeSheet sheet = this.keyframeEditor.getSheet(this.keyframeEditor.editor.getKeyframe());
        Pair<String, Boolean> bone = this.keyframeEditor.getBone();
        Form root = FormUtils.getRoot(this.editor.form);
        BaseValueBasic property = sheet == null ? null : FormUtils.getProperty(root, sheet.id);

        return property instanceof ValuePose pose && bone != null
            ? FormUtils.additivePoseRotationBase(pose, StringUtils.fileName(bone.a), this.getMatrixEntry(transition).evaluatedRotation())
            : null;
    }

    private Matrix3f getMobTranslateJacobian(float transition)
    {
        Matrix4f origin = this.getMatrixEntry(transition).origin();

        return origin == null ? null : GizmoDrag.computeModelPartTranslateJacobian(this.editor.renderer.toSceneMatrix(origin));
    }

    private boolean isMobPoseBoneTrack()
    {
        if (this.keyframeEditor == null || this.keyframeEditor.editor == null)
        {
            return false;
        }

        UIKeyframeSheet sheet = this.keyframeEditor.getSheet(this.keyframeEditor.editor.getKeyframe());
        Pair<String, Boolean> bone = this.keyframeEditor.getBone();
        boolean poseBone = sheet != null
            && bone != null
            && bone.a != null
            && !bone.a.isEmpty()
            && (sheet.isBoneTrack || this.keyframeEditor.editor instanceof UIPoseKeyframeFactory);
        Form form = sheet == null
            ? null
            : (sheet.form != null ? sheet.form : (sheet.property == null ? null : FormUtils.getForm(sheet.property)));

        return poseBone && form instanceof MobForm;
    }

    public Matrix4f getOrigin(float transition)
    {
        return this.getOriginInternal(transition, false);
    }

    public TransformSpace getGizmoSpace()
    {
        return this.keyframeEditor == null ? TransformSpace.LOCAL : this.keyframeEditor.getBoneSpace();
    }

    public Matrix4f getOriginMatrix(float transition)
    {
        return this.getOriginInternal(transition, true);
    }

    /** The parent frame paired with the rotation-bearing matrix for a live axis walk. */
    public Matrix4f getParentOriginMatrix(float transition)
    {
        Matrix4f origin = this.getMatrixEntry(transition).origin();
        return origin == null ? Matrices.EMPTY_4F : origin;
    }

    private Matrix4f getOriginInternal(float transition, boolean forceMatrix)
    {
        MatrixCacheEntry entry = this.getMatrixEntry(transition);
        Pair<String, Boolean> bone = this.keyframeEditor == null ? null : this.keyframeEditor.getBone();

        if (bone == null)
        {
            return Matrices.EMPTY_4F;
        }

        Matrix4f matrix = (forceMatrix || bone.b) ? entry.matrix() : entry.origin();

        return matrix == null ? Matrices.EMPTY_4F : matrix;
    }

    private MatrixCacheEntry getMatrixEntry(float transition)
    {
        if (this.keyframeEditor == null)
        {
            return new MatrixCacheEntry(null, null);
        }

        Pair<String, Boolean> bone = this.keyframeEditor.getBone();

        if (bone == null)
        {
            return new MatrixCacheEntry(null, null);
        }

        Form root = FormUtils.getRoot(this.editor.form);
        MatrixCache map = this.editor.renderer.collectPreviewMatrices(root, transition);

        return map.get(bone.a);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.state != null && (this.poseOverlayCount != BBSSettings.recordingPoseOverlays.get()
            || this.transformOverlayCount != BBSSettings.recordingTransformOverlays.get())) this.setState(this.state);
        boolean notEditing = this.keyframeEditor == null || !this.keyframeEditor.view.isEditing();
        this.categoryBar.setVisible(this.state != null && notEditing);
        this.partHeader.setVisible(this.state != null && notEditing && this.keyframeEditor != null
            && this.keyframeEditor.view.getGraph() == this.keyframeEditor.view.getDopeSheet());
        if (this.partHeader.isVisible())
        {
            int width = Math.min(this.keyframeEditor.view.getLabelWidth(), this.keyframeEditor.view.area.w);
            if (this.partHeader.area.w != width) this.partHeader.w(width).resize();
        }
        if (this.keyframeEditor != null)
        {
            UIPropTransform transform = this.getEditableTransform();

            if (transform != null)
            {
                transform.hotkeyDrag(() ->
                {
                    UIContext current = this.getContext();

                    return this.buildGizmoDrag(transform, current == null ? 0F : current.getTransition());
                });
            }

            this.editArea.area.render(context.batcher, Colors.A75);
        }

        super.render(context);
    }
}
