package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.forms.ExtrudedForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.forms.ItemForm;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.ParticleForm;
import mchorse.bbs_mod.forms.forms.TrailForm;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.forms.renderers.AnchorFormRenderer;
import mchorse.bbs_mod.forms.renderers.BillboardFormRenderer;
import mchorse.bbs_mod.forms.renderers.BoneHierarchy;
import mchorse.bbs_mod.forms.renderers.BlockFormRenderer;
import mchorse.bbs_mod.forms.renderers.ExtrudedFormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.FramebufferFormRenderer;
import mchorse.bbs_mod.forms.renderers.sound.SoundConeFormRenderer;
import mchorse.bbs_mod.forms.renderers.sound.SoundSphereFormRenderer;
import mchorse.bbs_mod.forms.renderers.ItemFormRenderer;
import mchorse.bbs_mod.forms.renderers.LabelFormRenderer;
import mchorse.bbs_mod.forms.renderers.MobFormRenderer;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.ParticleFormRenderer;
import mchorse.bbs_mod.forms.renderers.TrailFormRenderer;
import mchorse.bbs_mod.forms.renderers.VanillaParticleFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.Util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

public class FormUtilsClient
{
    private static Map<Class, IFormRendererFactory> map = new HashMap<>();
    private static CustomVertexConsumerProvider customVertexConsumerProvider;
    private static Deque<Form> currentForm = new ArrayDeque<>();

    static
    {
        SequencedMap<RenderType, ByteBufferBuilder> sortedMap = Util.make(new LinkedHashMap<>(), map -> {
            assignBufferBuilder(map, Sheets.solidBlockSheet());
            assignBufferBuilder(map, Sheets.cutoutBlockSheet());
            assignBufferBuilder(map, Sheets.bannerSheet());
            assignBufferBuilder(map, Sheets.translucentCullBlockSheet());
            assignBufferBuilder(map, Sheets.shieldSheet());
            assignBufferBuilder(map, Sheets.bedSheet());
            assignBufferBuilder(map, Sheets.shulkerBoxSheet());
            assignBufferBuilder(map, Sheets.signSheet());
            assignBufferBuilder(map, Sheets.hangingSignSheet());
            map.put(Sheets.chestSheet(), new ByteBufferBuilder(786432));
            assignBufferBuilder(map, RenderType.glint());
            assignBufferBuilder(map, RenderType.armorEntityGlint());
            assignBufferBuilder(map, RenderType.glint());
            assignBufferBuilder(map, RenderType.glint());
            assignBufferBuilder(map, RenderType.glintTranslucent());
            assignBufferBuilder(map, RenderType.entityGlint());
            assignBufferBuilder(map, RenderType.entityGlintDirect());
            assignBufferBuilder(map, RenderType.waterMask());
            ModelBakery.DESTROY_TYPES.forEach(renderLayer -> assignBufferBuilder(map, renderLayer));
        });

        customVertexConsumerProvider = new CustomVertexConsumerProvider(new ByteBufferBuilder(1536), sortedMap);

        register(BillboardForm.class, BillboardFormRenderer::new);
        register(ExtrudedForm.class, ExtrudedFormRenderer::new);
        register(LabelForm.class, LabelFormRenderer::new);
        register(ModelForm.class, ModelFormRenderer::new);
        register(ParticleForm.class, ParticleFormRenderer::new);
        register(BlockForm.class, BlockFormRenderer::new);
        register(ItemForm.class, ItemFormRenderer::new);
        register(AnchorForm.class, AnchorFormRenderer::new);
        register(MobForm.class, MobFormRenderer::new);
        register(VanillaParticleForm.class, VanillaParticleFormRenderer::new);
        register(TrailForm.class, TrailFormRenderer::new);
        register(FramebufferForm.class, FramebufferFormRenderer::new);
        register(SoundSphereForm.class, SoundSphereFormRenderer::new);
        register(SoundConeForm.class, SoundConeFormRenderer::new);
    }

    private static void assignBufferBuilder(Map<RenderType, ByteBufferBuilder> builderStorage, RenderType layer)
    {
        builderStorage.put(layer, new ByteBufferBuilder(layer.bufferSize()));
    }

    public static CustomVertexConsumerProvider getProvider()
    {
        return customVertexConsumerProvider;
    }

    public static <T extends Form> void register(Class<T> clazz, IFormRendererFactory<T> function)
    {
        map.put(clazz, function);
    }

    public static IFormRendererFactory getRegisteredFactory(Class<? extends Form> clazz)
    {
        return map.get(clazz);
    }

    public static void unregisterPluginRenderer(Class<? extends Form> clazz, IFormRendererFactory factory)
    {
        if (map.get(clazz) == factory)
        {
            map.remove(clazz);
        }
    }

    public static Form getCurrentForm()
    {
        return currentForm.isEmpty() ? null : currentForm.peek();
    }

    public static FormRenderer getRenderer(Form form)
    {
        if (form == null)
        {
            return null;
        }

        if (form.getRenderer() instanceof FormRenderer renderer)
        {
            return renderer;
        }

        IFormRendererFactory factory = map.get(form.getClass());

        if (factory != null)
        {
            FormRenderer formRenderer = factory.create(form);

            form.setRenderer(formRenderer);

            return formRenderer;
        }

        return null;
    }

    /** Release renderer resources without creating renderers for untouched forms. */
    public static void release(Form form)
    {
        if (form == null)
        {
            return;
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            release(part.getForm());
        }

        if (form.getRenderer() instanceof FormRenderer renderer)
        {
            renderer.release();
        }
    }

    public static void renderUI(Form form, UIContext context, int x1, int y1, int x2, int y2)
    {
        FormRenderer renderer = getRenderer(form);

        if (renderer != null)
        {
            renderer.renderUI(context, x1, y1, x2, y2);
        }
    }

    public static void render(Form form, FormRenderingContext context)
    {
        FormRenderer renderer = getRenderer(form);

        if (renderer != null)
        {
            currentForm.push(form);

            try
            {
                renderer.render(context);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                currentForm.pop();
            }
        }
    }

    public static List<String> getBones(Form form)
    {
        FormRenderer renderer = getRenderer(form);

        if (renderer != null)
        {
            return renderer.getBones();
        }

        return Collections.emptyList();
    }

    public static BoneHierarchy getBoneHierarchy(Form form)
    {
        FormRenderer renderer = getRenderer(form);

        return renderer == null ? BoneHierarchy.EMPTY : renderer.getBoneHierarchy();
    }

    public static String getBoneLabel(Form form, String bone)
    {
        if (bone == null || bone.isEmpty())
        {
            return "";
        }

        BoneHierarchy hierarchy = getBoneHierarchy(form);
        String resolved = hierarchy.resolveId(bone);

        return hierarchy.getLabels(false).getOrDefault(resolved, bone);
    }

    public static interface IFormRendererFactory <T extends Form>
    {
        public FormRenderer<T> create(T form);
    }
}
