package mchorse.bbs_mod.api.events;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;

/** Registers addon resources before forms and language files are loaded. */
public class RegisterSourcePacksEvent
{
    public final AssetProvider provider;

    public RegisterSourcePacksEvent(AssetProvider provider)
    {
        this.provider = provider;
    }

    /** Serves {@code modId:...} from {@code assets/<modId>/assets} in the addon's own jar. */
    public void registerAddon(String modId, Class<?> clazz)
    {
        this.provider.register(new InternalAssetsSourcePack(modId, modId, "assets/" + modId + "/assets", clazz));
    }
}
