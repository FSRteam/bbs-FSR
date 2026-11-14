package mchorse.bbs_mod.film;

import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;

/** Describes the transform target currently selected in the film editor. */
public record FilmTarget(Kind kind, String bone, TransformSpace space)
{
    public enum Kind { NONE, ROOT, ANCHOR, BONE }

    public static final FilmTarget NONE = new FilmTarget(Kind.NONE, null, TransformSpace.LOCAL);

    public static FilmTarget bone(String bone, TransformSpace space)
    {
        return new FilmTarget(Kind.BONE, bone, space == null ? TransformSpace.LOCAL : space);
    }

    public static FilmTarget anchor(TransformSpace space)
    {
        return new FilmTarget(Kind.ANCHOR, null, space == null ? TransformSpace.LOCAL : space);
    }

    public static FilmTarget root(TransformSpace space)
    {
        return new FilmTarget(Kind.ROOT, null, space == null ? TransformSpace.LOCAL : space);
    }

    public boolean isNone() { return this.kind == Kind.NONE; }
    public boolean is(Kind value) { return this.kind == value; }
    public String boneOrNull() { return this.kind == Kind.BONE ? this.bone : null; }
}
