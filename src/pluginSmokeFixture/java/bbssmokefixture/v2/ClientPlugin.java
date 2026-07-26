package bbssmokefixture.v2;

import com.mojang.blaze3d.platform.InputConstants;
import mchorse.bbs_mod.api.plugin.BBSPlugin;
import mchorse.bbs_mod.api.plugin.BBSPluginContext;
import mchorse.bbs_mod.api.plugin.client.BBSPluginClientContext;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side entrypoint for the 2.0 generation. Registers the exact same
 * key mapping name and the exact same vanilla renderer targets as
 * {@code bbssmokefixture.v1.ClientPlugin} so overriding the 1.0 jar exercises
 * the light-shader-style teardown + republish path for all three
 * JVM-uncoverable capabilities, not just a side-by-side install.
 */
public final class ClientPlugin implements BBSPlugin
{
    @Override
    public void prepare(BBSPluginContext context)
    {
        BBSPluginClientContext client = context.extension(BBSPluginClientContext.class);

        if (client == null)
        {
            throw new IllegalStateException("client structural context facade was not wired");
        }

        KeyMapping mapping = new KeyMapping(
            "key.bbssmokefixture.trigger",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_9,
            "category.bbssmokefixture.main"
        );

        BBSRegistrationResult keyResult = client.keyMappings().register(mapping);
        require(keyResult, "key mapping");

        context.own(new KeyPollWorker(mapping, "§b[bbssmokefixture 2.0] keymapping fired - upgraded!"));

        BBSRegistrationResult entityRenderer = client.renderers().registerEntity(EntityType.PIG, SmokeEntityRenderer::new);
        require(entityRenderer, "entity renderer");

        BBSRegistrationResult blockEntityRenderer = client.renderers().registerBlockEntity(
            BlockEntityType.CHEST, (rendererContext) -> new SmokeBlockEntityRenderer());
        require(blockEntityRenderer, "block entity renderer");

        BBSRegistrationResult formRenderer = client.forms().registerRenderer(SmokeForm.class, SmokeFormRenderer::new);
        require(formRenderer, "form renderer");
    }

    private static void require(BBSRegistrationResult result, String what)
    {
        if (!result.accepted())
        {
            throw new IllegalStateException(what + " registration rejected: " + result);
        }
    }

    /**
     * Polls the registered key mapping off the render thread and posts a
     * chat message back on the client thread; see
     * {@code bbssmokefixture.v1.ClientPlugin.KeyPollWorker} for why a
     * self-managed thread is used instead of a host tick event.
     */
    private static final class KeyPollWorker implements AutoCloseable
    {
        private final Thread thread;
        private volatile boolean running = true;

        KeyPollWorker(KeyMapping mapping, String message)
        {
            this.thread = new Thread(() -> this.loop(mapping, message), "bbssmokefixture-v2-keypoll");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void loop(KeyMapping mapping, String message)
        {
            while (this.running)
            {
                while (mapping.consumeClick())
                {
                    Minecraft minecraft = Minecraft.getInstance();

                    if (minecraft != null)
                    {
                        minecraft.execute(() ->
                        {
                            if (minecraft.player != null)
                            {
                                minecraft.player.displayClientMessage(Component.literal(message), false);
                            }
                        });
                    }
                }

                try
                {
                    Thread.sleep(50L);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close()
        {
            this.running = false;
            this.thread.interrupt();
        }
    }
}
