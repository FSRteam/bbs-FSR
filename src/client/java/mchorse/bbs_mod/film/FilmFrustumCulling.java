package mchorse.bbs_mod.film;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Skips a replay whose generous surroundings lie entirely outside the frustum of the pass that is
 * drawing it (upstream {@code e08d8ac92}).
 *
 * <p>Two things make this safe in FSR's multiview setup. First, the frustum is read as a local from
 * the render context for the pass being drawn — never cached in a field: every view does its own
 * full {@code GameRenderer.renderLevel}, and that call rebuilds {@code LevelRenderer.cullingFrustum}
 * from its own camera and projection, so the event's frustum belongs to that pass. A cached one
 * would follow the primary view into the side viewports and cull what they can see. Second, the
 * cull is conservative: with no frustum, or a form whose anchor places it at a target instead of at
 * its own entity, the replay is drawn.</p>
 *
 * <p>The cull only skips this pass's {@code renderEntity} call. Nothing in the replay, the entity
 * or the controller's maps is touched, so an anchored replay that hangs off a culled one is
 * unaffected — anchors read matrices through the pose pipeline, not through the draw.</p>
 *
 * <p>Known limitation: F3 hands the level renderer a frozen {@code capturedFrustum}, so while the
 * debug overlay is up every pass sees the same frustum and this cull is skewed. It is left as is —
 * it only bites in debug mode, and the alternative is another accessor into the key handler.</p>
 */
public final class FilmFrustumCulling
{
    /**
     * Half-extent of the box a replay is culled by, around its entity. Deliberately generous: a
     * form reaches past its hitbox (trails, particles, scaled models), and a box this large still
     * culls everything a big set keeps far outside the shot.
     */
    public static final double CULL_RADIUS = 32D;

    /**
     * The switch: upstream's {@code frustum_culling} setting (called "performance" there, parked in
     * Misc here next to the other two performance toggles). Read fresh on every use so toggling it
     * takes effect on the next frame, and treated as on while the settings are not initialised yet,
     * matching the config default — turning it off is the only way to see a difference.
     */
    public static boolean isEnabled()
    {
        return BBSSettings.frustumCulling == null || BBSSettings.frustumCulling.get();
    }

    /**
     * Diagnostic mode, off by default: prints the frustum identity and camera of every pass that
     * draws a film, plus the cull decision for every replay in it. That is what tells a human
     * whether each viewport really gets its own frustum (see the R-6 probe), without changing
     * anything about the render.
     *
     * <p>Off rather than on, because nobody has run it here: the three observations it prints are
     * the acceptance test for this file, and they can only be taken on a machine with a display.
     * Turning it on and reading the log is that test; see {@code research/b8-r6-frustum-multiview.md}
     * §6. The decisive one is {@code frustumSeesThisCam} on every viewport's line — all true means
     * each pass really did get a frustum built from its own camera, which is what makes culling by
     * it safe.</p>
     */
    public static boolean PROBE = false;

    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film-frustum");

    /** Ceiling on probe output, so a probe left on by accident cannot flood the log. */
    private static final int PROBE_MAX_LINES = 64;

    private static final Set<String> probedPasses = new LinkedHashSet<>();
    private static final Map<String, Boolean> probedReplays = new HashMap<>();

    private FilmFrustumCulling()
    {}

    /**
     * Whether this replay is entirely off screen for the pass currently being drawn. {@code null}
     * means "no frustum at hand" and never culls.
     */
    public static boolean isCulled(Frustum frustum, IEntity entity)
    {
        if (frustum == null || !isEnabled())
        {
            return false;
        }

        if (BBSRendering.isIrisShadowPass())
        {
            /* A form behind the camera still casts a legitimate shadow. */
            return false;
        }

        Form form = entity.getForm();

        /* An anchored form stands wherever its target does, not at its own entity. */
        if (form == null || hasAnchorTarget(form))
        {
            return false;
        }

        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();

        return !frustum.isVisible(new AABB(
            x - CULL_RADIUS, y - CULL_RADIUS, z - CULL_RADIUS,
            x + CULL_RADIUS, y + CULL_RADIUS, z + CULL_RADIUS
        ));
    }

