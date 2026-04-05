package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Transitional adapter kept for source compatibility with old call sites.
 *
 * @deprecated Use {@link IBbsWorldRenderContext} and {@link BBSWorldRenderContext#bridge(IBbsWorldRenderContext)} directly.
 */
@Deprecated(forRemoval = false, since = "M10")
public final class FabricWorldRenderContextAdapter
{
    private FabricWorldRenderContextAdapter() {}

    public static BBSWorldRenderContext wrap(Object context)
    {
        if (context == null)
        {
            throw new IllegalArgumentException("context");
        }

        if (context instanceof IBbsWorldRenderContext worldRenderContext)
        {
            return BBSWorldRenderContext.bridge(worldRenderContext);
        }

        final Object delegate = context;

        return new BBSWorldRenderContext()
        {
            @Override
            public net.minecraft.client.render.Camera camera()
            {
                return invoke(delegate, "camera", net.minecraft.client.render.Camera.class);
            }

            @Override
            public net.minecraft.client.util.math.MatrixStack matrixStack()
            {
                return invoke(delegate, "matrixStack", net.minecraft.client.util.math.MatrixStack.class);
            }

            @Override
            public net.minecraft.client.render.VertexConsumerProvider.Immediate consumers()
            {
                return invoke(delegate, "consumers", net.minecraft.client.render.VertexConsumerProvider.Immediate.class);
            }

            @Override
            public float tickDelta()
            {
                return invokeFloat(delegate, "tickDelta");
            }
        };
    }

    private static <T> T invoke(Object delegate, String methodName, Class<T> expectedType)
    {
        Object value = invoke(delegate, methodName);
        if (expectedType.isInstance(value))
        {
            return expectedType.cast(value);
        }

        throw new IllegalStateException("Unexpected return type from " + delegate.getClass().getName() + "#" + methodName);
    }

    private static float invokeFloat(Object delegate, String methodName)
    {
        Object value = invoke(delegate, methodName);
        if (value instanceof Number number)
        {
            return number.floatValue();
        }

        throw new IllegalStateException("Unexpected return type from " + delegate.getClass().getName() + "#" + methodName);
    }

    private static Object invoke(Object delegate, String methodName)
    {
        try
        {
            Method method = delegate.getClass().getMethod(methodName);
            return method.invoke(delegate);
        }
        catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e)
        {
            throw new IllegalStateException("Unsupported world render context delegate: " + delegate.getClass().getName(), e);
        }
    }
}
