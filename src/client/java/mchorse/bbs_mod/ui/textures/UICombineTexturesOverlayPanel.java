package mchorse.bbs_mod.ui.textures;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageBarOverlayPanel;

/**
 * Asks the two things the pictures can't say about the animation they are about to become: what
 * it is called and how long a frame lasts. The name and the size go the way the new texture
 * dialog puts them, so the two read alike.
 *
 * <p>The order of the frames isn't asked for here: it is the order the browser shows them in,
 * and the frame strip of the texture editor is where it is changed afterwards.</p>
 */
public class UICombineTexturesOverlayPanel extends UIMessageBarOverlayPanel
{
    /** The longest a single frame may be held, in ticks. */
    public static final int MAX_TIME = 32000;

    /** The default frametime, the same the animation editor starts a blank animation with. */
    public static final int DEFAULT_FRAMETIME = 2;

    /** The room the name field and its gap take above the bar. */
    private static final int NAME_HEIGHT = 25;

    public UITextbox name;
    public UITrackpad frametime;
    public UIButton combine;

    private final Callback callback;

    public interface Callback
    {
        public void combine(String name, int frametime);
    }

    public UICombineTexturesOverlayPanel(IKey message, String name, Callback callback)
    {
        super(UIKeys.TEXTURES_BROWSER_COMBINE_TITLE, message);

        this.callback = callback;

        /* The bar gives up the room the name field needs above it */
        this.bar.relative(this.content).x(6).y(1F, -6 - NAME_HEIGHT).w(1F, -12).anchor(0, 1F);

        this.name = new UITextbox(100, null).filename();
        this.name.placeholder(UIKeys.TEXTURES_BROWSER_COMBINE_NAME);
        this.name.setText(name);
        this.name.relative(this.bar).y(-5).w(1F).h(20).anchorY(1F);

        this.frametime = new UITrackpad();
        this.frametime.limit(1, MAX_TIME, true).setValue(DEFAULT_FRAMETIME);
        this.frametime.tooltip(UIKeys.TEXTURES_BROWSER_COMBINE_FRAMETIME);
        this.combine = new UIButton(UIKeys.TEXTURES_BROWSER_COMBINE_CONFIRM, (b) -> this.confirm());
        this.combine.w(80);

        this.bar.remove(this.confirm);
        this.bar.add(this.frametime, this.combine);
        this.content.add(this.name);
    }

    @Override
    protected void onAdd(UIElement parent)
    {
        super.onAdd(parent);

        this.name.textbox.moveCursorToEnd();
        parent.getContext().focus(this.name);
    }

    @Override
    public void confirm()
    {
        String name = this.name.getText().trim();

        if (!name.isEmpty() && this.callback != null)
        {
            this.callback.combine(name, (int) this.frametime.getValue());
        }

        super.confirm();
    }
}
