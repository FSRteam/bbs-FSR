package mchorse.bbs_mod.ui.film;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.misc.ImageOverlay;
import mchorse.bbs_mod.camera.data.Placement;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.List;

public class UIImageRenderer
{
    /**
     * The virtual frame's width in units: always {@link Placement#HEIGHT} tall,
     * as wide as the frame's aspect ratio makes it.
     */
    public static float getUnitWidth()
    {
        RenderTarget fb = Minecraft.getInstance().getMainRenderTarget();

        return fb.width * Placement.HEIGHT / fb.height;
    }

    public static void renderImages(PoseStack stack, Batcher2D batcher, List<ImageOverlay> images)
    {
        if (images.isEmpty())
        {
            return;
        }

        float width = getUnitWidth();
        float height = Placement.HEIGHT;

        Matrix4f cache = new Matrix4f(RenderSystem.getProjectionMatrix());

        RenderSystem.setProjectionMatrix(new Matrix4f().ortho(0, width, height, 0, -100, 100), VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        for (ImageOverlay image : images)
        {
            float alpha = Colors.getA(image.color);

            if (alpha <= 0)
            {
                continue;
            }

            if (image.texture == null || !BBSModClient.getTextures().has(image.texture))
            {
                continue;
            }

            Texture texture = BBSModClient.getTextures().getTexture(image.texture);

            if (texture == BBSModClient.getTextures().getError())
            {
                continue;
            }

            texture.bind();
            texture.setFilter(image.smooth ? GL11.GL_LINEAR : GL11.GL_NEAREST);
            texture.setWrap(GL13.GL_CLAMP_TO_EDGE);

            Placement placement = image.placement;
            float w;
            float h;
            float x;
            float y;
            float anchorX;
            float anchorY;

            if (image.fullscreen)
            {
                w = width;
                h = height;
                x = width / 2F;
                y = height / 2F;
                anchorX = 0.5F;
                anchorY = 0.5F;
            }
            else
            {
                w = texture.width * placement.scale;
                h = texture.height * placement.scale;
                x = width * placement.windowX + placement.offsetX;
                y = height * placement.windowY + placement.offsetY;
                anchorX = placement.anchorX;
                anchorY = placement.anchorY;
            }

            Transform transform = new Transform();

            transform.lerp(image.transform, 1F - image.factor);

            stack.pushPose();
            stack.translate(x, y, 0);
            MatrixStackUtils.applyTransform(stack, transform);

            batcher.texturedBox(texture, image.color, -w * anchorX, -h * anchorY, w, h, 0, 0, texture.width, texture.height, texture.width, texture.height);

            stack.popPose();
        }

        RenderSystem.setProjectionMatrix(cache, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.enableCull();
    }
}
