package mchorse.bbs_mod.api.client.ui;

import java.util.List;
import java.util.Objects;

/**
 * Painter-ordered triangle list with a color at every transformed vertex.
 */
public final class BBSUiColoredMesh implements BBSUiDrawCommand
{
    public static final int MAX_VERTICES = 1023;

    private final List<BBSUiVertex> vertices;

    public BBSUiColoredMesh(List<BBSUiVertex> vertices)
    {
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
        return BBSUiDrawCommandType.COLORED_MESH;
    }

    public List<BBSUiVertex> vertices()
    {
        return this.vertices;
    }
}
