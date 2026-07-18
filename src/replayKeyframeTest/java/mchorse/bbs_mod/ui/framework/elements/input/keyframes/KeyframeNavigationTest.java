package mchorse.bbs_mod.ui.framework.elements.input.keyframes;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

public final class KeyframeNavigationTest
{
    public static void run()
    {
        testNavigationContext();
        testNavigationWithoutSelection();
        testNavigationWithSelection();
        testStaleSelection();
    }

    private static void testNavigationContext()
    {
        KeyframeChannel<Float> channel = channel();

        assertFalse(UIKeyframes.hasNavigationContext(false, null, null), "outside graph without selection");
        assertFalse(UIKeyframes.hasNavigationContext(false, channel.get(0), channel), "outside graph with selection");
        assertFalse(UIKeyframes.hasNavigationContext(true, null, null), "blank graph without selection");
        assertTrue(UIKeyframes.hasNavigationContext(true, null, channel), "hovered track");
        assertTrue(UIKeyframes.hasNavigationContext(true, channel.get(0), null), "selected keyframe");
    }

    private static void testNavigationWithoutSelection()
    {
        KeyframeChannel<Float> channel = channel();

        assertSame(null, UIKeyframes.resolveNavigationTarget(null, null, 5F, -1), "no hovered sheet");
        assertSame(channel.get(0), UIKeyframes.resolveNavigationTarget(null, channel, 5F, -1), "left from cursor");
        assertSame(channel.get(1), UIKeyframes.resolveNavigationTarget(null, channel, 5F, 1), "right from cursor");
        assertSame(null, UIKeyframes.resolveNavigationTarget(null, new KeyframeChannel<>("empty", KeyframeFactories.FLOAT), 5F, 1), "empty sheet");
    }

    private static void testNavigationWithSelection()
    {
        KeyframeChannel<Float> channel = channel();

        assertSame(channel.get(0), UIKeyframes.resolveNavigationTarget(channel.get(1), null, 0F, -1), "previous selected keyframe");
        assertSame(channel.get(2), UIKeyframes.resolveNavigationTarget(channel.get(1), null, 0F, 1), "next selected keyframe");
        assertSame(channel.get(2), UIKeyframes.resolveNavigationTarget(channel.get(0), null, 0F, -1), "previous wraps");
        assertSame(channel.get(0), UIKeyframes.resolveNavigationTarget(channel.get(2), null, 0F, 1), "next wraps");
        assertSame(null, UIKeyframes.resolveNavigationTarget(channel.get(1), null, 0F, 0), "zero direction");
    }

    private static void testStaleSelection()
    {
        KeyframeChannel<Float> channel = channel();
        Keyframe<Float> removed = channel.get(1);

        channel.remove(1);

        assertSame(null, UIKeyframes.resolveNavigationTarget(removed, null, 0F, 1), "stale selected keyframe");
    }

    private static KeyframeChannel<Float> channel()
    {
        KeyframeChannel<Float> channel = new KeyframeChannel<>("x", KeyframeFactories.FLOAT);

        channel.insert(0F, 0F);
        channel.insert(10F, 10F);
        channel.insert(20F, 20F);

        return channel;
    }

    private static void assertSame(Object expected, Object actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message + ": expected same instance " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message + ": expected true");
        }
    }

    private static void assertFalse(boolean value, String message)
    {
        if (value)
        {
            throw new AssertionError(message + ": expected false");
        }
    }
}
