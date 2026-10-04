/**
 * BBS's addon API.
 *
 * <p>An addon is declared in {@code neoforge.mods.toml} and registers itself through
 * {@link mchorse.bbs_mod.api.BBSApi#registerAddon} with a
 * {@link mchorse.bbs_mod.api.addon.BBSAddonDescriptor} and a
 * {@link mchorse.bbs_mod.api.addon.BBSAddon} implementation. BBS then drives it through the
 * phases of {@link mchorse.bbs_mod.api.addon.BBSAddonPhase}, from discovery to unload, and
 * collects anything it reports into {@link mchorse.bbs_mod.api.BBSApi#addonDiagnostics()}.</p>
 *
 * <p><b>What is and isn't a contract.</b> This package and its sub-packages are; the rest of BBS
 * is not. A signature in here does not change without
 * {@link mchorse.bbs_mod.api.BBSApi#VERSION} changing with it. Everything outside this package
 * moves without notice — an addon reaching into it is on its own, and the breakage shows up in
 * the game rather than on the build.</p>
 *
 * <p>Unlike the Fabric original, calling {@code /bbs plugin} is not the only way in: FSR also
 * loads physical plugins from jars at runtime, which can be reloaded and unloaded. Both share the
 * same registries, and {@code api/plugin} is where that runtime lives.</p>
 *
 * <p>See {@code docs/addon-api-2.md} for the recipes.</p>
 */
package mchorse.bbs_mod.api;
