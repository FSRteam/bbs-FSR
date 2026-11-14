package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.BlockFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.FormOverlay;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import mchorse.bbs_mod.forms.structure.BakedStructure;
import mchorse.bbs_mod.forms.structure.StructureRenderWorld;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.joml.Vector3f;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;

/** Form appearance follows saved values through tracks, deferred captures and picking. */
public final class FormAppearanceContractTest
{
    public static void runAll()
    {
        try
        {
            Field registry = BBSMod.class.getDeclaredField("forms");
            registry.setAccessible(true);
            Object previous = registry.get(null);
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("model"), ModelForm.class, null);
            registry.set(null, architect);

            try
            {
                overlayPersistenceAndPlayback();
            }
            finally
            {
                registry.set(null, previous);
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install appearance form registry", exception);
        }

        overlayCompositionAndCapture();
        pickingReleasesStates();
        packedTint();
        blockOverlayRouting();
        structureOverlayReplay();
    }

    private static void overlayPersistenceAndPlayback()
    {
        ModelForm form = new ModelForm();
        check(form.pickable.get() && form.overlayColor.get().a == 0F, "Old forms must default to selectable and neutral");
        form.pickable.set(false);
        form.overlayColor.set(new Color(1F, 0.25F, 0.5F, 0.75F));
        ModelForm restored = new ModelForm();
        restored.fromData(form.toData());
        check(!restored.pickable.get() && restored.overlayColor.get().equals(form.overlayColor.get()),
            "Saving a form lost selectable or color_overlay");

        FormProperties properties = new FormProperties("properties");
        KeyframeChannel<Color> channel = properties.getOrCreate(form, "color_overlay");
        Color animated = new Color(0F, 0.5F, 1F, 0.8F);
        channel.insert(4F, animated);
        FormProperties reloaded = new FormProperties("properties");
        reloaded.fromData(properties.toData());
        reloaded.applyProperties(restored, 4F);
        check(restored.overlayColor.get().equals(animated), "Reloaded color_overlay track did not reach the form");
    }

    private static void overlayCompositionAndCapture()
    {
        ModelForm form = new ModelForm();
        form.overlayColor.set(new Color(1F, 0F, 0F, 0.5F));
        form.materials.getOrCreate("skin").overlayColor.set(new Color(1F, 1F, 0F, 1F));
        form.materialOverlayOverrides.put("skin", new Color(0F, 1F, 0F, 0.5F));
        ModelGroup bone = new ModelGroup("hand");
        bone.overlay.set(0F, 0F, 1F, 0.5F);
        Color composed = FormOverlay.combine(form, "skin", bone);
        check(Math.abs(composed.a - 0.875F) < 1E-6F
            && Math.abs(composed.r - 1F / 7F) < 1E-6F
            && Math.abs(composed.g - 2F / 7F) < 1E-6F
            && Math.abs(composed.b - 4F / 7F) < 1E-6F,
            "Overlay composition lost form/material/bone order or animated material precedence");

        Color previous = FormOverlay.setActive(composed);

        try
        {
            CapturedOverlay command = new CapturedOverlay();
            FormOverlay.combine(new ModelForm(), "skin", null);
            check(Math.abs(command.captured().a - 0.875F) < 1E-6F,
                "Deferred overlay aliases the shared composition scratch");
        }
        finally
        {
            FormOverlay.restoreActive(previous);
        }
    }

    private static void pickingReleasesStates()
    {
        int[] stateCalls = {0, 0};
        Form form = new Form()
        {
            @Override
            public void applyStates(float transition)
            {
                stateCalls[0]++;
            }

            @Override
            public void unapplyStates()
            {
                stateCalls[1]++;
            }
        };
        form.pickable.set(false);
        FormRenderer<Form> renderer = new FormRenderer<>(form)
        {
            @Override
            protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
            {}

            @Override
            protected void render3D(FormRenderingContext context)
            {
                throw new AssertionError("An unselectable form reached the picking draw");
            }
        };
        FormRenderingContext context = new FormRenderingContext().stencilMap(new StencilMap());
        renderer.render(context);
        check(stateCalls[0] == 1 && stateCalls[1] == 1 && context.stencilMap.indexMap.isEmpty(),
            "Skipping an unselectable form leaked animation state or a picking entry");
    }

    private static void packedTint()
    {
        Color previous = RecolorVertexConsumer.newColor;

        try
        {
            RecolorVertexConsumer.newColor = null;
            check(RecolorVertexConsumer.tintPackedABGR(0x80402010) == 0x80402010, "An absent tint changed packed vertices");
            RecolorVertexConsumer.newColor = new Color(1F, 0.5F, 0.25F, 0.5F);
            check(RecolorVertexConsumer.tintPackedABGR(0x80402010) == 0x40101010,
                "Sodium/Iris packed tint swapped red and blue or lost alpha");
        }
        finally
        {
            RecolorVertexConsumer.newColor = previous;
        }
    }

