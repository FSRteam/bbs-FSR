package mchorse.bbs_mod.forms;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.api.client.events.FormRenderEvents;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.VideoForm;
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
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.forms.TrailForm;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.forms.renderers.AnchorFormRenderer;
import mchorse.bbs_mod.forms.renderers.BillboardFormRenderer;
import mchorse.bbs_mod.forms.renderers.VideoFormRenderer;
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
import mchorse.bbs_mod.forms.renderers.StructureFormRenderer;
import mchorse.bbs_mod.forms.renderers.TrailFormRenderer;
import mchorse.bbs_mod.forms.renderers.VanillaParticleFormRenderer;
import mchorse.bbs_mod.forms.structure.BakedStructure;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.Util;
import org.slf4j.Logger;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

public class FormUtilsClient
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> reportedRenderFailures = new HashSet<>();

    private static Map<Class, IFormRendererFactory> map = new HashMap<>();
    private static CustomVertexConsumerProvider customVertexConsumerProvider;
    private static Deque<Form> currentForm = new ArrayDeque<>();

    private static CustomVertexConsumerProvider createProvider()
    {
        SequencedMap<RenderType, ByteBufferBuilder> sortedMap = Util.make(new LinkedHashMap<>(), map -> {
            assignBufferBuilder(map, Sheets.solidBlockSheet());
            assignBufferBuilder(map, Sheets.cutoutBlockSheet());
            /* Keyed terrain block layers so forms that render real blocks (the structure form) get
             * them keyed — drawn together at draw() with correct depth — instead of falling through
             * to the shared fallback buffer, which flushes on every layer switch and loses depth
             * between opaque layers (e.g. cutout leaves drawing over solid blocks). Kept before the
             * translucent layer so opaque draws first. */
            assignBufferBuilder(map, RenderType.solid());
            assignBufferBuilder(map, RenderType.cutout());
            assignBufferBuilder(map, RenderType.cutoutMipped());
            assignBufferBuilder(map, Sheets.bannerSheet());
            assignBufferBuilder(map, Sheets.translucentCullBlockSheet());
            /* Keep the block overlay beside the translucent layer it replaces. */
            assignBufferBuilder(map, BlockFormRenderer.OVERLAY_TRANSLUCENT_LAYER);
            assignBufferBuilder(map, Sheets.shieldSheet());
            assignBufferBuilder(map, Sheets.bedSheet());
            assignBufferBuilder(map, Sheets.shulkerBoxSheet());
            assignBufferBuilder(map, Sheets.signSheet());
            assignBufferBuilder(map, Sheets.hangingSignSheet());
            map.put(Sheets.chestSheet(), new ByteBufferBuilder(786432));
            assignBufferBuilder(map, RenderType.armorEntityGlint());
            assignBufferBuilder(map, RenderType.glint());
            assignBufferBuilder(map, RenderType.glintTranslucent());
            assignBufferBuilder(map, RenderType.entityGlint());
            assignBufferBuilder(map, RenderType.entityGlintDirect());
            assignBufferBuilder(map, RenderType.waterMask());
            ModelBakery.DESTROY_TYPES.forEach(renderLayer -> assignBufferBuilder(map, renderLayer));
            /* A structure's second color pass must follow all its base geometry. */
            assignBufferBuilder(map, BakedStructure.OVERLAY_LAYER);
        });

        return new CustomVertexConsumerProvider(new ByteBufferBuilder(1536), sortedMap);
    }

    /** Register built-in renderers during client setup, before addon registration. */
    public static void setup()
    {
        register(BillboardForm.class, (form) -> new BillboardFormRenderer<>(form));
        register(VideoForm.class, VideoFormRenderer::new);
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
        register(StructureForm.class, StructureFormRenderer::new);
        register(SoundSphereForm.class, SoundSphereFormRenderer::new);
        register(SoundConeForm.class, SoundConeFormRenderer::new);
    }

    private static void assignBufferBuilder(Map<RenderType, ByteBufferBuilder> builderStorage, RenderType layer)
    {
        builderStorage.put(layer, new ByteBufferBuilder(layer.bufferSize()));
    }

    public static CustomVertexConsumerProvider getProvider()
    {
        /* Render layers and their buffers are safe to allocate once the game is ready. */
        if (customVertexConsumerProvider == null)
        {
            customVertexConsumerProvider = createProvider();
        }

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

        IFormRendererFactory factory = findFactory(form.getClass());

        if (factory != null)
        {
            FormRenderer formRenderer = factory.create(form);

            form.setRenderer(formRenderer);

            return formRenderer;
        }

        return null;
    }

    /**
     * The renderer registered for a form's own class, or for the nearest class it extends.
     *
     * <p>Without the walk up, extending one of BBS's forms bought nothing: the subclass inherited
     * the shape and the data and then drew as nothing at all, until it registered a renderer that
     * was usually a copy of its parent's.</p>
     *
     * <p>An exact registration always wins, so BBS's own forms resolve exactly as they did before
     * this walk existed; the walk only decides what an unregistered subclass falls back to.</p>
     */
    private static IFormRendererFactory findFactory(Class clazz)
    {
        while (clazz != null && clazz != Object.class)
        {
            IFormRendererFactory factory = map.get(clazz);

            if (factory != null)
            {
                return factory;
            }

            clazz = clazz.getSuperclass();
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
        renderUI(form, context, x1, y1, x2, y2, false);
    }

    public static void renderUI(Form form, UIContext context, int x1, int y1, int x2, int y2, boolean selected)
    {
        BBSProfiler.count(BBSProfiler.Section.UI_PREVIEW_RENDERS);
        BBSProfiler.begin(BBSProfiler.Timer.UI_PREVIEWS);

        try
        {
            FormRenderer renderer = getRenderer(form);

            if (renderer != null)
            {
                renderer.renderUI(context, x1, y1, x2, y2, selected);
            }
        }
        finally
        {
            BBSProfiler.end(BBSProfiler.Timer.UI_PREVIEWS);
        }
    }

    public static void renderPreview(Form form, UIContext context, int x1, int y1, int x2, int y2)
    {
        FormRenderer renderer = getRenderer(form);

        if (renderer != null)
        {
            renderer.renderPreview(context, x1, y1, x2, y2);
        }
    }

    public static void render(Form form, FormRenderingContext context)
    {
        /* A form set to render last skips its turn here and draws when the pass closes its
         * scope — through this very method, so the events fire once, at the real draw. */
        if (FormRenderLast.postpone(form, context))
        {
            return;
        }

        FormRenderer renderer = getRenderer(form);

        if (renderer != null)
        {
            currentForm.push(form);

            try
            {
                FormRenderEvents.BEFORE.invoker().onFormRender(form, context);
                renderer.render(context);
            }
            catch (Exception e)
            {
                reportRenderFailure(form, e);
            }
            finally
            {
                try
                {
                    FormRenderEvents.AFTER.invoker().onFormRender(form, context);
                }
                finally
                {
                    currentForm.pop();
                }
            }
        }
    }

    /** Report each distinct render failure once instead of logging it every frame. */
    private static void reportRenderFailure(Form form, Exception exception)
    {
        StackTraceElement[] trace = exception.getStackTrace();
        String key = form.getClass().getName() + "|" + exception.getClass().getName()
            + "|" + (trace.length == 0 ? "" : trace[0].toString());

        if (reportedRenderFailures.add(key))
        {
            LOGGER.error("[BBS form] {} failed to render - further repeats of this failure are silenced.",
                form.getClass().getSimpleName(), exception);
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

        return hierarchy.getLabels().getOrDefault(resolved, bone);
    }

    public static interface IFormRendererFactory <T extends Form>
    {
        public FormRenderer<T> create(T form);
    }
}
