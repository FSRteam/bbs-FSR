package mchorse.bbs_mod.film;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.Replays;
import mchorse.bbs_mod.film.camera.CameraCut;
import mchorse.bbs_mod.film.camera.CameraCuts;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.film.camera.CameraTracks;
import mchorse.bbs_mod.film.camera.FilmCameraResolver;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.ui.ValueStringKeys;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.settings.values.numeric.ValueLong;
import mchorse.bbs_mod.utils.clips.Clips;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.List;

public class Film extends ValueGroup
{
    /** Stable id retained by all films written before multi-camera tracks existed. */
    public static final String LEGACY_CAMERA_ID = FilmCameraResolver.LEGACY_CAMERA_ID;
    private static final Logger LOGGER = LoggerFactory.getLogger(Film.class);

    private boolean editingCameras;

    public final Clips camera = new Clips("camera", BBSMod.getFactoryCameraClips());
    public final CameraTracks cameraTracks = new CameraTracks("camera_tracks");
    public final CameraCuts cameraCuts = new CameraCuts("camera_cuts");
    public final ValueString activeCameraId = new ValueString("active_camera", LEGACY_CAMERA_ID);
    public final Replays replays = new Replays("replays");
    /**
     * Names of replay categories that exist even with no replay assigned (empty groups).
     * Union with {@link Replay#category} on each replay defines all categories in the UI.
     */
    public final ValueStringKeys replayCategoryNames = new ValueStringKeys("replay_categories");

    public final ValueFloat hp = new ValueFloat("hp", 20F);
    public final ValueFloat hunger = new ValueFloat("hunger", 20F);
    public final ValueInt xpLevel = new ValueInt("xp_level", 0);
    public final ValueFloat xpProgress = new ValueFloat("xp_progress", 0F);

    /**
     * Radius (in blocks) around the player within which nearby mobs are captured
     * into separate replays when recording a player replay. 0 disables it.
     */
    public final ValueFloat mobRecordingRadius = new ValueFloat("mob_recording_radius", 0F);
    
    public final ValueString description = new ValueString("description", "");
    /** UTC instant as ISO-8601 ({@link Instant#toString()}), set when the film is first created. */
    public final ValueString createdAt = new ValueString("created_at", "");
    /** UTC instant as ISO-8601, updated at every persisted edit. */
    public final ValueString updatedAt = new ValueString("updated_at", "");
    /** Time spent editing with recent input (excludes AFK idle in the film editor). */
    public final ValueLong timeSpentActive = new ValueLong("time_spent_active", 0L);

    public Film()
    {
        super("");

        this.add(this.camera);
        this.add(this.cameraTracks);
        this.add(this.cameraCuts);
        this.add(this.activeCameraId);
        this.add(this.replays);
        this.add(this.replayCategoryNames);

        this.add(this.hp);
        this.add(this.hunger);
        this.add(this.xpLevel);
        this.add(this.xpProgress);
        this.add(this.mobRecordingRadius);
        
        this.add(this.description);
        this.add(this.createdAt);
        this.add(this.updatedAt);
        this.add(this.timeSpentActive);
    }

    @Override
    public void fromData(BaseType data)
    {
        if (data.isMap())
        {
            /* Loading an old Film into a reused value must not retain cameras
             * from the previously loaded Film. The legacy camera stays in place. */
            if (!data.asMap().has(this.cameraTracks.getId())) this.cameraTracks.fromData(new ListType());
            if (!data.asMap().has(this.cameraCuts.getId())) this.cameraCuts.fromData(new ListType());
            if (!data.asMap().has(this.activeCameraId.getId())) this.activeCameraId.fromData(new StringType(LEGACY_CAMERA_ID));
        }

        super.fromData(data);

        /* Keep the old map available for one pass after ValueGroup has loaded the
         * current representation.  FilmLegacy only adds new channels and never
         * writes the legacy inventory back into the film. */
        FilmLegacy.migrateHotbar(this, data);

        for (FilmCameraResolver.Diagnostic diagnostic : this.getCameraDiagnostics())
        {
            LOGGER.warn("Film '{}' camera reference {}='{}': {}; source data retained", this.getId(),
                diagnostic.path(), diagnostic.cameraId(), diagnostic.reason());
        }
    }

    @Override
    public void preNotify(BaseValue value, int flag)
    {
        if (!this.editingCameras)
        {
            super.preNotify(value, flag);
        }
    }

    @Override
    public void postNotify(BaseValue value, int flag)
    {
        if (!this.editingCameras)
        {
            super.postNotify(value, flag);
        }
    }

    public void stampCreationTimeNow()
    {
        this.createdAt.set(Instant.now().toString());
    }