    private static void blockOverlayRouting()
    {
        try
        {
            RenderType[] requested = {null};
            MultiBufferSource sink = (layer) ->
            {
                requested[0] = layer;
                return null;
            };
            Method factory = BlockFormRenderer.class.getDeclaredMethod("overlayProvider", MultiBufferSource.class);
            factory.setAccessible(true);
            MultiBufferSource provider = (MultiBufferSource) factory.invoke(null, sink);
            provider.getBuffer(Sheets.translucentCullBlockSheet());
            check(requested[0] == BlockFormRenderer.OVERLAY_TRANSLUCENT_LAYER,
                "Glass/ice block overlays still use a shader without the overlay sampler");
            provider.getBuffer(Sheets.translucentItemSheet());
            check(requested[0] == BlockFormRenderer.OVERLAY_TRANSLUCENT_LAYER,
                "Translucent item block overlays still use a shader without the overlay sampler");
            provider.getBuffer(Sheets.cutoutBlockSheet());
            check(requested[0] == Sheets.cutoutBlockSheet(), "Overlay routing changed an already compatible block layer");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not exercise translucent block overlay routing", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void structureOverlayReplay()
    {
        try
        {
            Constructor<BakedStructure> factory = BakedStructure.class.getDeclaredConstructor(StructureRenderWorld.class);
            factory.setAccessible(true);
            BakedStructure structure = factory.newInstance((Object) null);
            ByteBuffer vertex = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
            vertex.putFloat(0, 1F).putFloat(4, 2F).putFloat(8, 3F);
            vertex.put(12, (byte) 255).put(13, (byte) 255).put(14, (byte) 255).put(15, (byte) 128);
            vertex.put(29, (byte) 127);
            Class<?> layerClass = Class.forName(BakedStructure.class.getName() + "$BakedLayer");
            Constructor<?> layerFactory = layerClass.getDeclaredConstructor(RenderType.class, ByteBuffer.class, int.class);
            layerFactory.setAccessible(true);
            Field layers = BakedStructure.class.getDeclaredField("layers");
            layers.setAccessible(true);
            ((List<Object>) layers.get(structure)).add(layerFactory.newInstance(RenderType.translucent(), vertex, 1));

            int[] calls = {0};
            int[] alpha = {0};
            int[] overlayUv = {-1, -1};
            Vector3f position = new Vector3f();
            VertexConsumer capture = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[] {VertexConsumer.class}, (proxy, method, arguments) ->
                {
                    switch (method.getName())
                    {
                        case "addVertex" ->
                        {
                            calls[0]++;
                            position.set((Float) arguments[0], (Float) arguments[1], (Float) arguments[2]);
                        }
                        case "setColor" -> alpha[0] = (Integer) arguments[3];
                        case "setUv1" ->
                        {
                            overlayUv[0] = (Integer) arguments[0];
                            overlayUv[1] = (Integer) arguments[1];
                        }
                    }
                    return proxy;
                });
            RenderType[] requested = {null};
            CustomVertexConsumerProvider consumers = new CustomVertexConsumerProvider(null, new LinkedHashMap<>())
            {
                @Override
                public VertexConsumer getBuffer(RenderType layer)
                {
                    requested[0] = layer;
                    return capture;
                }
            };
            PoseStack matrices = new PoseStack();
            matrices.translate(2F, 3F, 4F);
            structure.renderOverlay(matrices.last(), consumers, 0, 0F);
            check(calls[0] == 0 && requested[0] == null, "A neutral structure overlay emitted geometry");
            structure.renderOverlay(matrices.last(), consumers, 0, 0.5F);
            check(requested[0] == BakedStructure.OVERLAY_LAYER && calls[0] == 1 && alpha[0] == 63
                && position.equals(new Vector3f(3F, 5F, 7F)),
                "The terrain overlay did not preserve placement, source transparency and overlay strength");
            check(overlayUv[0] == 0 && overlayUv[1] == 10,
                "Structure vertices did not unpack vanilla's packed overlay coordinates");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not exercise baked structure overlay replay", exception);
        }
    }

    private static final class CapturedOverlay extends FormTranslucentQueue.DrawCommand
    {
        private CapturedOverlay()
        {
            super(new Vector3f(), false, false);
        }

        private Color captured()
        {
            return this.colorOverlay;
        }

        @Override
        public void draw()
        {}
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
