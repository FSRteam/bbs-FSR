package bbssmokefixture.v1;

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
 * Client-side entrypoint for the 1.0 generation. Registers the three
 * capabilities a bare JavaExec test cannot exercise for real
 * (key_mappings/entity_renderer/block_entity_renderer, see
 * {@code PluginStructuralCapabilitiesE2ETest}'s class javadoc) plus the
 * client-side form renderer, so a human can verify all of them in a real
 * NeoForge client.
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

        /* No client tick event is exposed to plugins (the SPI deliberately
         * carries no NeoForge bus type), so this fixture polls its own
         * mapping off a small daemon thread. context.own(...) guarantees the
         * thread is stopped on unload/override teardown, matching the
         * "no residual after teardown" contract every other structural face
         * follows. */
        context.own(new KeyPollWorker(mapping, "§a[bbssmokefixture 1.0] keymapping fired"));

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
     * chat message back on the client thread so a tester has an unmistakable,
     * eyeball-visible signal that the mapping actually fired.
     */
    private static final class KeyPollWorker implements AutoCloseable
    {
        private final Thread thread;
        private volatile boolean running = true;

        KeyPollWorker(KeyMapping mapping, String message)
        {
            this.thread = new Thread(() -> this.loop(mapping, message), "bbssmokefixture-v1-keypoll");
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
