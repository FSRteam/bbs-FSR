package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.utils.UIFilmUndoHandler;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.lang.reflect.Field;
import java.util.Set;

/** Live edits must carry newly created channel schemas and keep long replays running. */
public final class FilmLiveSyncContractTest
{
    public static void runAll() throws ReflectiveOperationException
    {
        testDuration();
        testSyncQueue();
    }

    private static void testDuration()
    {
        Film film = new Film();
        check(film.calculateDuration() == 0, "An empty film acquired playback time");
        IdleClip legacy = new IdleClip();
        legacy.duration.set(20);
        film.camera.addClip(legacy);
        CameraTrack alternate = film.cameraTracks.addTrack("alternate", "Alternate", new Position());
        IdleClip clip = new IdleClip();
        clip.duration.set(40);
        alternate.clips.addClip(clip);
        Replay replay = film.replays.addReplay();
        replay.keyframes.x.insert(99F, 1D);
        check(film.calculateDuration() == 100, "Actors ended when the camera ended");
        replay.enabled.set(false);
        check(film.calculateDuration() == 40, "Disabled replay extended playback or secondary camera was lost");
        replay.enabled.set(true);
        replay.keyframes.x.removeAll();
        check(film.calculateDuration() == 40, "Secondary camera no longer owns the longer duration");
        Replay another = film.replays.addReplay();
        another.keyframes.y.insert(59.5F, 2D);
        check(film.calculateDuration() == 60, "Fractional final replay key lost its final playback tick");
    }

    @SuppressWarnings("unchecked")
    private static void testSyncQueue() throws ReflectiveOperationException
    {
        Film client = new Film();
        Replay first = client.replays.addReplay();
        Replay second = client.replays.addReplay();
        check(first.enabled.isSynced() && first.keyframes.x.isSynced(), "Replay sync declaration was not inherited");
        check(!client.camera.isSynced() && !client.description.isSynced(), "Replay declaration leaked into camera-only data");

        ProbeHandler handler = new ProbeHandler();
        handler.changed(first.enabled);
        handler.changed(second.enabled);
        Field queueField = UIFilmUndoHandler.class.getDeclaredField("syncData");
        queueField.setAccessible(true);
        Set<BaseValue> queue = (Set<BaseValue>) queueField.get(handler);
        check(queue.size() == 2, "Equal fields at different replay paths merged in the live sync queue");

        first.fp.set(true);
        handler.changed(first.fp);
        check(first.fp.isSynced() && queue.contains(first.fp),
            "First-person replay edits did not enter the shared live sync queue");

        first.keyframes.x.insert(7F, 3D);
        handler.changed(first.keyframes.x.getKeyframes().get(0));
        check(queue.contains(first.keyframes.x), "A replay key edit did not queue its containing channel");

        Film server = new Film();
        server.fromData(client.toData());
        first.fp.set(false);
        server.getRecursively(first.fp.getPath()).fromData(first.fp.toData());
        check(!server.replays.getById(first.getId()).fp.get(),
            "First-person edits did not reach the live server replay by stable path");
        KeyframeChannel<Float> property = first.properties.registerChannel("live_sync:strength", KeyframeFactories.FLOAT);
        property.insert(5F, 0.75F);
        handler.changed(property.getKeyframes().get(0));
        check(queue.contains(first.properties) && !queue.contains(property),
            "A lazy property edit omitted the factory metadata required by the server");
        BaseValue serverValue = server.getRecursively(first.properties.getPath());
        check(serverValue != null, "Stable replay path was not resolvable on the live server copy");
        serverValue.fromData(first.properties.toData());
        Replay received = server.replays.getById(first.getId());
        KeyframeChannel<?> receivedProperty = (KeyframeChannel<?>) received.properties.get("live_sync:strength");
        check(receivedProperty != null && ((Number) receivedProperty.interpolate(5F)).floatValue() == 0.75F,
            "A newly authored property channel did not reach the server's live replay");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ProbeHandler extends UIFilmUndoHandler
    {
        private ProbeHandler()
        {
            super(null);
        }

        private void changed(BaseValue value)
        {
            this.handleValue(value);
        }
    }
}
