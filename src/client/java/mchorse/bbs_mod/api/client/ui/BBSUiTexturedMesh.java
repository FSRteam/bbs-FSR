package mchorse.bbs_mod.api.client.ui;

import java.util.List;
import java.util.Objects;

/** Painter-ordered textured triangle list backed by one stable UI asset. */
public final class BBSUiTexturedMesh implements BBSUiDrawCommand
{
    public static final int MAX_VERTICES = 1023;

    private final BBSUiAssetRef asset;
    private final List<BBSUiTexturedMeshVertex> vertices;

    public BBSUiTexturedMesh(BBSUiAssetRef asset, List<BBSUiTexturedMeshVertex> vertices)
    {
        this.asset = Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(vertices, "vertices");

        if (vertices.isEmpty() || vertices.size() % 3 != 0 || vertices.size() > MAX_VERTICES)
        {
            throw new IllegalArgumentException("vertices must be a non-empty triangle list of at most " + MAX_VERTICES + " entries");
        }

        this.vertices = List.copyOf(vertices);

        if (this.vertices.stream().anyMatch(Objects::isNull))
        {
            throw new NullPointerException("vertices contains null");
        }
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.TEXTURED_MESH;
    }

    public BBSUiAssetRef asset()
    {
        return this.asset;
    }

    public List<BBSUiTexturedMeshVertex> vertices()
    {
        return this.vertices;
    }
}
