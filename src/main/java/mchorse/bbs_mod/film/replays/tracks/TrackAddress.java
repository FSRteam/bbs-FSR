package mchorse.bbs_mod.film.replays.tracks;

/** FSR source/binary compatibility view of the canonical {@link TrackId}. */
@Deprecated(forRemoval = false, since = "2.0")
public record TrackAddress(TrackKind kind, String formPath, String subject, String property)
{
    public static final String BONE_PREFIX = TrackId.BONE_PREFIX;
    public static final String CONSTRAINT_PREFIX = TrackId.CONSTRAINT_PREFIX;
    public static final String MATERIAL_TEXTURE_PREFIX = TrackId.MATERIAL_TEXTURE_PREFIX;
    public static final String MATERIAL_PROP_PREFIX = TrackId.MATERIAL_PROP_PREFIX;
    public static final String IK_TARGETS = TrackId.IK_TARGETS;
    public static final String POLE_TARGETS = TrackId.POLE_TARGETS;
    public static final String PHYSICS_TARGETS = TrackId.PHYSICS_TARGETS;
    public static final String MATERIAL_DEFAULT = TrackId.MATERIAL_DEFAULT;
    public static final String MATERIAL_PROP_COLOR = TrackId.MATERIAL_PROP_COLOR;
    public static final String MATERIAL_PROP_OVERLAY = TrackId.MATERIAL_PROP_OVERLAY;
    public static final String MATERIAL_PROP_LIGHTING = TrackId.MATERIAL_PROP_LIGHTING;
    public static final String MATERIAL_PROP_CULLING = TrackId.MATERIAL_PROP_CULLING;
    public static final String MATERIAL_PROP_VISIBLE = TrackId.MATERIAL_PROP_VISIBLE;
    public static final String MATERIAL_PROP_SMOOTHNESS = TrackId.MATERIAL_PROP_SMOOTHNESS;
    public static final String MATERIAL_PROP_METALLIC = TrackId.MATERIAL_PROP_METALLIC;
    public static final String MATERIAL_PROP_SSS = TrackId.MATERIAL_PROP_SSS;
    public static final String MATERIAL_PROP_PIXEL_EMISSION = TrackId.MATERIAL_PROP_PIXEL_EMISSION;
    public static final String MATERIAL_PROP_RELIEF = TrackId.MATERIAL_PROP_RELIEF;
    public static final String[] MATERIAL_PROPS_ALL = TrackId.MATERIAL_PROPS_ALL;

    public TrackAddress
    {
        formPath = formPath == null ? "" : formPath;
        subject = subject == null ? "" : subject;
        property = property == null ? "" : property;
    }

    public TrackId id()
    {
        return new TrackId(this.kind, this.formPath, this.subject, this.property);
    }

    private static TrackAddress of(TrackId track)
    {
        return track == null ? null : new TrackAddress(track.kind(), track.formPath(), track.subject(), track.property());
    }

    public static TrackAddress property(String formPath, String name)
    {
        return of(TrackId.property(formPath, name));
    }

    public static TrackAddress bone(String formPath, String bone)
    {
        return of(TrackId.bone(formPath, bone));
    }

    public static TrackAddress boneConstraint(String formPath, String bone)
    {
        return of(TrackId.boneConstraint(formPath, bone));
    }

    public static TrackAddress materialTexture(String formPath, String material)
    {
        return of(TrackId.materialTexture(formPath, material));
    }

    public static TrackAddress materialProp(String formPath, String material, String property)
    {
        return of(TrackId.materialProp(formPath, material, property));
    }

    public static TrackAddress ikTarget(String formPath, String controller)
    {
        return of(TrackId.ikTarget(formPath, controller));
    }

    public static TrackAddress poleTarget(String formPath, String controller)
    {
        return of(TrackId.poleTarget(formPath, controller));
    }

    public static TrackAddress physicsTarget(String formPath, String rootBone)
    {
        return of(TrackId.physicsTarget(formPath, rootBone));
    }

    public static TrackAddress ikControls(String formPath)
    {
        return of(TrackId.ikControls(formPath));
    }

    public static TrackAddress physicsControls(String formPath)
    {
        return of(TrackId.physicsControls(formPath));
    }

    public static TrackAddress windControls(String formPath)
    {
        return of(TrackId.windControls(formPath));
    }

    public boolean is(TrackKind kind) { return this.kind == kind; }
    public String material() { return this.id().material(); }
    public String subjectPath() { return this.id().subjectPath(); }
    public String toKey() { return this.id().toKey(); }
    public String label() { return this.id().label(); }
    @Override
    public String toString() { return this.toKey(); }
    public static TrackAddress parse(String key) { return of(TrackId.parse(key)); }
    public static TrackAddress parse(String key, TrackKind kind) { return of(TrackId.parse(key, kind)); }
    public static TrackKind kindOf(String key) { return TrackId.kindOf(key); }
}
