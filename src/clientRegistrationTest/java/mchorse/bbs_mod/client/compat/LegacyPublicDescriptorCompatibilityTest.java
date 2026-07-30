package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.ActionPlayer;
import mchorse.bbs_mod.actions.PlayerType;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.addon.BBSAddonCollector;
import mchorse.bbs_mod.addon.BBSAddonRegisterEvent;
import mchorse.bbs_mod.addon.v2.BBSAddonManager;
import mchorse.bbs_mod.api.BBSApi;
import mchorse.bbs_mod.api.BBSApiVersion;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.BBSClientApi;
import mchorse.bbs_mod.api.network.BBSAddonClientNetworkReceiver;
import mchorse.bbs_mod.api.network.BBSAddonServerNetworkReceiver;
import mchorse.bbs_mod.client.compat.ClientApiCompat;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.network.IBufferReceiver;
import mchorse.bbs_mod.network.PacketCrusher;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmRecorder;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.UICanvas;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.utils.VideoExportUtils;
import mchorse.bbs_mod.utils.VideoRecorder;
import mchorse.bbs_mod.data.types.BaseType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/** Exact erased descriptors retained for legacy Addon v1 and API 2.0 jars. */
public final class LegacyPublicDescriptorCompatibilityTest
{
    private LegacyPublicDescriptorCompatibilityTest()
    {}

    public static void main(String[] args)
    {
        check("2.0".equals(BBSApiVersion.CURRENT), "Addon/API version changed from 2.0");
        addonEntryDescriptors();
        networkDescriptors();
        actionDescriptors();
        clientFacadeDescriptors();
        clientRuntimeDescriptors();

        System.out.println("LegacyPublicDescriptorCompatibilityTest: all tests passed");
    }

    private static void addonEntryDescriptors()
    {
        requirePublicStatic(BBSApi.class, "registerAddon", void.class, BBSAddonDescriptor.class, Supplier.class);
        requirePublicStatic(BBSApi.class, "registerAddon", void.class, Supplier.class);
        requirePublicStatic(BBSMod.class, "registerAddon", void.class, BBSAddonDescriptor.class, Supplier.class);
        requirePublicStatic(BBSMod.class, "registerAddon", void.class, Supplier.class);
        requirePublicStatic(BBSMod.class, "registerAddon", void.class, String.class, Supplier.class);
        requirePublicVirtual(BBSMod.class, "onInitialize", void.class);

        requirePublicConstructor(BBSAddonCollector.class);
        requirePublic(BBSAddonCollector.class, "register", boolean.class, String.class, BBSAddonMod.class);
        requirePublic(BBSAddonCollector.class, "registerExternal", boolean.class, String.class, BBSAddonMod.class);
        requirePublicConstructor(BBSAddonRegisterEvent.class, BBSAddonCollector.class);
        requirePublic(BBSAddonRegisterEvent.class, "register", void.class, String.class, BBSAddonMod.class);
        requirePublic(BBSAddonRegisterEvent.class, "register", void.class, String.class, Supplier.class);
        requirePublicConstructor(BBSAddonManager.class, Supplier.class);

        requirePublic(BBSAddon.class, "descriptor", BBSAddonDescriptor.class);
    }

    private static void networkDescriptors()
    {
        requireSam(NetworkCompat.ServerReceiver.class, "receive", void.class,
            MinecraftServer.class, ServerPlayer.class, FriendlyByteBuf.class);
        requireSam(NetworkCompatClient.ClientReceiver.class, "receive", void.class, FriendlyByteBuf.class);
        requireSam(BBSAddonClientNetworkReceiver.class, "receive", void.class,
            ResourceLocation.class, FriendlyByteBuf.class);
        requireSam(BBSAddonServerNetworkReceiver.class, "receive", void.class,
            MinecraftServer.class, ServerPlayer.class, ResourceLocation.class, FriendlyByteBuf.class);
        requirePublicStatic(NetworkCompatClient.class, "registerClientReceiver", void.class,
            ResourceLocation.class, NetworkCompatClient.ClientReceiver.class);
        requirePublicStatic(NetworkCompatClient.class, "dispatchClientPayload", void.class,
            ResourceLocation.class, FriendlyByteBuf.class);

        requirePublicConstructor(PacketCrusher.class);
        requirePublic(PacketCrusher.class, "receive", void.class, FriendlyByteBuf.class, IBufferReceiver.class);
    }

    private static void actionDescriptors()
    {
        requirePublicConstructor(ActionPlayer.class,
            ServerPlayer.class, ServerLevel.class, Film.class,
            int.class, int.class, int.class, PlayerType.class);
        requirePublic(ActionPlayer.class, "updateReplayEntities", void.class);
        requirePublic(ActionPlayer.class, "stop", void.class);
        requirePublic(ActionManager.class, "startRecording", void.class,
            Film.class, ServerPlayer.class, int.class, int.class, int.class);
        requireProtected(ActionClip.class, "applyPositionRotation", void.class,
            SuperFakePlayer.class, Replay.class, int.class);
        requireResolvedProtected(GunProjectileEntity.class, "getPermissionLevel", int.class);

        check(PlayerType.NORMAL.ordinal() == 0, "PlayerType.NORMAL ordinal changed");
        check(PlayerType.FILM_EDITOR.ordinal() == 1, "PlayerType.FILM_EDITOR ordinal changed");
        check(PlayerType.RECORDING.ordinal() == 2, "PlayerType.RECORDING ordinal changed");
        check(IKControl.DEFAULT_SOFTNESS == 0.05F, "legacy inlined IK softness constant changed");
        check(IKControl.HARD_REACH_DEFAULT_SOFTNESS == 0F, "core hard-reach IK default changed");
        check(new IKControl().softness == IKControl.HARD_REACH_DEFAULT_SOFTNESS,
            "IKControl runtime default uses the legacy addon constant");
    }

