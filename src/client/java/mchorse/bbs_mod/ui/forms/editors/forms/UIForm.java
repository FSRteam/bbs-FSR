package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIGeneralFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIPanelBase;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.joml.Matrices;
import org.joml.Matrix4f;

public abstract class UIForm <T extends Form> extends UIPanelBase<UIFormPanel<T>>
{
    public UIFormEditor editor;

    public T form;
    public UIFormPanel<T> defaultPanel;
    public UIGeneralFormPanel generalPanel;

    private UIPropTransform general;

    public UIForm()
    {
        super(Direction.LEFT);

        this.keys().register(Keys.FILM_CONTROLLER_CYCLE_EDITORS, this::cyclePanels);
    }

    public UIPropTransform getEditableTransform()
    {
        this.setPanel(this.generalPanel);

        return this.general;
    }

    private void cyclePanels()
    {
        int index = this.panels.indexOf(this.view);
        int newIndex = MathUtils.cycler(index + (Window.isShiftPressed() ? -1 : 1), this.panels);

        this.setPanel(this.panels.get(newIndex));
        UIUtils.playClick();
    }

    public Matrix4f getOrigin(float transition)
    {
        return this.getOrigin(transition, FormUtils.getPath(this.form), this.generalPanel != null && this.generalPanel.transform.isLocal());
    }

    /**
     * Always returns the bone's full local matrix (including its own rotation),
     * irrespective of the LOCAL/GLOBAL UI toggle. Required for sampling-based
     * gizmo helpers that need the rotation to be visible in the matrix &mdash;
     * the rotation-stripped &quot;origin&quot; variant doesn't move when
     * {@code transform.rotate} is perturbed, so axis extraction would silently
     * fall back to identity.
     */
    public Matrix4f getOriginMatrix(float transition)
    {
        return this.getOrigin(transition, FormUtils.getPath(this.form), true);
    }

    /**
     * Origin for the body part gizmo mode: always the edited form's OWN root frame (where the body
     * part actually renders &mdash; attach bone &middot; part transform &middot; form transform),
     * ignoring any type-specific override like the model's selected pose bone. The drag math stays
     * correct for editing the body part transform because the attach bone is constant w.r.t. it and
     * the form's own transform cancels in the Jacobian/rotate-axes derivatives.
     */
    public Matrix4f getBodyPartGizmoOrigin(float transition, boolean local)
    {
        return this.getOrigin(transition, FormUtils.getPath(this.form), local);
    }

    protected Matrix4f getOrigin(float transition, String path, boolean local)
    {
        Form root = FormUtils.getRoot(this.form);
        MatrixCache map = FormUtilsClient.getRenderer(root).collectMatrices(this.editor.renderer.getTargetEntity(), transition);
        Matrix4f matrix = local ? map.get(path).matrix() : map.get(path).origin();

        return matrix == null ? Matrices.EMPTY_4F : matrix;
    }

    protected void registerDefaultPanels()
    {
        UIGeneralFormPanel panel = new UIGeneralFormPanel(this);

        this.registerPanel(panel, UIKeys.FORMS_EDITORS_GENERAL, Icons.GEAR);

        this.generalPanel = panel;
        this.general = panel.transform;
    }

    public void setEditor(UIFormEditor editor)
    {
        this.editor = editor;
    }

    public void startEdit(T form)
    {
        this.startEdit(form, null);
    }

    public void startEdit(T form, Class<?> preferredPanel)
    {
        this.form = form;

        for (UIFormPanel<T> panel : this.panels)
        {
            panel.startEdit(form);
        }

        this.setPanel(this.findPanel(preferredPanel));
    }

    private UIFormPanel<T> findPanel(Class<?> panelClass)
    {
        if (panelClass != null)
        {
            for (UIFormPanel<T> panel : this.panels)
            {
                if (panel.getClass() == panelClass)
                {
                    return panel;
                }
            }
        }

        return this.defaultPanel;
    }

    public void finishEdit()
    {
        for (UIFormPanel<T> panel : this.panels)
        {
            panel.finishEdit();
        }
    }

    public void pickBone(String bone)
    {
        if (this.view != null)
        {
            this.view.pickBone(bone);
        }
    }

    /**
     * Toggle a bone in the pose editor's multi-selection without rebuilding the panel.
     * Only model forms with a live pose editor handle this operation.
     */
    public boolean toggleBoneSelection(String bone)
    {
        return false;
    }

    public Class<?> getActivePanelClass()
    {
        return this.view == null ? null : this.view.getClass();
    }

    public void pickBoneFromViewport(String bone, Class<?> preferredPanel)
    {
        if (preferredPanel != null)
        {
            for (UIFormPanel<T> panel : this.panels)
            {
                if (panel.getClass() == preferredPanel)
                {
                    if (panel.pickBoneInList(bone))
                    {
                        this.setPanel(panel);

                        return;
                    }

                    break;
                }
            }
        }

        this.setPanel(this.defaultPanel);
        this.pickBone(bone);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.view != null)
        {
            this.view.options.area.render(context.batcher, BBSSettings.deepSurface());
        }

        super.render(context);
    }

    @Override
    protected void renderBackground(UIContext context, int x, int y, int w, int h)
    {
        context.batcher.box(x, y, x + w, y + h, BBSSettings.deepSurface());
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);

        data.putInt("panel", this.panels.indexOf(this.view));
        data.putDouble("scroll", this.view.options.scroll.getScroll());
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);

        this.setPanel(this.panels.get(data.getInt("panel")));
        this.view.options.scroll.setScroll(data.getDouble("scroll"));
    }
}
