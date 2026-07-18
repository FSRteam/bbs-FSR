package mchorse.bbs_mod.api.client.ui;

/**
 * Immutable browser-neutral command produced while a BBS UI is rendered.
 */
public interface BBSUiDrawCommand
{
    BBSUiDrawCommandType type();
}
