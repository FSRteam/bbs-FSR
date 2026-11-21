package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.BoneSelection;
import mchorse.bbs_mod.ui.utils.IBoneSelectionHost;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.bones.UIBonePicker;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public abstract class UIFormPanel <T extends Form> extends UIElement
{
    private static final float DEFAULT_OPTIONS_WIDTH = 0.2F;

    private static final Map<String, Boolean> sectionFolds = new HashMap<>();

    protected UIForm editor;
    protected T form;

    public UIScrollView options;
    public UIDraggable draggable;

    public UIFormPanel(UIForm editor)
    {
        this.editor = editor;

        UISplitter splitter = UISplitter.fraction("form_panel." + this.getClass().getSimpleName(), this.getDefaultOptionsWidth(), 0F, 0.5F);
        this.draggable = splitter;
        splitter.measure(this, this::getParent).fromEnd().onChange(() ->
        {
            this.options.w(splitter.getValue()).resize();
            this.draggable.resize();
        });

        this.options = UI.scrollView(UIConstants.MARGIN, UIConstants.SCROLL_PADDING);
        this.options.scroll.cancelScrolling();
        this.options.relative(this).x(1F).w(splitter.getValue()).minW(120).h(1F).anchorX(1F);

        this.draggable.relative(this.options).x(0F).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);

        this.add(this.options, this.draggable);
    }

    protected float getDefaultOptionsWidth()
    {
        return DEFAULT_OPTIONS_WIDTH;
    }

    protected UISection section(IKey title, String id, boolean defaultExpanded)
    {
        UISection section = new UISection(title);

        section.setExpanded(sectionFolds.getOrDefault(id, defaultExpanded));
        section.onToggle((s) -> sectionFolds.put(id, s.isExpanded()));

        return section;
    }

    public void startEdit(T form)
    {
        this.form = form;
    }

    public void finishEdit()
    {}

    public void pickBone(String bone)
    {}

    public boolean pickBoneInList(String bone)
    {
        return false;
    }

    /** Bone currently owned by a single-bone panel, or empty when this panel has no bone target. */
    public String getSelectedBone()
    {
        return "";
    }
    private final BoneSelection detachedSelection = new BoneSelection();

    protected BoneSelection boneSelection()
    {
        IBoneSelectionHost host = this.getAncestor(IBoneSelectionHost.class);

        return host == null ? this.detachedSelection : host.getBoneSelection();
    }

    protected UIBonePicker.Viewport viewportBonePicking()
    {
        return new UIBonePicker.Viewport()
        {
            @Override
            public void startPicking(Consumer<String> callback)
            {
                UIFormEditor formEditor = UIFormPanel.this.getParent(UIFormEditor.class);

                if (formEditor == null)
                {
                    callback.accept(null);

                    return;
                }

                formEditor.startBonePicking((pair) -> callback.accept(pair != null && pair.a == UIFormPanel.this.form ? pair.b : null));
            }

            @Override
            public void stopPicking()
            {
                UIFormEditor formEditor = UIFormPanel.this.getParent(UIFormEditor.class);

                if (formEditor != null)
                {
                    formEditor.stopBonePicking();
                }
            }
        };
    }
}
