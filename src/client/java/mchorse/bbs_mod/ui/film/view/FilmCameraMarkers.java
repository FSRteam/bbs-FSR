package mchorse.bbs_mod.ui.film.view;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.graphics.line.LineBuilder;
import mchorse.bbs_mod.graphics.line.LinePoint;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/** Camera objects are sampled with the world and published only with its successful image. */
public final class FilmCameraMarkers
{
    private List<Marker> pending = List.of();
    private List<Marker> displayed = List.of();
    private Film pendingFilm;
    private Film displayedFilm;
    private long pendingFrame = -1L;

    public void clear()
    {
        this.pending = List.of();
        this.displayed = List.of();
        this.pendingFilm = null;
        this.displayedFilm = null;
        this.pendingFrame = -1L;
    }

    public void sampleWorld(UIFilmPanel panel, UIFilmPreview preview)
    {
        Film film = panel.getData();
        ViewDescriptor view = preview.getViewDescriptor();
        List<Marker> markers = new ArrayList<>();

        this.pendingFrame = BBSRendering.getSceneFrameId();
        this.pendingFilm = film;

        if (film != null && view.isShowCameraObjects())
        {
            float aspect = Math.max(1, BBSSettings.videoSettings.width.get()) / (float) Math.max(1, BBSSettings.videoSettings.height.get());

            this.addCamera(markers, panel, view, Film.LEGACY_CAMERA_ID, aspect);

            for (CameraTrack track : film.cameraTracks.getList())
            {
                String id = track.cameraId.get();

                if (film.getCameraTrack(id) == track)
                {
                    this.addCamera(markers, panel, view, id, aspect);
                }
            }
        }

        this.pending = List.copyOf(markers);
    }

    private void addCamera(List<Marker> markers, UIFilmPanel panel, ViewDescriptor view, String id, float aspect)
    {
        if (view.getNavigation().isInCameraView() && id.equals(view.resolveCameraId()))
        {
            return;
        }

        Position pose = panel.getCameraPoseEvaluator().evaluate(id, panel.getData().getCameraBasePosition(id));

        markers.add(new Marker(id, FilmViewMenus.cameraName(panel.getData(), id), pose,
            FilmCameraMarkerGeometry.corners(pose, aspect, 0.8F)));
    }

    public void publish(ViewRenderState state)
    {
        if (state != null && state.hasFrame() && state.getSampleFrameId() == this.pendingFrame)
        {
            this.displayed = this.pending;
            this.displayedFilm = this.pendingFilm;
        }
    }

    public void renderOverlay(UIFilmPanel panel, UIFilmPreview preview, UIContext context)
    {
        if (!this.canShow(panel, preview))
        {
            return;
        }

        Camera camera = preview.getDisplayedCamera();
        Area area = preview.getViewport();
        ViewFrameGeometry frame = new ViewFrameGeometry(area.x, area.y, area.w, area.h);

        this.renderFrusta(panel, camera, frame, context);

        for (Marker marker : this.displayed)
        {
            FilmCameraMarkerGeometry.ScreenPoint point = FilmCameraMarkerGeometry.project(marker.pose, camera, frame);

            if (point == null || !frame.contains((int) point.x(), (int) point.y(), preview.area.x, preview.area.y, preview.area.w, preview.area.h))
            {
                continue;
            }

            int color = marker.id.equals(panel.getEditedCameraId()) ? BBSSettings.primaryColor(Colors.A100) : BBSSettings.textColor();

            context.batcher.icon(Icons.VIDEO_CAMERA, color, point.x(), point.y(), 0.5F, 0.5F);

            if (preview.getViewDescriptor().isShowName())
            {
                context.batcher.textCard(marker.name, (int) point.x() + 10, (int) point.y() - 4, color, Colors.A50);
            }
        }
    }

    private void renderFrusta(UIFilmPanel panel, Camera camera, ViewFrameGeometry frame, UIContext context)
    {
        LineBuilder<Integer> lines = new LineBuilder<>(FilmCameraMarkerGeometry.LINE_HALF_WIDTH);
        Vector3f origin = new Vector3f();

        for (Marker marker : this.displayed)
        {
            int color = marker.id.equals(panel.getEditedCameraId()) ? BBSSettings.primaryColor(Colors.A100) : BBSSettings.mutedTextColor();

            for (int i = 0; i < marker.corners.length; i++)
            {
                Vector3f from = marker.corners[i];
                Vector3f to = marker.corners[(i + 1) % marker.corners.length];

                this.addLine(lines, FilmCameraMarkerGeometry.projectSegment(marker.pose, origin, from, camera, frame), color);
                this.addLine(lines, FilmCameraMarkerGeometry.projectSegment(marker.pose, from, to, camera, frame), color);
            }
        }

        if (lines.lines.isEmpty())
        {
            return;
        }

        List<List<LinePoint<Integer>>> geometry = lines.build();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        try
        {
            context.batcher.flush();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            Matrix4f matrix = context.batcher.getContext().pose().last().pose();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            /* Batch the independent strips into one draw; guides keep a one-pixel UI width. */
            for (List<LinePoint<Integer>> points : geometry)
            {
                for (int i = 2; i < points.size(); i++)
                {
                    this.vertex(builder, matrix, points.get(i - 2));
                    this.vertex(builder, matrix, points.get(i % 2 == 0 ? i - 1 : i));
                    this.vertex(builder, matrix, points.get(i % 2 == 0 ? i : i - 1));
                }
            }

            BufferUploader.drawWithShader(builder.buildOrThrow());
        }
        finally
        {
            RenderSystem.depthFunc(depthFunction);

            if (depthTest)
            {
                RenderSystem.enableDepthTest();
            }
            else
            {
                RenderSystem.disableDepthTest();
            }
        }
    }

    private void addLine(LineBuilder<Integer> lines, FilmCameraMarkerGeometry.ScreenSegment segment, int color)
    {
        if (segment != null)
        {
            lines.push().add(segment.from().x(), segment.from().y(), color).add(segment.to().x(), segment.to().y(), color);
        }
    }

    private void vertex(BufferBuilder builder, Matrix4f matrix, LinePoint<Integer> point)
    {
        builder.addVertex(matrix, point.x, point.y, 0F).setColor(point.user);
    }

    public String pick(UIFilmPanel panel, UIFilmPreview preview, UIContext context)
    {
        if (!this.canShow(panel, preview) || !preview.isInsideFrame(context))
        {
            return null;
        }

        Camera camera = preview.getDisplayedCamera();
        Area area = preview.getViewport();
        ViewFrameGeometry frame = new ViewFrameGeometry(area.x, area.y, area.w, area.h);
        float closest = 144F;
        String result = null;

        for (Marker marker : this.displayed)
        {
            FilmCameraMarkerGeometry.ScreenPoint point = FilmCameraMarkerGeometry.project(marker.pose, camera, frame);

            if (point != null)
            {
                float dx = context.mouseX - point.x();
                float dy = context.mouseY - point.y();
                float distance = dx * dx + dy * dy;

                if (distance < closest && panel.getData().hasCamera(marker.id))
                {
                    result = marker.id;
                    closest = distance;
                }
            }
        }

        return result;
    }

    private boolean canShow(UIFilmPanel panel, UIFilmPreview preview)
    {
        return this.displayedFilm == panel.getData() && this.displayedFilm != null
            && preview.getViewDescriptor().isShowCameraObjects() && !panel.recorder.isExporting();
    }

    private record Marker(String id, String name, Position pose, Vector3f[] corners)
    {}
}
