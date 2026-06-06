package mchorse.bbs_mod.events;

/**
 * An addon event subscribers container.
 *
 * <p>Internal addons may register through {@code BBSAddonRegisterEvent} on BBS' mod bus.
 * External NeoForge mods should call {@code BBSMod.registerAddon(...)} during mod construction,
 * because their own mod bus does not receive events posted on BBS' mod bus. Collected addons
 * are then resolved by {@code LoaderAccess#getEntrypoints("bbs-addon", ...)}.</p>
 */
public interface BBSAddonMod
{}
