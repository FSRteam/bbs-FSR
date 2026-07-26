package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginKind;
import mchorse.bbs_mod.api.plugin.BBSPluginReloadMode;
import mchorse.bbs_mod.api.plugin.BBSPluginSide;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.clips.MissingClip;
import mchorse.bbs_mod.utils.factory.MapFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coverage for acceptance #5 of the FSR structural hot reload: a single instance's
 * rebuild failure (fixture-injected, real deserialization throw - not a "missing type")
 * must degrade only that instance to a placeholder and report a diagnostic, while every
 * other live instance rebuilds normally and the swap itself never throws.
 *
 * <p>This drives {@link PluginStructuralReloadCoordinator} directly with the exact
 * registration path {@code BBSPluginManager} uses ({@link PluginStructuralRegistryAdapters}
 * over plain host registry instances), so it needs no Minecraft bootstrap.</p>
 *
 * <p>Note: this deliberately injects the failure on a <b>clip</b>, not a form, and only
 * exercises the clip registry ({@link Clips} takes its {@code IFactory} by constructor
 * argument, so a plain local {@link MapFactory} is enough to drive a real snapshot/rebuild).
 * {@code PluginStructuralInstanceTracker}'s form path instead always resolves through the
 * process-global {@code BBSMod.getForms()} via {@code FormUtils}, which additionally
 * swallows every exception from the underlying form factory and falls back to a silent
 * {@code MissingForm} before this coordinator's rebuild-failure callback ever sees a throw -
 * see the report for this task for why a genuine form rebuild failure can never surface as a
 * {@code REBUILD_FAILED} diagnostic today.</p>
 */
public final class PluginStructuralRebuildFailureTest
{
    private PluginStructuralRebuildFailureTest() {}

    public static void main(String[] args) throws Exception
    {
        singleInstanceRebuildFailureDegradesOnlyThatInstance();

        System.out.println("PluginStructuralRebuildFailureTest: all tests passed");
    }

    private static void singleInstanceRebuildFailureDegradesOnlyThatInstance() throws Exception
    {
        MapFactory<Clip, ClipFactoryData> cameraClips = new MapFactory<>();
        MapFactory<Clip, ClipFactoryData> actionClips = new MapFactory<>();
        Link healthyClipLink = Link.bbs("rebuild_fixture_healthy_clip");
        Link faultyClipLink = Link.bbs("rebuild_fixture_faulty_clip");
        BBSPluginDescriptor descriptor = descriptor();

        PluginOwner incumbentOwner = new PluginOwner("rebuild-fixture", 1L);
        PluginContributionLedger incumbentLedger = new PluginContributionLedger(incumbentOwner);
        PluginStructuralRegistrationWindow incumbentWindow = new PluginStructuralRegistrationWindow(incumbentOwner);
        PluginGenerationContext incumbentContext = context(descriptor, incumbentOwner, incumbentLedger, incumbentWindow, cameraClips, actionClips);

        check(incumbentContext.clips().registerCameraClip(healthyClipLink, HealthyClipV1.class, new ClipFactoryData(Icons.CAMERA, 0)).accepted(),
            "v1 healthy clip registration was rejected");
        check(incumbentContext.clips().registerCameraClip(faultyClipLink, FaultyClipV1.class, new ClipFactoryData(Icons.CAMERA, 0)).accepted(),
            "v1 faulty clip registration was rejected");
        incumbentWindow.activate();

        /* Live instances that must survive the swap below, exactly like clips sitting
         * inside an open film's camera track. */
        Clips clipsHolder = new Clips("rebuild-fixture-clips-holder", cameraClips);
        Clip healthyInstance = new HealthyClipV1();
        healthyInstance.tick.set(11);
        clipsHolder.addClip(healthyInstance);
        Clip faultyInstance = new FaultyClipV1();
        faultyInstance.tick.set(22);
        clipsHolder.addClip(faultyInstance);

        PluginOwner candidateOwner = new PluginOwner("rebuild-fixture", 2L);
        PluginContributionLedger candidateLedger = new PluginContributionLedger(candidateOwner);
        PluginStructuralRegistrationWindow candidateWindow = new PluginStructuralRegistrationWindow(candidateOwner, incumbentWindow.keys());
        PluginGenerationContext candidateContext = context(descriptor, candidateOwner, candidateLedger, candidateWindow, cameraClips, actionClips);

        check(candidateContext.clips().registerCameraClip(healthyClipLink, HealthyClipV2.class, new ClipFactoryData(Icons.CAMERA, 0)).accepted(),
            "v2 healthy clip registration was rejected");
        check(candidateContext.clips().registerCameraClip(faultyClipLink, FaultyClipV2.class, new ClipFactoryData(Icons.CAMERA, 0)).accepted(),
            "v2 faulty clip registration was rejected");

        PluginStructuralReloadCoordinator coordinator = new PluginStructuralReloadCoordinator(Runnable::run, () -> false);
        List<String> failedTypes = new ArrayList<>();
        List<Throwable> failedErrors = new ArrayList<>();

        /* The swap itself must never throw, even though one instance's rebuild fails. */
        coordinator.replace(incumbentWindow, candidateWindow, (type, error) ->
        {
            failedTypes.add(type);
            failedErrors.add(error);
        });

        check(failedTypes.size() == 1, "exactly one rebuild failure must be reported, got " + failedTypes);
        check(failedTypes.get(0).contains("rebuild_fixture_faulty_clip"), "the reported failure must identify the faulty clip, got " + failedTypes);
        check(failedErrors.get(0) instanceof IllegalStateException, "the reported failure must carry the injected exception");
        check("fixture rebuild failure injection".equals(failedErrors.get(0).getMessage()), "the reported failure must carry the injected message");

        /* The faulty instance degrades to a placeholder that keeps its original data. */
        Clip degradedClip = clipsHolder.get(1);
        check(degradedClip instanceof MissingClip, "the faulty clip instance was not degraded to a placeholder");
        check(((MissingClip) degradedClip).sourceData().getInt("tick") == 22, "the placeholder lost the faulty clip's data");

        /* Every other instance rebuilds normally under the new generation's types. */
        Clip rebuiltClip = clipsHolder.get(0);
        check(!(rebuiltClip instanceof MissingClip), "the healthy clip instance was wrongly degraded to a placeholder");
        check(rebuiltClip.getClass() == HealthyClipV2.class, "the healthy clip instance did not rebuild under the v2 type");
        check(rebuiltClip.tick.get() == 11, "the healthy clip instance lost its data during rebuild");

        /* The old generation is not revived: the host registries only resolve to v2 now. */
        check(cameraClips.getTypeClass(healthyClipLink) == HealthyClipV2.class, "the v1 healthy clip type was left registered after the swap");
        check(cameraClips.getTypeClass(faultyClipLink) == FaultyClipV2.class, "the v1 faulty clip type was left registered after the swap");

        incumbentLedger.close();
        candidateLedger.close();
    }

