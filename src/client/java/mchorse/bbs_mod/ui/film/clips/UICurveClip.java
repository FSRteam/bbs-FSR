package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;

public class UICurveClip extends UIClip<CurveClip>
{
    public UIKeyframeEditor keyframes;
    public UIButton editKey;
    public UIButton edit;

    public UICurveClip(CurveClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.keyframes = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.editor, consumer));
        this.keyframes.view.backgroundRenderer((context) ->
        {
            UIReplaysEditor.renderBackground(context, this.keyframes.view, (Clips) this.clip.getParent(), this.clip.tick.get());
        });
        this.keyframes.view.duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("curve_keyframes");

        this.editKey = new UIButton(UIKeys.CAMERA_PANELS_PICK_KEY, (b) ->
        {
            this.getContext().replaceContextMenu((menu) ->
            {
                this.addOption(menu, UIKeys.CAMERA_PANELS_CURVES_SUN_ROTATION, "sun_rotation", Icons.SUN);
            });
        });

        this.edit = new UIButton(UIKeys.CAMERA_PANELS_EDIT_KEYFRAMES, (b) ->
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
            this.keyframes.view.editSheet(this.keyframes.view.getGraph().getSheets().get(0));
        });
        this.edit.keys().register(Keys.FORMS_EDIT, () -> this.edit.clickItself());
    }

    private void addOption(ContextMenuManager menu, IKey label, String key, Icon icon)
    {
        menu.action(icon, label, this.clip.key.get().equals(key), () -> this.clip.key.set(key));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(UIClip.label(UIKeys.C_CLIP.get("bbs:curve")).marginTop(12), this.editKey, this.edit);
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.keyframes.setChannel(this.clip.channel, Colors.ACTIVE);
    }

    @Override
    public void updateDuration(int duration)
    {
        super.updateDuration(duration);

        this.keyframes.updateConverter();
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);

        if (data.getString("embed").equals("curve"))
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.editSheet(this.keyframes.view.getGraph().getSheets().get(0));
            this.keyframes.view.resetView();
        }
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);

        if (this.keyframes.hasParent())
        {
            data.putString("embed", "curve");
        }
    }
}