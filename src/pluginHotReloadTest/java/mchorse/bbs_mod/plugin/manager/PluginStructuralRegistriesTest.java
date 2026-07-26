package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginKind;
import mchorse.bbs_mod.api.plugin.BBSPluginReloadMode;
import mchorse.bbs_mod.api.plugin.BBSPluginSide;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain-Java coverage for common structural plugin registries. */
public final class PluginStructuralRegistriesTest
{
    private PluginStructuralRegistriesTest() {}

    public static void main(String[] args) throws Exception
    {
        mapFactoryUnregisterAndCopyPreserveData();
        structuralRegistriesRegisterAndLedgerCleanup();
        structuralRegistriesRequireDeclaredCapabilities();

        System.out.println("PluginStructuralRegistriesTest: all tests passed");
    }

    private static void mapFactoryUnregisterAndCopyPreserveData()
    {
        MapFactory<Form, String> forms = new MapFactory<>();
        Link id = Link.bbs("fixture");

        forms.register(id, TestForm.class, "payload");
        check("payload".equals(forms.copy().getData(id)), "map copy lost per-id data");
        check(forms.getTypeClass(id) == TestForm.class, "form registration did not stick");

        forms.unregister(id);
        check(forms.getTypeClass(id) == null, "Link unregister left the type registered");
        check(forms.getData(id) == null, "Link unregister left the per-id data registered");

        forms.register(id, TestForm.class, "payload");
        forms.unregister("bbs:fixture");
        check(forms.getTypeClass(id) == null, "String unregister left the type registered");
        check(forms.getData(id) == null, "String unregister left the per-id data registered");
    }

    private static void structuralRegistriesRegisterAndLedgerCleanup() throws Exception
    {
        PluginOwner owner = new PluginOwner("fixture-plugin", 1L);
        PluginContributionLedger ledger = new PluginContributionLedger(owner);
        FormArchitect forms = new FormArchitect();
        MapFactory<Clip, ClipFactoryData> cameraClips = new MapFactory<>();
        MapFactory<Clip, ClipFactoryData> actionClips = new MapFactory<>();
        PluginParticleComponents particleComponents = new PluginParticleComponents();
        PluginStructuralRegistrationWindow window = new PluginStructuralRegistrationWindow(owner);
        BBSPluginDescriptor descriptor = descriptor(
            Set.of(BBSPluginCapability.FORMS, BBSPluginCapability.CLIPS, BBSPluginCapability.PARTICLES)
        );

        PluginGenerationContext context = new PluginGenerationContext(
            descriptor,
            owner,
            Path.of("."),
            (severity, code, message) -> {},
            ledger,
            PluginStructuralRegistriesTest.class.getClassLoader(),
            window,
            PluginStructuralRegistryAdapters.forms(window, descriptor, owner, ledger, () -> forms),
            PluginStructuralRegistryAdapters.clips(window, descriptor, owner, ledger, () -> cameraClips, () -> actionClips),
            PluginStructuralRegistryAdapters.particles(
                window, descriptor, owner, ledger, particleComponents,
                PluginStructuralRegistriesTest.class.getClassLoader(), Map::of
            )
        );

        BBSRegistrationResult form = context.forms().register(Link.bbs("fixture_form"), TestForm.class);
        BBSRegistrationResult camera = context.clips().registerCameraClip(
            Link.bbs("fixture_camera"),
            TestClip.class,
            new ClipFactoryData(Icons.CAMERA, 0xff00ff)
        );
        BBSRegistrationResult action = context.clips().registerActionClip(
            Link.bbs("fixture_action"),
            TestClip.class,
            new ClipFactoryData(Icons.CAMERA, 0xff00ff)
        );
        BBSRegistrationResult particle = context.particles().registerComponent(
            "fixture_component",
            "fixture.Component"
        );

        check(form.accepted(), "form registry rejected a valid structural registration");
        check(camera.accepted(), "camera clip registry rejected a valid structural registration");
        check(action.accepted(), "action clip registry rejected a valid structural registration");
        check(particle.accepted(), "particle registry rejected a valid structural registration");
        check(forms.getTypeClass(Link.bbs("fixture_form")) == null,
            "staged form registration changed the host before commit");

        window.activate();
        check(forms.getTypeClass(Link.bbs("fixture_form")) == TestForm.class,
            "form registry did not update the host FormArchitect at commit");
        check(cameraClips.getTypeClass(Link.bbs("fixture_camera")) == TestClip.class,
            "camera clip registry did not update the host clip factory at commit");
        check(actionClips.getTypeClass(Link.bbs("fixture_action")) == TestClip.class,
            "action clip registry did not update the action clip factory at commit");
        check("fixture.Component".equals(particleComponents.className("fixture_component")),
            "particle registry did not update the hot-plugin particle table at commit");

        context.sealStructuralRegistrations();

        try
        {
            context.forms().register(Link.bbs("late_form"), TestForm.class);
            throw new AssertionError("sealed structural form registry accepted a late registration");
        }
        catch (IllegalStateException expected)
        {}

        try
        {
            ledger.close();
        }
        catch (Throwable throwable)
        {
            throw new AssertionError("ledger cleanup unexpectedly failed", throwable);
        }

        check(forms.getTypeClass(Link.bbs("fixture_form")) == null,
            "ledger cleanup left the form registration behind");
        check(cameraClips.getTypeClass(Link.bbs("fixture_camera")) == null,
            "ledger cleanup left the camera clip registration behind");
        check(actionClips.getTypeClass(Link.bbs("fixture_action")) == null,
            "ledger cleanup left the action clip registration behind");
        check(particleComponents.className("fixture_component") == null,
            "ledger cleanup left the particle component registration behind");
    }

