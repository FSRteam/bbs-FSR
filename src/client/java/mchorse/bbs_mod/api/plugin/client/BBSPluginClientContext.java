package mchorse.bbs_mod.api.plugin.client;

public interface BBSPluginClientContext
{
    BBSPluginKeyMappingRegistry keyMappings();

    BBSPluginRendererRegistry renderers();

    BBSPluginFormClientRegistry forms();

    BBSPluginClipClientRegistry clips();
}
