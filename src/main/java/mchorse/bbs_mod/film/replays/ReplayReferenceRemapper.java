package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.camera.clips.modifiers.EntityClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.values.ValueAnchor;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.List;

/** Owns the old-to-new Replay index transaction for every persisted reference. */
public final class ReplayReferenceRemapper
{
    private ReplayReferenceRemapper()
    {}

    @SuppressWarnings("unchecked")
    public static void remap(Film film, List<Replay> previousOrder)
    {
        int[] oldToNew = ReplayIndexRemapper.create(previousOrder, film.replays.getList());

        for (Replay replay : film.replays.getList())
        {
            remap(replay.actions.get(), oldToNew);

            Form form = replay.form.get();

            if (form != null)
            {
                remapValue(form, oldToNew);
            }

            for (KeyframeChannel<?> channel : replay.properties.properties.values())
            {
                if (channel.getFactory() != KeyframeFactories.ANCHOR)
                {
                    continue;
                }

                KeyframeChannel<Anchor> anchors = (KeyframeChannel<Anchor>) channel;

                for (Keyframe<Anchor> keyframe : anchors.getKeyframes())
                {
                    Anchor anchor = keyframe.getValue();

                    anchor.replay = ReplayIndexRemapper.remap(anchor.replay, oldToNew);
                }
            }
        }

        for (Clip clip : film.camera.get())
        {
            if (clip instanceof EntityClip entityClip)
            {
                int remapped = ReplayIndexRemapper.remap(entityClip.selector.get(), oldToNew);

                if (remapped != entityClip.selector.get())
                {
                    entityClip.selector.set(remapped);
                }
            }
        }
    }

    public static void remap(Iterable<? extends BaseValue> values, int[] oldToNew)
    {
        for (BaseValue value : values)
        {
            remapValue(value, oldToNew);
        }
    }

    private static void remapValue(BaseValue value, int[] oldToNew)
    {
        if (value instanceof ActionTarget target)
        {
            if (!target.replayId.get().isEmpty())
            {
                target.replayId.set(ReplayIndexRemapper.remap(target.replayId.get(), oldToNew));
            }

            return;
        }

        if (value instanceof ValueAnchor valueAnchor)
        {
            Anchor original = valueAnchor.getOriginalValue();
            Anchor runtime = valueAnchor.getRuntimeValue();

            remapAnchor(original, oldToNew);

            if (runtime != original)
            {
                remapAnchor(runtime, oldToNew);
            }

            return;
        }

        if (value instanceof BaseValueGroup group)
        {
            for (BaseValue child : group.getAll())
            {
                remapValue(child, oldToNew);
            }
        }
    }

    private static void remapAnchor(Anchor anchor, int[] oldToNew)
    {
        if (anchor != null)
        {
            anchor.replay = ReplayIndexRemapper.remap(anchor.replay, oldToNew);
        }
    }
}
