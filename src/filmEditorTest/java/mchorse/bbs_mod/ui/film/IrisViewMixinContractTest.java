package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.mixin.client.iris.IrisRenderTargetsViewMixin;
import mchorse.bbs_mod.utils.iris.IrisViewState;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks optional mixin contracts against the actual pinned dependency bytecode without starting GL. */
public final class IrisViewMixinContractTest
{
    private static final String PREFIX = "mchorse/bbs_mod/mixin/client/iris/";
    private static final List<String> MIXINS = List.of("SystemTimeUniformsCounterMixin", "SystemTimeUniformsTimerMixin",
        "IrisRenderingPipelineAccessor", "IrisRenderingPipelineViewMixin", "PipelineManagerViewMixin",
        "CapturedRenderingStateViewMixin", "WorldRenderingSettingsViewMixin", "GlResourceViewMixin",
        "IrisRenderTargetViewMixin", "IrisRenderTargetsViewMixin", "DynamicTextureViewMixin", "HorizonRendererViewMixin",
        "ShaderStorageBufferHolderAccessor", "ShaderStorageBufferViewMixin", "SodiumGlObjectViewMixin",
        "ShaderInstanceViewMixin", "ProgramViewMixin", "UniformViewMixin", "UnshadedChunkRendererMixin");
    private static final List<String> INJECTORS = List.of("Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");

    private IrisViewMixinContractTest()
    {}

    public static void main(String[] args) throws IOException
    {
        verifyMixins();
        System.out.println("IrisViewMixinContractTest: Iris and Sodium binary contracts passed");
    }

    public static void runAll() throws IOException
    {
        verifyDepthTargetSwitching();
        UnshadedShaderResourceTest.runAll();
        IrisViewHistoryResetTest.runAll();
        verifyMixins();
    }

