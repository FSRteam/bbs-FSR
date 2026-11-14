package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.forms.forms.VideoForm;
import mchorse.bbs_mod.ui.utils.values.UIValues;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.clips.UIVideoClip;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIStringOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

public class UIVideoFormPanel extends UIFormPanel<VideoForm>
{
    public final UIToggle loop;
    public final UIToggle billboard;
    public final UITrackpad offset;

    public UIVideoFormPanel(UIForm editor)
    {
        super(editor);

        UIButton pick = new UIButton(UIKeys.CAMERA_PANELS_VIDEO_PICK, (b) ->
        {
            UIStringOverlayPanel panel = UIStringOverlayPanel.links(UIKeys.CAMERA_PANELS_VIDEO_PICK,
                UIVideoClip.getVideoLinks(), (link) -> this.form.video.set(link));

            UIOverlay.addOverlay(this.getContext(), panel.set(this.form.video.get()));
        });

        this.loop = UIValues.toggle(UIKeys.CAMERA_PANELS_VIDEO_LOOP, () -> this.form.loop);
        this.billboard = UIValues.toggle(UIKeys.FORMS_EDITORS_VIDEO_BILLBOARD, () -> this.form.billboard);
        this.offset = UIValues.trackpad(() -> this.form.videoOffset);
        this.offset.tooltip(UIKeys.FORMS_EDITORS_VIDEO_OFFSET);

        this.options.add(pick, this.loop, this.billboard,
            UI.label(UIKeys.FORMS_EDITORS_VIDEO_PLAYBACK).marginTop(UIConstants.SECTION_GAP), this.offset);
    }

    @Override
    public void startEdit(VideoForm form)
    {
        super.startEdit(form);

        this.loop.setValue(form.loop.get());
        this.billboard.setValue(form.billboard.get());
        this.offset.setValue(form.videoOffset.get());
    }
}