    private static void structuralRegistriesRequireDeclaredCapabilities()
    {
        PluginOwner owner = new PluginOwner("capability-check", 1L);
        PluginContributionLedger ledger = new PluginContributionLedger(owner);
        PluginStructuralRegistrationWindow window = new PluginStructuralRegistrationWindow(owner);
        BBSPluginDescriptor descriptor = descriptor(Set.of());

        PluginGenerationContext context = new PluginGenerationContext(
            descriptor,
            owner,
            Path.of("."),
            (severity, code, message) -> {},
            ledger,
            PluginStructuralRegistriesTest.class.getClassLoader(),
            window,
            PluginStructuralRegistryAdapters.forms(window, descriptor, owner, ledger, FormArchitect::new),
            PluginStructuralRegistryAdapters.clips(window, descriptor, owner, ledger, MapFactory::new, MapFactory::new),
            PluginStructuralRegistryAdapters.particles(
                window, descriptor, owner, ledger, new PluginParticleComponents(),
                PluginStructuralRegistriesTest.class.getClassLoader(), Map::of
            )
        );

        try
        {
            context.forms().register(Link.bbs("missing_capability"), TestForm.class);
            throw new AssertionError("forms facade ignored a missing declared capability");
        }
        catch (IllegalStateException expected)
        {}

        try
        {
            ledger.close();
        }
        catch (Throwable throwable)
        {
            throw new AssertionError("capability-check ledger cleanup unexpectedly failed", throwable);
        }
    }

    private static BBSPluginDescriptor descriptor(Set<BBSPluginCapability> capabilities)
    {
        return new BBSPluginDescriptor(
            1,
            BBSPluginKind.CODE,
            "fixture-plugin",
            "Fixture Plugin",
            "1.0.0",
            "[1.0,2.0)",
            "fixture.Plugin",
            null,
            BBSPluginSide.COMMON,
            capabilities,
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

    public static class TestForm extends Form
    {}

    public static class TestClip extends Clip
    {
        @Override
        protected Clip create()
        {
            return new TestClip();
        }
    }
}