    public void stampUpdatedTimeNow()
    {
        this.updatedAt.set(Instant.now().toString());
    }

    /**
     * Parse an ISO-8601 instant as stored by {@link #createdAt}/{@link #updatedAt}.
     *
     * @return the parsed instant, or {@code null} for missing or malformed values.
     */
    public static Instant parseTimestamp(String iso)
    {
        if (iso == null || iso.isEmpty())
        {
            return null;
        }

        try
        {
            return Instant.parse(iso);
        }
        catch (DateTimeException e)
        {
            return null;
        }
    }

    /**
     * @return Localized date/time for UI, or {@code null} if missing/legacy films without {@link #createdAt}.
     */
    public static String formatCreatedAtForDisplay(String isoUtc)
    {
        if (isoUtc == null || isoUtc.isEmpty())
        {
            return null;
        }

        try
        {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(Instant.parse(isoUtc).atZone(ZoneId.systemDefault()));
        }
        catch (DateTimeException e)
        {
            return isoUtc;
        }
    }

    public Replay getFirstPersonReplay()
    {
        for (Replay replay : this.replays.getList())
        {
            if (replay.fp.get())
            {
                return replay;
            }
        }

        return null;
    }

    public boolean hasFirstPerson()
    {
        return this.getFirstPersonReplay() != null;
    }

    /** Return a non-legacy track by stable id, or {@code null} for legacy/missing ids. */
    public CameraTrack getCameraTrack(String cameraId)
    {
        return this.cameraTracks.getById(cameraId);
    }

    public boolean hasCamera(String cameraId)
    {
        return LEGACY_CAMERA_ID.equals(cameraId) || this.cameraTracks.containsId(cameraId);
    }

    /** Legacy clips remain the source of truth for the legacy camera. */
    public Clips getCameraClips(String cameraId)
    {
        CameraTrack track = this.getCameraTrack(cameraId);

        return track == null ? this.camera : track.clips;
    }

    public Position getCameraBasePosition(String cameraId)
    {
        CameraTrack track = this.getCameraTrack(cameraId);

        return track == null ? new Position() : track.copyPosition();
    }

    public List<FilmCameraResolver.Diagnostic> getCameraDiagnostics()
    {
        return FilmCameraResolver.diagnose(this);
    }

    public String resolveCameraId(int tick)
    {
        return FilmCameraResolver.resolve(this, tick);
    }

    /** Add a track with a stable id that is never derived from its ValueList position. */
    public CameraTrack addCamera(String name, Position position)
    {
        return this.cameraTracks.addTrack(null, name, position);
    }

    public boolean renameCamera(String cameraId, String name)
    {
        CameraTrack track = this.getCameraTrack(cameraId);

        if (track == null || name == null || name.isBlank() || name.equals(track.name.get()))
        {
            return false;
        }

        track.name.set(name, IValueListener.FLAG_UNMERGEABLE);

        return true;
    }

    public boolean setActiveCamera(String cameraId)
    {
        if (!this.hasCamera(cameraId) || cameraId.equals(this.activeCameraId.get()))
        {
            return false;
        }

        this.activeCameraId.set(cameraId, IValueListener.FLAG_UNMERGEABLE);

        return true;
    }

    /** Remove a track and repair output/cut references without deleting source markers. */
    public boolean removeCamera(String cameraId)
    {
        if (cameraId == null || LEGACY_CAMERA_ID.equals(cameraId) || !this.cameraTracks.containsId(cameraId))
        {
            return false;
        }

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        this.editingCameras = true;

        try
        {
            this.cameraTracks.removeTrack(cameraId);

            if (cameraId.equals(this.activeCameraId.get()))
            {
                this.activeCameraId.set(LEGACY_CAMERA_ID);
            }

            for (CameraCut cut : this.cameraCuts.getList())
            {
                if (cameraId.equals(cut.cameraId.get()))
                {
                    cut.cameraId.set(LEGACY_CAMERA_ID);
                }
            }
        }
        finally
        {
            this.editingCameras = false;
            this.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }

        return true;
    }

    public CameraCut setCameraCut(int tick, String cameraId)
    {
        String resolved = this.hasCamera(cameraId) ? cameraId : LEGACY_CAMERA_ID;

        return this.cameraCuts.setCut(Math.max(0, tick), resolved);
    }

    public boolean removeCameraCut(int tick)
    {
        return this.cameraCuts.removeCut(tick);
    }

    /** Duration is owned by the Film so every output path observes all camera tracks. */
    public int calculateDuration()
    {
        int duration = this.camera.calculateDuration();

        for (CameraTrack track : this.cameraTracks.getList())
        {
            duration = Math.max(duration, track.clips.calculateDuration());
        }

        return duration;
    }
}
