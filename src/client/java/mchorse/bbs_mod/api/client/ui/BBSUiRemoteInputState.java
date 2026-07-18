package mchorse.bbs_mod.api.client.ui;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of continuously held browser input. Discrete click/key events alone
 * are insufficient for BBS controls that poll buttons while dragging.
 */
public final class BBSUiRemoteInputState
{
    public static final int MOD_SHIFT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_ALT = 0x0004;
    public static final int MOD_SUPER = 0x0008;

    private final double mouseX;
    private final double mouseY;
    private final int pressedMouseButtons;
    private final Set<Integer> pressedKeys;
    private final int modifiers;

    public BBSUiRemoteInputState(
        double mouseX,
        double mouseY,
        int pressedMouseButtons,
        Set<Integer> pressedKeys,
        int modifiers
    )
    {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.pressedMouseButtons = pressedMouseButtons;
        this.pressedKeys = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(pressedKeys, "pressedKeys")));
        this.modifiers = modifiers;
    }

    public double mouseX()
    {
        return this.mouseX;
    }

    public double mouseY()
    {
        return this.mouseY;
    }

    public int pressedMouseButtons()
    {
        return this.pressedMouseButtons;
    }

    public Set<Integer> pressedKeys()
    {
        return this.pressedKeys;
    }

    public int modifiers()
    {
        return this.modifiers;
    }

    public boolean isMouseButtonPressed(int button)
    {
        return button >= 0 && button < Integer.SIZE - 1 && (this.pressedMouseButtons & (1 << button)) != 0;
    }

    public boolean isKeyPressed(int keyCode)
    {
        return this.pressedKeys.contains(keyCode);
    }

    public boolean hasModifier(int modifier)
    {
        return (this.modifiers & modifier) != 0;
    }
}
