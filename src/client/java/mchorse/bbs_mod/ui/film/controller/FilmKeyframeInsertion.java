package mchorse.bbs_mod.ui.film.controller;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIRecordOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UIUtils;
import net.minecraft.client.Minecraft;
import static mchorse.bbs_mod.ui.film.controller.UIFilmController.*;

/** Manual replay keyframe insertion. */
public class FilmKeyframeInsertion
{
    private final UIFilmController controller;

    public FilmKeyframeInsertion(UIFilmController controller)
    {
        this.controller = controller;
    }




    public void insertFrame()
    {
        if (!this.controller.sceneOwner)
        {
            this.controller.panel.getController().insertFrame();

            return;
        }

        Replay replay = this.controller.getReplay();

        if (replay == null)
        {
            return;
        }

        TrackCategory category = this.controller.panel.replayEditor.getCategory();

        if (category == TrackCategories.POSE)
        {
            UIReplaysEditorUtils.insertPoseKeyframesAtTick(replay, this.controller.getTick(), this.controller.panel.replayEditor.getExpandedTracks());
            return;
        }

        if (category != TrackCategories.REPLAY)
        {
            return;
        }

        /* The replay tab alone owns the recorded player channels. */
        if (Window.isCtrlPressed())
        {
            this.controller.toggleMousePointer(false);

            UIRecordOverlayPanel panel = new UIRecordOverlayPanel(
                UIKeys.FILM_CONTROLLER_INSERT_FRAME_TITLE,
                UIKeys.FILM_CONTROLLER_INSERT_FRAME_DESCRIPTION,
                (groups) ->
                {
                    BaseValue.edit(replay.keyframes, (keyframes) ->
                    {
                        keyframes.record(this.controller.getTick(), this.controller.getCurrentEntity(), groups);
                    });
                }
            );

            panel.onClose((event) -> this.controller.toggleMousePointer(this.controller.controlled != null));

            UIOverlay.addOverlay(this.controller.getContext(), panel);
        }
        else
        {
            List<String> chosenGroups = Arrays.asList(ReplayKeyframes.GROUP_POSITION, ReplayKeyframes.GROUP_ROTATION);

            if (this.controller.mouse.getMode() == 1) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_LEFT_STICK);
            else if (this.controller.mouse.getMode() == 2) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_RIGHT_STICK);
            else if (this.controller.mouse.getMode() == 3) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_TRIGGERS);
            else if (this.controller.mouse.getMode() == 4) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_EXTRA1);
            else if (this.controller.mouse.getMode() == 5) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_EXTRA2);

            final List<String> groups = chosenGroups;

            BaseValue.edit(replay.keyframes, (keyframes) ->
            {
                keyframes.record(this.controller.getTick(), this.controller.getCurrentEntity(), groups);
            });
        }
    }

    public void insertPlayerFrame()
    {
        if (!this.controller.sceneOwner)
        {
            this.controller.panel.getController().insertPlayerFrame();

            return;
        }

        Replay replay = this.controller.getReplay();

        if (replay == null || Minecraft.getInstance().player == null)
        {
            return;
        }

        Morph morph = Morph.getMorph(Minecraft.getInstance().player);

        if (morph == null || morph.entity == null)
        {
            return;
        }

        IEntity player = morph.entity;
        int tick = this.controller.getTick();

        BaseValue.edit(replay.keyframes, (keyframes) ->
        {
            keyframes.x.insert(tick, player.getX());
            keyframes.y.insert(tick, player.getY());
            keyframes.z.insert(tick, player.getZ());
            keyframes.yaw.insert(tick, (double) player.getYaw());
            keyframes.pitch.insert(tick, (double) player.getPitch());
            keyframes.headYaw.insert(tick, (double) player.getHeadYaw());
            keyframes.bodyYaw.insert(tick, (double) player.getBodyYaw());
        });

        UIUtils.playClick();
    }
}
