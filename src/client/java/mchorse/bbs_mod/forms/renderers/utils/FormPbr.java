package mchorse.bbs_mod.forms.renderers.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.forms.utils.FormMaterial;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.iris.IrisUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;

/**
 * The PBR sliders' albedo redirection: a material with slider values set gets its own GL copy
 * of the albedo texture, because Iris caches PBR holders BY THE ALBEDO'S GL ID — two forms
 * sharing one texture but with different sliders would otherwise fight over one holder. The
 * variant's key identifies the form, material and albedo frame; slider changes refresh that
 * variant's tracked PBR holder. Iris-only: without a shader pack nothing reads the maps, so
 * the albedo stays the shared original.
 */
public class FormPbr
{
    /**
     * Variant key strings are built once per form, material and albedo texture id. Weak owners
     * release their variants when a form is collected; each owner has a unique lifetime id.
     * Primitive texture keys preserve animated-frame identity without rebuilding strings per draw.
     */
    private static final ReferenceQueue<ModelForm> RELEASED_FORMS = new ReferenceQueue<>();
    private static final Map<ModelForm, Owner> VARIANT_KEYS = new WeakHashMap<>();
    private static final Set<Owner> OWNERS = new HashSet<>();
    private static long nextOwner;

    private static class Owner extends WeakReference<ModelForm>
    {
        private final long id = nextOwner++;
        private final Map<String, Int2ObjectMap<String>> keys = new HashMap<>();

        private Owner(ModelForm form)
        {
            super(form, RELEASED_FORMS);
        }

        private void release()
        {
            this.keys.values().forEach((byTexture) -> byTexture.values().forEach(BBSModClient.getTextures()::deleteVariant));
            this.keys.clear();
            OWNERS.remove(this);
        }
    }

    private static void releaseCollectedForms()
    {
        Owner owner;
        while ((owner = (Owner) RELEASED_FORMS.poll()) != null) owner.release();
    }

    /**
     * The texture to actually bind as this material's albedo: the PBR variant when the material
     * has sliders set (statically or by an animation track) and Iris is active, the original
     * otherwise. The empty material name is the whole-form level of a single-texture model.
     *
     * <p>The variant is one stable GL copy per material instance; slider edits and ANIMATED
     * sliders re-track the snapshot and invalidate Iris' PBR holder for that id, so the maps
     * regenerate lazily without new albedo copies per value (see {@code IrisUtils#trackPbrVariant}).</p>
     */
    public static Texture resolveAlbedo(ModelForm form, String material, Link link, Texture texture)
    {
        if (form == null || link == null || texture == null || !BBSRendering.isIrisShadersEnabled())
        {
            return texture;
        }

        releaseCollectedForms();
        String materialKey = material == null ? "" : material;
        float[] sliders = sliders(form, materialKey);

        if (sliders == null && !materialKey.isEmpty())
        {
            /* The material says nothing, so the whole-form level speaks for it — that's where a
             * single-material model's sliders live (the tab writes them under the "" key, while
             * the draw asks by the model's real material name). */
            materialKey = "";
            sliders = sliders(form, materialKey);
        }

        if (sliders == null)
        {
            return texture;
        }

        float smoothness = sliders[0];
        float metallic = sliders[1];
        float sss = sliders[2];
        float emission = sliders[3];
        float relief = sliders[4];

        /* One albedo copy per material INSTANCE (identity): two forms sharing a texture with
         * different sliders need different GL ids, because Iris caches PBR holders by that id. */
        Owner owner = VARIANT_KEYS.get(form);
        if (owner == null)
        {
            owner = new Owner(form);
            VARIANT_KEYS.put(form, owner);
            OWNERS.add(owner);
        }
        Int2ObjectMap<String> byTexture = owner.keys.computeIfAbsent(materialKey, (key) -> new Int2ObjectOpenHashMap<>());
        String variantKey = byTexture.get(texture.id);
        if (variantKey == null)
        {
            variantKey = "pbr:" + owner.id + ":" + materialKey + ":" + texture.id;
            byTexture.put(texture.id, variantKey);
        }
        Texture variant = BBSModClient.getTextures().getVariant(link, variantKey, texture);

        if (variant == null || variant == BBSModClient.getTextures().getError())
        {
            return texture;
        }

        IrisUtils.trackPbrVariant(variant, link, smoothness, metallic, sss, emission, relief);

        return variant;
    }

    public static void release(ModelForm form)
    {
        Owner owner = VARIANT_KEYS.remove(form);
        if (owner != null) owner.release();
        releaseCollectedForms();
    }

    /** The level's five effective sliders, or null when every one of them is neutral. */
    private static float[] sliders(ModelForm form, String material)
    {
        FormMaterial formMaterial = form.materials.getMaterial(material);
        float[] values = {
            effective(form, material, formMaterial, TrackId.MATERIAL_PROP_SMOOTHNESS),
            effective(form, material, formMaterial, TrackId.MATERIAL_PROP_METALLIC),
            effective(form, material, formMaterial, TrackId.MATERIAL_PROP_SSS),
            effective(form, material, formMaterial, TrackId.MATERIAL_PROP_PIXEL_EMISSION),
            effective(form, material, formMaterial, TrackId.MATERIAL_PROP_RELIEF)
        };

        for (float value : values)
        {
            if (value > 0F)
            {
                return values;
            }
        }

        return null;
    }

    private static float effective(ModelForm form, String material, FormMaterial formMaterial, String property)
    {
        float staticValue = 0F;

        if (formMaterial != null)
        {
            staticValue = switch (property)
            {
                case TrackId.MATERIAL_PROP_SMOOTHNESS -> formMaterial.smoothness.get();
                case TrackId.MATERIAL_PROP_METALLIC -> formMaterial.metallic.get();
                case TrackId.MATERIAL_PROP_SSS -> formMaterial.sss.get();
                case TrackId.MATERIAL_PROP_PIXEL_EMISSION -> formMaterial.pixelEmission.get();
                case TrackId.MATERIAL_PROP_RELIEF -> formMaterial.relief.get();
                default -> 0F;
            };
        }

        return FormMaterialLevels.pbrSlider(form, material, property, staticValue);
    }
}
