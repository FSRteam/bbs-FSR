package mchorse.bbs_mod.api.resources;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.resources.ISourcePack;

public interface BBSResourceRegistry
{
    BBSRegistrationResult registerSourcePack(ISourcePack sourcePack);

    BBSRegistrationResult registerSourcePackFirst(ISourcePack sourcePack);
}
