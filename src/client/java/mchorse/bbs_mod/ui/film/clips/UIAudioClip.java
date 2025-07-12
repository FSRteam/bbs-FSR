package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.io.File;

public class UIAudioClip extends UIClip<AudioClip>
{
    public UIButton pickAudio;
    public UIIcon openFolder;
    public UITrackpad offset;

    public UIAudioClip(AudioClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.pickAudio = new UIButton(UIKeys.CAMERA_PANELS_AUDIO_PICK_AUDIO, (b) ->
        {
            UISoundOverlayPanel panel = new UISoundOverlayPanel((l) -> this.clip.audio.set(l));

            UIOverlay.addOverlay(this.getContext(), panel.set(this.clip.audio.get()));
        });

        this.openFolder = new UIIcon(Icons.FOLDER, (b) ->
        {
            Link link = this.clip.audio.get();
            File file = BBSMod.getAudioFolder();

            if (link != null)
            {
                File audioFile = BBSMod.getProvider().getFile(link);

                if (audioFile.exists())
                {
                    file = audioFile.getParentFile();
                }
            }

            UIUtils.openFolder(file);
        });

        this.offset = new UITrackpad((v) -> this.clip.offset.set(v.intValue()));
        this.offset.integer();
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(UI.column(UIClip.label(UIKeys.C_CLIP.get("bbs:audio")), UI.row(this.pickAudio, this.openFolder)).marginTop(12));
        this.panels.add(UI.column(UIClip.label(UIKeys.CAMERA_PANELS_AUDIO_OFFSET).marginTop(6), this.offset).marginTop(12));
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.offset.setValue(this.clip.offset.get());
    }
}