    private static PluginGenerationContext context(
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        PluginStructuralRegistrationWindow window,
        MapFactory<Clip, ClipFactoryData> cameraClips,
        MapFactory<Clip, ClipFactoryData> actionClips
    )
    {
        return new PluginGenerationContext(
            descriptor,
            owner,
            Path.of("."),
            (severity, code, message) -> {},
            ledger,
            PluginStructuralRebuildFailureTest.class.getClassLoader(),
            window,
            PluginStructuralRegistryAdapters.forms(window, descriptor, owner, ledger, FormArchitect::new),
            PluginStructuralRegistryAdapters.clips(window, descriptor, owner, ledger, () -> cameraClips, () -> actionClips),
            PluginStructuralRegistryAdapters.particles(
                window, descriptor, owner, ledger, new PluginParticleComponents(),
                PluginStructuralRebuildFailureTest.class.getClassLoader(), Map::of
            )
        );
    }

    private static BBSPluginDescriptor descriptor()
    {
        return new BBSPluginDescriptor(
            1,
            BBSPluginKind.CODE,
            "rebuild-fixture",
            "Rebuild Fixture",
            "1.0.0",
            "[1.0,2.0)",
            "fixture.Plugin",
            null,
            BBSPluginSide.COMMON,
            Set.of(BBSPluginCapability.CLIPS),
            List.of(),
            BBSPluginReloadMode.HOT
        );
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    public static final class HealthyClipV1 extends Clip
    {
        @Override
        protected Clip create()
        {
            return new HealthyClipV1();
        }
    }

    public static final class HealthyClipV2 extends Clip
    {
        @Override
        protected Clip create()
        {
            return new HealthyClipV2();
        }
    }

    public static final class FaultyClipV1 extends Clip
    {
        @Override
        protected Clip create()
        {
            return new FaultyClipV1();
        }
    }

    /** Registered type exists (not "missing"); deserialization of this specific type genuinely throws. */
    public static final class FaultyClipV2 extends Clip
    {
        @Override
        protected Clip create()
        {
            return new FaultyClipV2();
        }

        @Override
        public void fromData(BaseType data)
        {
            super.fromData(data);

            throw new IllegalStateException("fixture rebuild failure injection");
        }
    }
}