    /** Whether the form is anchored onto another replay. */
    public static boolean hasAnchorTarget(Form form)
    {
        Anchor anchor = form.anchor.get();

        return anchor != null && anchor.hasTarget();
    }

    /**
     * Diagnostic only. One line per distinct (view, camera, orientation signature). The signature
     * is which of a ring of probe points around the camera the frustum contains, which encodes the
     * frustum's orientation: two viewports whose signatures differ demonstrably hold differently
     * aimed frustums, even when they sit on the very same camera position. That is the evidence
     * that each pass owns its frustum rather than reusing another pass's.
     */
    public static void probePass(Frustum frustum, Camera camera)
    {
        if (!PROBE)
        {
            return;
        }

        String view = BBSRendering.getActiveViewId();
        String cam = camera == null ? "none" : String.format("%.0f,%.0f,%.0f",
            camera.getPosition().x, camera.getPosition().y, camera.getPosition().z);
        String signature = signature(frustum, camera);

        if (probedPasses.size() >= PROBE_MAX_LINES || !probedPasses.add(view + "|" + cam + "|" + signature))
        {
            return;
        }

        String sees = "n/a";

        if (frustum != null && camera != null)
        {
            Vec3 p = camera.getPosition();

            sees = String.valueOf(frustum.isVisible(new AABB(p, p).inflate(1D)));
        }

        LOGGER.info("[R6] pass view={} frustumId={} cam={} seesThisCam={} aim={} secondaryView={} irisShadow={}",
            view, System.identityHashCode(frustum), cam, sees, signature,
            BBSRendering.isSecondaryViewEnabled(), BBSRendering.isIrisShadowPass());
    }

    /**
     * Which of twelve probe points the frustum contains: the camera, eight points on a horizontal
     * ring, and three straight up/down. Encodes aim, so passes that share a camera position but
     * look elsewhere still get different signatures.
     */
    private static String signature(Frustum frustum, Camera camera)
    {
        if (frustum == null || camera == null)
        {
            return "none";
        }

        Vec3 p = camera.getPosition();
        double r = 24D;
        double[][] offsets = {
            {0, 0, 0},
            {r, 0, 0}, {-r, 0, 0}, {0, 0, r}, {0, 0, -r},
            {r * .7, 0, r * .7}, {r * .7, 0, -r * .7}, {-r * .7, 0, r * .7}, {-r * .7, 0, -r * .7},
            {0, r, 0}, {0, -r, 0}, {0, 0, 0},
        };
        StringBuilder builder = new StringBuilder(offsets.length);

        for (double[] o : offsets)
        {
            Vec3 q = p.add(o[0], o[1], o[2]);

            builder.append(frustum.isVisible(new AABB(q, q).inflate(1D)) ? '1' : '0');
        }

        return builder.toString();
    }

    /**
     * Diagnostic only: the cull verdict for one replay, logged whenever it changes for a given view
     * and replay — which is what makes observation ③ readable: an unanchored replay moved behind
     * the main camera must show {@code culled=false} on the side viewport's line.
     */
    public static void probeReplay(Frustum frustum, IEntity entity, boolean culled)
    {
        if (!PROBE || probedReplays.size() >= PROBE_MAX_LINES)
        {
            return;
        }

        String view = BBSRendering.getActiveViewId();
        String key = view + "|" + System.identityHashCode(entity);

        if (Boolean.valueOf(culled).equals(probedReplays.put(key, culled)))
        {
            return;
        }

        Form form = entity.getForm();

        LOGGER.info("[R6] replay view={} entity={},{},{} form={} anchored={} culled={} frustumId={}",
            view,
            String.format("%.1f", entity.getX()), String.format("%.1f", entity.getY()), String.format("%.1f", entity.getZ()),
            form == null ? "null" : form.getClass().getSimpleName(),
            form != null && hasAnchorTarget(form), culled, System.identityHashCode(frustum));
    }
}