    private static void clientFacadeDescriptors()
    {
        requireSam(BBSClientApi.HudRenderHandler.class, "render", void.class, GuiGraphics.class, float.class);
        requireSam(BBSClientApi.WorldRenderHandler.class, "render", void.class, BBSWorldRenderContext.class);
        requireSam(ClientApiCompat.DisconnectHandler.class, "onDisconnect", void.class, Minecraft.class);
        requireSam(ClientApiCompat.HudRenderHandler.class, "render", void.class, GuiGraphics.class, float.class);
        requireSam(ClientApiCompat.WorldRenderHandler.class, "render", void.class, BBSWorldRenderContext.class);
        requirePublicStatic(BBSClientApi.class, "registerKeyBinding", KeyMapping.class, KeyMapping.class);
        requirePublicStatic(BBSClientApi.class, "registerEntityRenderer", void.class,
            EntityType.class, EntityRendererProvider.class);
        requirePublicStatic(BBSClientApi.class, "registerBlockEntityRenderer", void.class,
            BlockEntityType.class, BlockEntityRendererProvider.class);
        requirePublicStatic(ClientApiCompat.class, "registerKeyBinding", KeyMapping.class, KeyMapping.class);
        requirePublicStatic(ClientApiCompat.class, "registerEntityRenderer", void.class,
            EntityType.class, EntityRendererProvider.class);
        requirePublicStatic(ClientApiCompat.class, "registerBlockEntityRenderer", void.class,
            BlockEntityType.class, BlockEntityRendererProvider.class);
    }

    private static void clientRuntimeDescriptors()
    {
        requirePublic(UIFilmPanel.class, "receiveActions", void.class,
            String.class, int.class, int.class, BaseType.class);
        requirePublic(UIFilmRecorder.class, "startRecording", void.class, int.class, Texture.class);
        requirePublic(UIFilmRecorder.class, "startRecording", void.class,
            int.class, int.class, int.class, int.class);
        requirePublic(VideoRecorder.class, "startRecording", void.class,
            String.class, File.class, int.class, int.class, int.class);
        requirePublic(VideoRecorder.class, "stopRecording", void.class);
        requirePublicStatic(VideoExportUtils.class, "deleteTemporaryFile", void.class, File.class);
        requirePublic(Scroll.class, "mouseReleased", void.class, UIContext.class);
        requirePublic(Scroll.class, "mouseReleased", void.class, int.class, int.class);
        requireProtected(UICanvas.class, "zoom", void.class, UIContext.class, int.class);
    }

    private static void requireSam(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
    {
        Method expected = requirePublic(owner, name, returnType, parameters);
        long abstractMethods = java.util.Arrays.stream(owner.getMethods())
            .filter(method -> Modifier.isAbstract(method.getModifiers()))
            .filter(method -> !method.isDefault())
            .count();

        check(Modifier.isAbstract(expected.getModifiers()), owner.getName() + " SAM method is not abstract");
        check(abstractMethods == 1L, owner.getName() + " is no longer a single-abstract-method interface");
    }

    private static Method requirePublicStatic(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
    {
        Method method = requirePublic(owner, name, returnType, parameters);

        check(Modifier.isStatic(method.getModifiers()), descriptor(owner, name, parameters) + " is no longer static");

        return method;
    }

    private static Method requirePublicVirtual(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
    {
        Method method = requirePublic(owner, name, returnType, parameters);

        check(!Modifier.isStatic(method.getModifiers()), descriptor(owner, name, parameters) + " is no longer virtual");

        return method;
    }

    private static Method requirePublic(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
    {
        try
        {
            Method method = owner.getMethod(name, parameters);

            check(method.getReturnType() == returnType,
                descriptor(owner, name, parameters) + " return type changed to " + method.getReturnType().getName());
            check(Modifier.isPublic(method.getModifiers()), descriptor(owner, name, parameters) + " is no longer public");

            return method;
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(descriptor(owner, name, parameters) + " is missing", e);
        }
    }

    private static Method requireProtected(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
    {
        try
        {
            Method method = owner.getDeclaredMethod(name, parameters);

            check(method.getReturnType() == returnType,
                descriptor(owner, name, parameters) + " return type changed to " + method.getReturnType().getName());
            check(Modifier.isProtected(method.getModifiers()), descriptor(owner, name, parameters) + " is no longer protected");

            return method;
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(descriptor(owner, name, parameters) + " is missing", e);
        }
    }

    private static Method requireResolvedProtected(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
    {
        Class<?> current = owner;

        while (current != null)
        {
            try
            {
                Method method = current.getDeclaredMethod(name, parameters);

                check(method.getReturnType() == returnType,
                    descriptor(owner, name, parameters) + " inherited return type changed");
                check(Modifier.isProtected(method.getModifiers()),
                    descriptor(owner, name, parameters) + " inherited target is no longer protected");

                return method;
            }
            catch (NoSuchMethodException ignored)
            {
                current = current.getSuperclass();
            }
        }

        throw new AssertionError(descriptor(owner, name, parameters) + " cannot resolve through the superclass chain");
    }

    private static void requirePublicConstructor(Class<?> owner, Class<?>... parameters)
    {
        try
        {
            Constructor<?> constructor = owner.getDeclaredConstructor(parameters);

            check(Modifier.isPublic(constructor.getModifiers()), owner.getName() + " constructor is no longer public");
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(owner.getName() + " constructor is missing", e);
        }
    }

    private static String descriptor(Class<?> owner, String name, Class<?>... parameters)
    {
        return owner.getName() + "." + name + java.util.Arrays.toString(parameters);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
