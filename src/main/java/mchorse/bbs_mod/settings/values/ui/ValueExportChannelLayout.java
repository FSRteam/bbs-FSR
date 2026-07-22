package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.settings.values.core.ValueString;

import java.util.concurrent.atomic.AtomicBoolean;

/** Persisted video-export layout with one-time compatibility normalization. */
public class ValueExportChannelLayout extends ValueString
{
    private final AtomicBoolean migrationDiagnostic = new AtomicBoolean();

    public ValueExportChannelLayout(String id)
    {
        super(id, ChannelLayout.MONO.id());
    }

    @Override
    public void set(String value)
    {
        super.set(normalize(value));
    }

    @Override
    public void set(String value, int flag)
    {
        super.set(normalize(value), flag);
    }

    @Override
    public void fromData(mchorse.bbs_mod.data.types.BaseType data)
    {
        super.fromData(data);
        ChannelLayout resolved = ChannelLayout.normalizeExport(this.getOriginalValue(), this::diagnoseOnce);

        super.set(resolved.id());
    }

    /** Resolve the current value without exposing reserved/unknown layouts. */
    public ChannelLayout getResolved()
    {
        return ChannelLayout.normalizeExport(this.get(), this::diagnoseOnce);
    }

    public static String normalize(String value)
    {
        return ChannelLayout.normalizeExport(value).id();
    }

    private void diagnoseOnce(String message)
    {
        if (this.migrationDiagnostic.compareAndSet(false, true))
        {
            com.mojang.logging.LogUtils.getLogger().warn(message);
        }
    }
}
