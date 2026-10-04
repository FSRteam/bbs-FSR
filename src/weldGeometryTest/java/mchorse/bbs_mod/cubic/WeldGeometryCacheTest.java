package mchorse.bbs_mod.cubic;

import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.WeldGeometryCache;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.List;

/**
 * B8·R-4: what the welded-geometry cache must never get wrong.
 *
 * <p>The cache is a pure performance feature — the picture must not move — so the only thing worth
 * asserting about it is its KEY and LENDING policy. Three failures would each be silent, and each
 * would corrupt a frame:</p>
 *
 * <ol>
 *   <li>serving an entry whose key differs from the requested one, which would draw one pass (or one
 *       pose) with another's geometry;</li>
 *   <li>handing back a buffer that the translucent queue has already borrowed this frame. That queue
 *       flushes at the END of the frame ({@code FormTranslucentQueue.release}), so rebuilding the entry
 *       mid-frame would flush whatever was baked over it — the FSR-only hazard this migration has to
 *       keep intact;</li>
 *   <li>failing to reset on {@code invalidate()}, which would keep serving geometry built from a weld
 *       config that no longer exists — or dropping the ring instead of just its bakes, which the queue
 *       may still be pointing into.</li>
 * </ol>
 *
 * <p>Plus the key's own composition: every input that reaches the bake must change the key — both the
 * draw's (the light, the picking mode, which would serve one pass a bake made for another) and the
 * bones' (the pose, which would keep serving the geometry baked for the previous pose, silently and for
 * as long as nothing else in the key moved — the worse of the two). No other test would see either.</p>
 *
 * <p>Deliberately headless: the entry's GPU buffer is allocated lazily, so none of this needs a GL
 * context or a render thread.</p>
 */
public final class WeldGeometryCacheTest
{
    /** Any stable seed; the key's own starting value (upstream's constant) is not otherwise observable. */
    private static final long SEED = 1125899906842597L;

