package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.renderers.VanillaBoneHierarchy;
import mchorse.bbs_mod.forms.renderers.VanillaRendererBones;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityModelSet.class)
public class EntityModelLoaderMixin
{
    @Inject(method = "bakeLayer", at = @At("RETURN"))
    private void bbs$registerBoneHierarchy(ModelLayerLocation layer, CallbackInfoReturnable<ModelPart> info)
    {
        VanillaBoneHierarchy.register(layer, info.getReturnValue());
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void bbs$clearBoneHierarchies(ResourceManager manager, CallbackInfo info)
    {
        VanillaRendererBones.clear();
        VanillaBoneHierarchy.clear();
    }
}
