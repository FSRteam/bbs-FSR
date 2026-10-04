package mchorse.bbs_mod.utils.iris;

public interface IrisPipelineResources
{
    void bbs$setResourceOwner(ViewResourceOwner owner);

    void bbs$releaseResources();

    void bbs$resetTemporalHistory();
}
