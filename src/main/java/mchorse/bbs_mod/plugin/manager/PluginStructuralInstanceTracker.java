package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MissingForm;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Weak inventory of persisted holders that can contain generation-owned structural instances. */
public final class PluginStructuralInstanceTracker
{
    private static final Map<ValueForm, Boolean> FORMS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Clips, Boolean> CLIPS = Collections.synchronizedMap(new WeakHashMap<>());

    private PluginStructuralInstanceTracker() {}

    public static void track(ValueForm value)
    {
        FORMS.put(value, Boolean.TRUE);
    }

    public static void track(Clips value)
    {
        CLIPS.put(value, Boolean.TRUE);
    }

    static Snapshot snapshot(Set<Class<?>> formTypes, Set<Class<?>> clipTypes)
    {
        List<FormSnapshot> forms = new ArrayList<>();
        List<Clips.Snapshot> clips = new ArrayList<>();

        synchronized (FORMS)
        {
            for (ValueForm holder : List.copyOf(FORMS.keySet()))
            {
                Form form = holder.getOriginalValue();

                if (containsType(form, formTypes))
                {
                    MapType data = FormUtils.toData(form);
                    holder.replaceStructuralValue(new MissingForm(data));
                    forms.add(new FormSnapshot(holder, data));
                }
            }
        }

        synchronized (CLIPS)
        {
            for (Clips holder : List.copyOf(CLIPS.keySet()))
            {
                Clips.Snapshot snapshot = holder.snapshotStructuralTypes(clipTypes);

                if (!snapshot.isEmpty())
                {
                    clips.add(snapshot);
                }
            }
        }

        return new Snapshot(forms, clips);
    }

    private static boolean containsType(Form form, Set<Class<?>> types)
    {
        if (form == null || types.isEmpty())
        {
            return false;
        }

        if (types.contains(form.getClass()))
        {
            return true;
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            if (containsType(part.getForm(), types))
            {
                return true;
            }
        }

        return false;
    }

    static final class Snapshot
    {
        private final List<FormSnapshot> forms;
        private final List<Clips.Snapshot> clips;

        private Snapshot(List<FormSnapshot> forms, List<Clips.Snapshot> clips)
        {
            this.forms = forms;
            this.clips = clips;
        }

        int rebuild(BiConsumer<String, Throwable> failure)
        {
            int failed = 0;

            for (FormSnapshot snapshot : this.forms)
            {
                if (!BBSMod.getForms().has(snapshot.data))
                {
                    /* Type no longer registered (plugin generation retired it) - this is
                     * the expected degradation path, not a rebuild failure, so the
                     * placeholder stays without raising a diagnostic. Note: FormUtils.fromData
                     * is intentionally not used here, it self-heals *any* exception (including
                     * genuine deserialization failures) into a MissingForm, which would hide
                     * real rebuild failures from this diagnostic path. */
                    snapshot.holder.replaceStructuralValue(new MissingForm(snapshot.data));

                    continue;
                }

                try
                {
                    Form form = BBSMod.getForms().fromData(snapshot.data);
                    snapshot.holder.replaceStructuralValue(form == null ? new MissingForm(snapshot.data) : form);
                }
                catch (Throwable error)
                {
                    failed += 1;
                    snapshot.holder.replaceStructuralValue(new MissingForm(snapshot.data));
                    failure.accept(snapshot.data.getString("id", "<unknown-form>"), error);
                }
            }

            for (Clips.Snapshot snapshot : this.clips)
            {
                failed += snapshot.rebuild(failure);
            }

            return failed;
        }
    }

    private record FormSnapshot(ValueForm holder, MapType data) {}
}
