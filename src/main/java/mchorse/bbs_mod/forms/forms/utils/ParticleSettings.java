package mchorse.bbs_mod.forms.forms.utils;

import mchorse.bbs_mod.data.IMapSerializable;
import mchorse.bbs_mod.data.types.MapType;
import net.minecraft.resources.ResourceLocation;

public class ParticleSettings implements IMapSerializable
{
    public ResourceLocation particle = ResourceLocation.fromNamespaceAndPath("minecraft", "flame");
    public String arguments = "";

    @Override
    public void toData(MapType data)
    {
        data.putString("particle", this.particle.toString());
        data.putString("args", this.arguments);
    }

    @Override
    public void fromData(MapType data)
    {
        ResourceLocation resourceLocation = ResourceLocation.tryParse(data.getString("particle"));

        this.particle = resourceLocation == null
            ? ResourceLocation.fromNamespaceAndPath("minecraft", "flame")
            : resourceLocation;
        this.arguments = data.getString("args");
    }
}