    private static void verifyMixins() throws IOException
    {
        List<String> mixins = new ArrayList<>(MIXINS.stream().map(name -> PREFIX + name).toList());

        for (String name : List.of("SodiumBufferBuilderAccessor", "SodiumWorldRendererViewMixin",
            "SodiumRenderSectionManagerViewMixin", "EntityVertexMixin", "ColorAttributeMixin"))
        {
            mixins.add("mchorse/bbs_mod/mixin/client/sodium/" + name);
        }

        for (String name : mixins)
        {
            ClassNode mixin = readClass(name);
            AnnotationNode annotation = findAnnotation(annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations),
                "Lorg/spongepowered/asm/mixin/Mixin;");
            List<?> targets = (List<?>) value(annotation, "value");

            if (targets == null)
            {
                targets = (List<?>) value(annotation, "targets");
            }

            check(targets != null && !targets.isEmpty(), name + " has no explicit target");

            for (Object target : targets)
            {
                verify(mixin, readClass(target instanceof Type type ? type.getInternalName() : target.toString().replace('.', '/')));
            }
        }
    }

    private static void verifyDepthTargetSwitching() throws IOException
    {
        try
        {
            IrisRenderTargetsViewMixin target = new IrisRenderTargetsViewMixin() {};
            Field texture = IrisRenderTargetsViewMixin.class.getDeclaredField("currentDepthTexture");
            Field version = IrisRenderTargetsViewMixin.class.getDeclaredField("cachedDepthBufferVersion");
            ClassLoader loader = IrisViewMixinContractTest.class.getClassLoader();
            Method hook = IrisRenderTargetsViewMixin.class.getDeclaredMethod("bbs$trackDepthTarget",
                int.class, int.class, int.class, int.class,
                Class.forName("net.irisshaders.iris.gl.texture.DepthBufferFormat", false, loader),
                Class.forName("net.irisshaders.iris.shaderpack.properties.PackDirectives", false, loader),
                CallbackInfoReturnable.class);
            texture.setAccessible(true);
            version.setAccessible(true);
            hook.setAccessible(true);
            texture.setInt(target, 71);
            version.setInt(target, 3);

            hook.invoke(target, 3, 82, 320, 180, null, null, new CallbackInfoReturnable<Boolean>("resize", false));
            check(version.getInt(target) == 3, "ordinary Iris rendering must retain its native depth version");

            try (IrisViewState.Scope ignored = IrisViewState.enter("depth-switch", true, 0L))
            {
                hook.invoke(target, 3, 71, 320, 180, null, null, new CallbackInfoReturnable<Boolean>("resize", false));
                check(version.getInt(target) == 3, "the same depth target must not force reattachment");

                hook.invoke(target, 4, 82, 320, 180, null, null, new CallbackInfoReturnable<Boolean>("resize", false));
                check(version.getInt(target) == 3, "a native version change already requests depth reattachment");

                hook.invoke(target, 3, 82, 320, 180, null, null, new CallbackInfoReturnable<Boolean>("resize", false));
                check(version.getInt(target) != 3,
                    "same-version back buffers must enter Iris' native depth-attachment update branch");
                check(texture.getInt(target) == 71, "Iris must still own the actual depth attachment update");
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IOException("Could not verify Iris depth-target switching", exception);
        }
    }

    private static void verify(ClassNode mixin, ClassNode target)
    {
        for (FieldNode field : mixin.fields)
        {
            if (findAnnotation(annotations(field.visibleAnnotations, field.invisibleAnnotations),
                "Lorg/spongepowered/asm/mixin/Shadow;") != null)
            {
                check(target.fields.stream().anyMatch(actual -> actual.name.equals(field.name) && actual.desc.equals(field.desc)),
                    mixin.name + " shadows a missing or incompatible field " + field.name);
            }
        }

        for (MethodNode method : mixin.methods)
        {
            List<AnnotationNode> annotations = annotations(method.visibleAnnotations, method.invisibleAnnotations);
            AnnotationNode accessor = findAnnotation(annotations, "Lorg/spongepowered/asm/mixin/gen/Accessor;");

            if (accessor != null)
            {
                String field = (String) value(accessor, "value");
                Type returned = Type.getReturnType(method.desc);
                String descriptor = returned.equals(Type.VOID_TYPE) ? Type.getArgumentTypes(method.desc)[0].getDescriptor() : returned.getDescriptor();

                check(target.fields.stream().anyMatch(actual -> actual.name.equals(field) && actual.desc.equals(descriptor)),
                    mixin.name + " accesses a missing or incompatible field " + field);
            }

            if (findAnnotation(annotations, "Lorg/spongepowered/asm/mixin/Shadow;") != null)
            {
                check(target.methods.stream().anyMatch(actual -> actual.name.equals(method.name) && actual.desc.equals(method.desc)),
                    mixin.name + " shadows a missing method " + method.name);
            }

            for (String descriptor : INJECTORS)
            {
                AnnotationNode injection = findAnnotation(annotations, descriptor);

                if (injection != null)
                {
                    verifyInjection(mixin, target, injection);
                }
            }
        }
    }

    private static void verifyInjection(ClassNode mixin, ClassNode target, AnnotationNode injection)
    {
        for (Object selector : (List<?>) value(injection, "method"))
        {
            String selected = (String) selector;
            ITargetSelector parsed = MemberInfo.parse(selected, null).configure(ITargetSelector.Configure.SELECT_MEMBER);
            List<MethodNode> methods = target.methods.stream()
                .filter(actual -> parsed.match(ElementNode.of(target, actual)).isExactMatch())
                .limit(parsed.getMaxMatchCount()).toList();

            check(!methods.isEmpty(), mixin.name + " injects into a missing method " + selected);

            Object at = value(injection, "at");
            List<?> points = at instanceof List<?> list ? list : List.of(at);

            for (Object point : points)
            {
                verifyPoint(mixin, selected, methods, (AnnotationNode) point);
            }
        }
    }

    private static void verifyPoint(ClassNode mixin, String selector, List<MethodNode> methods, AnnotationNode at)
    {
        String kind = (String) value(at, "value");

        if (!"FIELD".equals(kind) && !"INVOKE".equals(kind))
        {
            return;
        }

        String member = (String) value(at, "target");
        int ownerEnd = member.indexOf(';');
        String owner = member.substring(1, ownerEnd);
        String nameAndDescriptor = member.substring(ownerEnd + 1);
        int descriptorStart = "FIELD".equals(kind) ? nameAndDescriptor.indexOf(':') : nameAndDescriptor.indexOf('(');
        String name = nameAndDescriptor.substring(0, descriptorStart);
        String descriptor = nameAndDescriptor.substring(descriptorStart + ("FIELD".equals(kind) ? 1 : 0));
        Integer opcode = (Integer) value(at, "opcode");
        Integer ordinal = (Integer) value(at, "ordinal");
        boolean found = false;

        for (MethodNode method : methods)
        {
            int matches = 0;

            for (AbstractInsnNode instruction : method.instructions)
            {
                if (opcode != null && opcode >= 0 && instruction.getOpcode() != opcode)
                {
                    continue;
                }

                if (instruction instanceof FieldInsnNode field && "FIELD".equals(kind)
                    && field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(descriptor))
                {
                    matches++;
                }
                else if (instruction instanceof MethodInsnNode call && "INVOKE".equals(kind)
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor))
                {
                    matches++;
                }
            }

            found |= matches > (ordinal == null || ordinal < 0 ? 0 : ordinal);
        }

        check(found, mixin.name + " has no matching " + kind + " allocation/call anchor " + member + " in " + selector);
    }

    private static ClassNode readClass(String name) throws IOException
    {
        String external = name.startsWith("net/irisshaders/") ? System.getProperty("bbs.compat.iris.jar")
            : name.startsWith("net/caffeinemc/") ? System.getProperty("bbs.compat.sodium.jar") : null;

        if (external != null)
        {
            try (ZipFile archive = new ZipFile(external))
            {
                ZipEntry entry = archive.getEntry(name + ".class");
                check(entry != null, "Missing compatibility fixture class: " + name);

                try (InputStream stream = archive.getInputStream(entry))
                {
                    ClassNode node = new ClassNode();
                    new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                    return node;
                }
            }
        }

        try (InputStream stream = IrisViewMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class"))
        {
            check(stream != null, "Missing runtime class for mixin contract: " + name);

            ClassNode node = new ClassNode();

            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            return node;
        }
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible)
    {
        List<AnnotationNode> result = new ArrayList<>();

        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);

        return result;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String descriptor)
    {
        return annotations.stream().filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode annotation, String key)
    {
        if (annotation != null && annotation.values != null)
        {
            for (int i = 0; i < annotation.values.size(); i += 2)
            {
                if (key.equals(annotation.values.get(i)))
                {
                    return annotation.values.get(i + 1);
                }
            }
        }

        return null;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
