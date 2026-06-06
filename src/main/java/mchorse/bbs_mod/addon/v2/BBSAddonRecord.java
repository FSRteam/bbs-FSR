package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;

final class BBSAddonRecord
{
    final BBSAddonDescriptor descriptor;
    final BBSAddon addon;
    final BBSAddonDiagnosticRecord diagnostics;

    BBSAddonRecord(BBSAddonDescriptor descriptor, BBSAddon addon, BBSAddonDiagnosticRecord diagnostics)
    {
        this.descriptor = descriptor;
        this.addon = addon;
        this.diagnostics = diagnostics;
    }
}
