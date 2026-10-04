package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.joml.Vector4f;

/** Shared geometry for camera-object drawing and picking against a displayed frame. */
public final class FilmCameraMarkerGeometry
{
    public static final float LINE_HALF_WIDTH = 0.5F;

    public static Vector3f[] corners(Position pose, float aspect, float depth)
    {
        float ratio = Float.isFinite(aspect) && aspect > 0F ? aspect : 1F;
        float distance = Float.isFinite(depth) ? Math.max(0.01F, depth) : 1F;
        float fov = Float.isFinite(pose.angle.fov) ? Math.max(1F, Math.min(179F, pose.angle.fov)) : 70F;
        float height = (float) Math.tan(MathUtils.toRad(fov) * 0.5F) * distance;
        float width = height * ratio;
        Matrix3f inverseView = new Matrix3f()
            .rotateY(-MathUtils.toRad(pose.angle.yaw))
            .rotateX(-MathUtils.toRad(pose.angle.pitch))
            .rotateZ(-MathUtils.toRad(pose.angle.roll));

        return new Vector3f[] {
            inverseView.transform(new Vector3f(-width, -height, -distance)),
            inverseView.transform(new Vector3f(width, -height, -distance)),
            inverseView.transform(new Vector3f(width, height, -distance)),
            inverseView.transform(new Vector3f(-width, height, -distance))
        };
    }

    public static ScreenPoint project(Position pose, Camera camera, ViewFrameGeometry frame)
    {
        Vector4f point = new Vector4f((float) (pose.point.x - camera.position.x),
            (float) (pose.point.y - camera.position.y), (float) (pose.point.z - camera.position.z), 1F);

        point.mul(camera.view).mul(camera.projection);

        if (!Float.isFinite(point.w) || point.w <= 0F)
        {
            return null;
        }

        point.div(point.w);

        if (!Float.isFinite(point.x) || !Float.isFinite(point.y) || !Float.isFinite(point.z)
            || point.z < -1F || point.z > 1F)
        {
            return null;
        }

        return new ScreenPoint(frame.x() + (point.x + 1F) * frame.width() * 0.5F,
            frame.y() + (1F - point.y) * frame.height() * 0.5F, point.z);
    }

    /** Clip before division so a guide crossing the eye cannot expand across the whole UI. */
    public static ScreenSegment projectSegment(Position origin, Vector3f fromOffset, Vector3f toOffset,
        Camera camera, ViewFrameGeometry frame)
    {
        if (frame.width() <= 0 || frame.height() <= 0)
        {
            return null;
        }

        Vector4d from = clipPosition(origin, fromOffset, camera);
        Vector4d to = clipPosition(origin, toOffset, camera);

        if (!from.isFinite() || !to.isFinite())
        {
            return null;
        }

        double start = 0D;
        double end = 1D;

        for (int plane = 0; plane < 7; plane++)
        {
            double fromDistance = planeDistance(from, plane);
            double toDistance = planeDistance(to, plane);

            if (fromDistance < 0D && toDistance < 0D)
            {
                return null;
            }

            if (fromDistance < 0D || toDistance < 0D)
            {
                double intersection = fromDistance / (fromDistance - toDistance);

                if (fromDistance < 0D)
                {
                    start = Math.max(start, intersection);
                }
                else
                {
                    end = Math.min(end, intersection);
                }

                if (start >= end)
                {
                    return null;
                }
            }
        }

        ScreenPoint first = screenPosition(new Vector4d(from).lerp(to, start), frame);
        ScreenPoint last = screenPosition(new Vector4d(from).lerp(to, end), frame);
        double dx = last.x() - first.x();
        double dy = last.y() - first.y();

        return dx * dx + dy * dy > 0.000001D ? new ScreenSegment(first, last) : null;
    }

    private static Vector4d clipPosition(Position origin, Vector3f offset, Camera camera)
    {
        return new Vector4d(origin.point.x - camera.position.x + offset.x,
            origin.point.y - camera.position.y + offset.y, origin.point.z - camera.position.z + offset.z, 1D)
            .mul(camera.view).mul(camera.projection);
    }

    private static double planeDistance(Vector4d point, int plane)
    {
        return switch (plane)
        {
            case 0 -> point.w + point.x;
            case 1 -> point.w - point.x;
            case 2 -> point.w + point.y;
            case 3 -> point.w - point.y;
            case 4 -> point.w + point.z;
            case 5 -> point.w - point.z;
            default -> point.w - 0.0000001D;
        };
    }

    private static ScreenPoint screenPosition(Vector4d point, ViewFrameGeometry frame)
    {
        double x = Math.max(-1D, Math.min(1D, point.x / point.w));
        double y = Math.max(-1D, Math.min(1D, point.y / point.w));
        double depth = Math.max(-1D, Math.min(1D, point.z / point.w));

        return new ScreenPoint((float) (frame.x() + (x + 1D) * frame.width() * 0.5D),
            (float) (frame.y() + (1D - y) * frame.height() * 0.5D), (float) depth);
    }

    private FilmCameraMarkerGeometry()
    {}

    public record ScreenPoint(float x, float y, float depth)
    {}

    public record ScreenSegment(ScreenPoint from, ScreenPoint to)
    {}
}
