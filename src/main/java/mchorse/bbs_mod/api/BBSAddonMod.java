package mchorse.bbs_mod.api;

/**
 * An addon event subscribers container.
 *
 * <p>On Fabric, {@code bbs-addon} and {@code bbs-client-addon} metadata entrypoints select the
 * common and client subscribers. On NeoForge, register the subscriber explicitly with
 * {@code EventBus.INSTANCE.register(subscriber)} from the addon's mod constructor or bootstrap,
 * before BBS common setup. Register client subscribers from a client-only bootstrap so a
 * dedicated server never loads classes mentioning client events.</p>
 *
 * <p>FSR API2's {@code context.events().register(subscriber)} also reaches this channel, but its
 * registration callback runs after the early source, keyframe, form and clip registry events.
 * An addon subscribing to those events must use the earlier explicit registration above.</p>
 *
 * <p>This is an empty marker on purpose: which events an addon cares about is said by the
 * {@link Subscribe} annotation on its methods and by the type of their single parameter, not by
 * an interface. {@code mchorse.bbs_mod.events.BBSAddonMod}, the v1 marker, is a different
 * interface and stays in place — see {@link EventBus} for how the two are kept apart.</p>
 */
public interface BBSAddonMod
{}
