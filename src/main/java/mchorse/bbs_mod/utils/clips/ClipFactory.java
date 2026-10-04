package mchorse.bbs_mod.utils.clips;

import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.factory.MapFactory;

/**
 * The factory of clips, which answers an unknown clip type with a data-preserving stand-in
 * rather than with an exception the caller has to decide what to do with. See {@link MissingClip}.
 *
 * <p>FSR's placeholder is stronger than a bare stand-in — it carries the original type into the
 * timeline as a "missing clip" label and can be rebuilt once the plugin providing the type is
 * back — so this factory reuses it instead of introducing a second placeholder class.</p>
 */
public class ClipFactory extends MapFactory<Clip, ClipFactoryData>
{
    @Override
    public Clip createUnknown(Link type, MapType data)
    {
        return new MissingClip(data);
    }
}