    private WeldGeometryCacheTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("WeldGeometryCacheTest passed");
    }

    public static void runAll()
    {
        sameKeyFindsTheEntry();
        differentKeyIsNeverServed();
        invalidBakeIsNotServedEvenByItsOwnKey();
        lentEntryIsNotRebuiltInItsOwnFrame();
        lentEntryIsReusableInTheNextFrame();
        pastTheCapTheCallerFallsBack();
        invalidateForgetsBakesButKeepsTheRing();
        deleteDropsTheRing();
        everyDrawInputChangesTheKey();
        sameDrawInputsGiveTheSameKey();
        everyBoneInputChangesTheKey();
        sameBoneInputsGiveTheSameKey();
    }

    private static void sameKeyFindsTheEntry()
    {
        WeldGeometryCache cache = new WeldGeometryCache();
        WeldGeometryCache.Entry entry = bake(cache, 4242L, 0L);

        require(cache.find(4242L) == entry, "a bake must be found by its own key");

        System.out.println("[weld-cache] same key reuses the same entry");
    }

    private static void differentKeyIsNeverServed()
    {
        WeldGeometryCache cache = new WeldGeometryCache();

        bake(cache, 1L, 0L);

        require(cache.find(2L) == null,
            "a bake stored under one key must never be served for another — a different pose, pass or picking mode would draw it");

        System.out.println("[weld-cache] a different key is never served another bake");
    }

    private static void invalidBakeIsNotServedEvenByItsOwnKey()
    {
        WeldGeometryCache cache = new WeldGeometryCache();
        WeldGeometryCache.Entry entry = bake(cache, 7L, 0L);

        entry.valid = false;

        require(cache.find(7L) == null, "an invalidated entry must not be found even by the key it still carries");

        System.out.println("[weld-cache] an invalid entry is not served");
    }

    private static void lentEntryIsNotRebuiltInItsOwnFrame()
    {
        WeldGeometryCache cache = new WeldGeometryCache(2);
        WeldGeometryCache.Entry first = bake(cache, 10L, 9L);

        /* The queue borrowed this buffer; its flush is at the end of this frame. */
        first.lentEpoch = 9L;

        WeldGeometryCache.Entry second = bake(cache, 11L, 9L);

        require(second != null && second != first,
            "a lent buffer must not be handed back to be rebuilt in the same frame");

        second.lentEpoch = 9L;

        require(cache.acquire(9L) == null,
            "with every slot lent out this frame the caller must bake into its own throwaway buffer, not over a borrowed one");

        require(cache.find(10L) == first && cache.find(11L) == second,
            "the fallback must not disturb either borrowed bake");

        System.out.println("[weld-cache] a lent entry is never rebuilt in the same frame");
    }

    private static void lentEntryIsReusableInTheNextFrame()
    {
        WeldGeometryCache cache = new WeldGeometryCache(1);
        WeldGeometryCache.Entry entry = bake(cache, 3L, 3L);

        entry.lentEpoch = 3L;

        require(cache.acquire(3L) == null, "the only slot is still lent out this frame");
        require(cache.acquire(4L) == entry,
            "a buffer lent in an earlier frame must be reusable in the next one — otherwise the ring would never be reused at all");

        System.out.println("[weld-cache] a lent entry is reusable in the next frame");
    }

    private static void pastTheCapTheCallerFallsBack()
    {
        WeldGeometryCache cache = new WeldGeometryCache(16);

        require(cache.acquire(0L) != null, "an empty ring must hand out an entry");

        for (int i = 0; i < 16; i++)
        {
            WeldGeometryCache.Entry entry = cache.acquire(1L);

            require(entry != null, "the ring must grow to its cap (" + (i + 1) + " of 16)");

            entry.lentEpoch = 1L;
        }

        require(cache.size() == 16, "the ring must stop at its cap");
        require(cache.acquire(1L) == null, "past the cap the caller must fall back to an owned per-frame buffer");

        System.out.println("[weld-cache] the ring is capped at 16 and falls back past it");
    }

    private static void invalidateForgetsBakesButKeepsTheRing()
    {
        WeldGeometryCache cache = new WeldGeometryCache();
        WeldGeometryCache.Entry entry = bake(cache, 5L, 0L);

        entry.cpuGroups = Collections.singleton(new ModelGroup("bone"));

        cache.invalidate();

        require(!entry.valid, "invalidate must drop the bake");
        require(entry.cpuGroups == null, "invalidate must drop the bake's group set");
        require(cache.find(5L) == null, "a dropped bake must not be found");
        require(cache.size() == 1,
            "invalidate must KEEP the ring: the translucent queue may still hold a buffer until the end of the frame");

        System.out.println("[weld-cache] invalidate forgets the bakes and keeps the ring");
    }

    private static void deleteDropsTheRing()
    {
        WeldGeometryCache cache = new WeldGeometryCache();

        bake(cache, 6L, 0L);
        cache.delete();

        require(cache.size() == 0, "delete must drop the ring");
        require(cache.find(6L) == null, "delete must drop the bakes");
        require(cache.acquire(0L) != null, "a deleted cache must still be able to hand out an entry");

        System.out.println("[weld-cache] delete drops the ring");
    }

    private static void everyDrawInputChangesTheKey()
    {
        long base = ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 1F, 1F, 1F, 0, 12345);

        require(ModelInstance.weldDrawKey(SEED, 0, 0, 1F, 1F, 1F, 1F, 0, 12345) != base,
            "the draw's light must be in the weld key (stencil and world passes use different light)");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 1, 1F, 1F, 1F, 1F, 0, 12345) != base,
            "the draw's overlay must be in the weld key");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 0.5F, 1F, 1F, 1F, 0, 12345) != base,
            "the draw's red must be in the weld key");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 0.5F, 1F, 1F, 0, 12345) != base,
            "the draw's green must be in the weld key");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 1F, 0.5F, 1F, 0, 12345) != base,
            "the draw's blue must be in the weld key");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 1F, 1F, 0.5F, 0, 12345) != base,
            "the draw's alpha must be in the weld key");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 1F, 1F, 1F, 1, 12345) != base,
            "the picking mode must be in the weld key (it bakes stencil ids into the light attribute)");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 1F, 1F, 1F, 2, 12345) != base,
            "a picking pass that writes stencil ids and one that only clears must not share a bake");
        require(ModelInstance.weldDrawKey(SEED, 0xF000F0, 0, 1F, 1F, 1F, 1F, 0, 12346) != base,
            "the shape keys must be in the weld key");

        System.out.println("[weld-cache] every draw input changes the weld key");
    }

    private static void sameDrawInputsGiveTheSameKey()
    {
        long first = ModelInstance.weldDrawKey(SEED, 0xF000F0, 7, 1F, 1F, 1F, 1F, 0, 12345);
        long second = ModelInstance.weldDrawKey(SEED, 0xF000F0, 7, 1F, 1F, 1F, 1F, 0, 12345);

        require(first == second, "the same draw inputs must give the same key, or the cache would never hit");

        System.out.println("[weld-cache] the same draw inputs give the same key");
    }

    /**
     * The other half of the key: the bones. A component dropped here is worse than one dropped from
     * the draw half — the key would still be stable, and the cache would happily keep serving the
     * geometry baked for the PREVIOUS pose, forever, because nothing else in the key moved.
     */
    private static void everyBoneInputChangesTheKey()
    {
        long base = ModelInstance.weldPoseKey(0, List.of(bone()));

        require(ModelInstance.weldPoseKey(1, List.of(bone())) != base,
            "the number of VAO bones must be in the weld key (it decides which bones the bake emits)");

        require(ModelInstance.weldPoseKey(0, List.of(bone(), bone())) != base,
            "a bone added to the model must change the weld key");

        ModelGroup hidden = bone();

        hidden.visible = false;

        require(ModelInstance.weldPoseKey(0, List.of(hidden)) != base,
            "a bone's visibility must be in the weld key (a hidden bone is not emitted)");

        ModelGroup moved = bone();

        moved.current.translate.set(1F, 0F, 0F);

        require(ModelInstance.weldPoseKey(0, List.of(moved)) != base,
            "a bone's current translation must be in the weld key, or a moved pose would be served the previous bake");

        ModelGroup scaled = bone();

        scaled.current.scale.set(2F, 2F, 2F);

        require(ModelInstance.weldPoseKey(0, List.of(scaled)) != base,
            "a bone's current scale must be in the weld key");

        ModelGroup rotated = bone();

        rotated.current.rotate.set(0F, 90F, 0F);

        require(ModelInstance.weldPoseKey(0, List.of(rotated)) != base,
            "a bone's current euler rotation must be in the weld key");

        ModelGroup turned = bone();

        turned.current.quat.set(0.5F, 0.5F, 0.5F, 0.5F);

        require(ModelInstance.weldPoseKey(0, List.of(turned)) != base,
            "a bone's current quaternion rotation must be in the weld key");

        ModelGroup switched = bone();

        switched.current.rotationMode = Transform.RotationMode.QUATERNION;

        require(ModelInstance.weldPoseKey(0, List.of(switched)) != base,
            "a bone's rotation mode must be in the weld key (it picks which of the two fields is the rotation)");

        ModelGroup pivoted = bone();

        pivoted.initial.translate.set(0F, 1F, 0F);

        require(ModelInstance.weldPoseKey(0, List.of(pivoted)) != base,
            "a bone's initial pivot must be in the weld key (it enters the bone's matrix)");

        ModelGroup oriented = bone();

        oriented.orient = new Quaternionf(0.5F, 0.5F, 0.5F, 0.5F);

        require(ModelInstance.weldPoseKey(0, List.of(oriented)) != base,
            "a bone's orient must be in the weld key (it rotates the whole bone)");

        ModelGroup offset = bone();

        offset.offset = new Vector3f(1F, 0F, 0F);

        require(ModelInstance.weldPoseKey(0, List.of(offset)) != base,
            "a bone's offset must be in the weld key");

        ModelGroup tinted = bone();

        tinted.color.set(0.5F, 1F, 1F, 1F);

        require(ModelInstance.weldPoseKey(0, List.of(tinted)) != base,
            "a bone's colour must be in the weld key (the bake's vertices carry it)");

        ModelGroup lit = bone();

        lit.lighting = 0.5F;

        require(ModelInstance.weldPoseKey(0, List.of(lit)) != base,
            "a bone's lighting must be in the weld key (it lerps the lightmap baked into the vertices)");

        System.out.println("[weld-cache] every bone input changes the weld key");
    }

    private static void sameBoneInputsGiveTheSameKey()
    {
        long first = ModelInstance.weldPoseKey(3, List.of(bone(), bone()));
        long second = ModelInstance.weldPoseKey(3, List.of(bone(), bone()));

        require(first == second, "the same bone inputs must give the same key, or the cache would never hit");

        System.out.println("[weld-cache] the same bone inputs give the same key");
    }

    /** A bone with nothing set: identity pose, white, unlit — the starting point of every comparison above. */
    private static ModelGroup bone()
    {
        return new ModelGroup("bone");
    }

    /** A fresh bake for {@code key} in {@code epoch} — the caller's side of the cache protocol. */
    private static WeldGeometryCache.Entry bake(WeldGeometryCache cache, long key, long epoch)
    {
        WeldGeometryCache.Entry entry = cache.acquire(epoch);

        require(entry != null, "an unbaked, unlent ring must hand out an entry");

        entry.key = key;
        entry.valid = true;
        entry.hasGeometry = true;

        return entry;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError("weld-cache: " + message);
        }
    }
}
