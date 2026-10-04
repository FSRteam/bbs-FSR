package mchorse.bbs_mod.api;

/**
 * An addon event subscribers container.
 *
 * <p>In {@code fabric.mod.json} — or, on FSR, the equivalent loader entrypoint lists — there are
 * two entrypoints for it. {@code bbs-addon} is read on both sides, at the very top of BBS's own
 * initialization. {@code bbs-client-addon} is read on the client only, before BBS posts any of
 * its client-side events — the events declared in the client source set can only be subscribed
 * to from there, since a class mentioning them can't be loaded on a dedicated server.</p>
 *
 * <p>This is an empty marker on purpose: which events an addon cares about is said by the
 * {@link Subscribe} annotation on its methods and by the type of their single parameter, not by
 * an interface. {@code mchorse.bbs_mod.events.BBSAddonMod}, the v1 marker, is a different
 * interface and stays in place — see {@link EventBus} for how the two are kept apart.</p>
 */
public interface BBSAddonMod
{}
