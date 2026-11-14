package mchorse.bbs_mod.film.replays.tracks;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormMaterial;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.HashMap;
import java.util.Map;

/** Material addresses retain FS 2.6's dotted names and nested stable form paths. */
public final class MaterialTracks
{
    public static final String PREFIX = "materials.";
    private static final FormMaterial NEUTRAL = new FormMaterial("");

    public record Address(String formPath, String material, String property)
    {
        public String key()
        {
            return TrackId.materialProp(this.formPath, this.material, this.property).toKey();
        }
    }

    private MaterialTracks() {}

    public static Address parse(String key)
    {
        TrackId track = TrackId.parse(key, TrackKind.MATERIAL_PROP);
        return track == null ? null : new Address(track.formPath(), track.material(), track.property());
    }

    public static IKeyframeFactory factory(Address address)
    {
        return switch (address.property())
        {
            case TrackId.MATERIAL_PROP_VISIBLE -> KeyframeFactories.BOOLEAN;
            case TrackId.MATERIAL_PROP_COLOR, TrackId.MATERIAL_PROP_OVERLAY -> KeyframeFactories.COLOR;
            case TrackId.MATERIAL_PROP_CULLING -> KeyframeFactories.INTEGER;
            default -> KeyframeFactories.FLOAT;
        };
    }

    public static Object seed(ModelForm form, Address address)
    {
        FormMaterial material = form.materials.getMaterial(address.material());
        if (material == null) material = NEUTRAL;
        BaseValueBasic<?> value = (BaseValueBasic<?>) material.get(address.property());
        return value.get();
    }

    public static ModelForm target(Form root, Address address)
    {
        Form form = FormUtils.getForm(root, address.formPath());
        return form instanceof ModelForm modelForm ? modelForm : null;
    }

    public static void override(ModelForm form, Address address, Object value)
    {
        String material = address.material();

        switch (address.property())
        {
            case TrackId.MATERIAL_PROP_VISIBLE -> put(form.materialVisibilityOverrides, material, (Boolean) value);
            case TrackId.MATERIAL_PROP_COLOR -> put(form.materialColorOverrides, material, (Color) value);
            case TrackId.MATERIAL_PROP_OVERLAY -> put(form.materialOverlayOverrides, material, (Color) value);
            case TrackId.MATERIAL_PROP_LIGHTING -> put(form.materialLightingOverrides, material, value == null ? null : ((Number) value).floatValue());
            case TrackId.MATERIAL_PROP_CULLING -> put(form.materialCullingOverrides, material, value == null ? null : ((Number) value).intValue());
            default ->
            {
                if (value != null)
                {
                    form.materialPbrOverrides.computeIfAbsent(material, (key) -> new HashMap<>())
                        .put(address.property(), ((Number) value).floatValue());
                }
                else
                {
                    Map<String, Float> sliders = form.materialPbrOverrides.get(material);
                    if (sliders != null)
                    {
                        sliders.remove(address.property());
                        if (sliders.isEmpty()) form.materialPbrOverrides.remove(material);
                    }
                }
            }
        }
    }

    private static <T> void put(Map<String, T> values, String material, T value)
    {
        if (value == null) values.remove(material);
        else values.put(material, value);
    }
}
