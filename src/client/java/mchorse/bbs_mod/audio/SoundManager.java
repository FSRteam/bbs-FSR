package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.IOUtils;
import mchorse.bbs_mod.utils.watchdog.IWatchDogListener;
import mchorse.bbs_mod.utils.watchdog.WatchDogEvent;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Decoded buffer cache plus independently owned OpenAL voices. */
public class SoundManager implements IWatchDogListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundManager.class);
    private static final float PLAYING_DRIFT_SECONDS = 0.05F;
    private static final float PAUSED_DRIFT_SECONDS = 0.001F;

    private final AssetProvider provider;
    private final SoundBackend backend;
    private final Consumer<Runnable> contextExecutor;
    private final BooleanSupplier contextThread;
    private final Map<Link, SoundBuffer> buffers = new HashMap<>();
    /** Buffers removed from the asset cache but awaiting native cleanup. */
    private final IdentityHashMap<SoundBuffer, Boolean> pendingBuffers = new IdentityHashMap<>();
    private final List<SoundPlayer> sounds = new ArrayList<>();
    private final IdentityHashMap<Object, IdentityHashMap<Object, ManagedVoice>> ownedVoices = new IdentityHashMap<>();
    /** Weak identity fence; WeakHashMap would incorrectly use owner.equals(). */
    private final List<WeakReference<Object>> retiredOwners = new ArrayList<>();
    /** Sources whose native cleanup failed and must be retried on the context thread. */
    private final IdentityHashMap<SoundPlayer, Boolean> pendingPlayers = new IdentityHashMap<>();
    private volatile Throwable lastCleanupFailure;
    private volatile Throwable lastPlaybackFailure;

    /** Legacy constructor retained for source and binary compatibility. */
    public SoundManager(AssetProvider provider)
    {
        this(provider, OpenALSoundBackend.INSTANCE,
            command -> Minecraft.getInstance().execute(command),
            () -> Minecraft.getInstance().isSameThread());
    }

    public SoundManager(AssetProvider provider, SoundBackend backend,
                        Consumer<Runnable> contextExecutor, BooleanSupplier contextThread)
    {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.contextExecutor = Objects.requireNonNull(contextExecutor, "contextExecutor");
        this.contextThread = Objects.requireNonNull(contextThread, "contextThread");
    }

    public Collection<SoundPlayer> getPlayers()
    {
        return this.sounds;
    }

    public Throwable getLastCleanupFailure()
    {
        return this.lastCleanupFailure;
    }

    public Throwable getLastPlaybackFailure()
    {
        return this.lastPlaybackFailure;
    }

    public int getOwnedVoiceCount(Object owner)
    {
        IdentityHashMap<Object, ManagedVoice> voices = this.ownedVoices.get(owner);

        return voices == null ? 0 : voices.size();
    }

    public SoundPlayer getOwnedVoice(Object owner, Object clipIdentity)
    {
        IdentityHashMap<Object, ManagedVoice> voices = this.ownedVoices.get(owner);
        ManagedVoice voice = voices == null ? null : voices.get(clipIdentity);

        return voice == null ? null : voice.player;
    }

    /** Load a sound buffer (optionally include a waveform). */
    public SoundBuffer load(Link link, boolean includeWaveform)
    {
        Objects.requireNonNull(link, "link");

        try
        {
            Wave wave = this.readPlayableWave(link);
            Waveform waveform = includeWaveform ? this.generateWaveform(link, wave) : null;
            SoundBuffer buffer = new SoundBuffer(link, wave, waveform, this.backend);
            SoundBuffer previous = this.buffers.put(link, buffer);

            if (previous != null && previous != buffer)
            {
                this.releaseBuffer(previous);
            }

            return buffer;
        }
        catch (AudioDecodeException e)
        {
            this.lastPlaybackFailure = e;
            this.buffers.put(link, null);
            LOGGER.warn("Sound {} could not be decoded: {}", link, e.getMessage());
        }
        catch (Exception e)
        {
            this.lastPlaybackFailure = e;
            this.buffers.put(link, null);
            LOGGER.error("Failed to load sound {}", link, e);
        }

        return null;
    }

    private Wave readPlayableWave(Link link) throws IOException
    {
        Wave wave = AudioReader.read(this.provider, link);

        /* Preserve the decoded mono/stereo layout while adapting unsupported OpenAL widths. */
        if (wave.getFormat().encoding() != PcmEncoding.PCM_U8
            && wave.getFormat().encoding() != PcmEncoding.PCM_S16_LE)
        {
            wave = wave.convertTo16();
        }

        if (wave.getFormat().channels() != 1 && wave.getFormat().channels() != 2)
        {
            throw new UnsupportedAudioFormatException(link.toString(),
                "Only mono and stereo playback are supported");
        }

        return wave;
    }

    private Waveform generateWaveform(Link link, Wave wave)
    {
        Waveform waveform = new Waveform();

        waveform.generate(wave, this.readColorCodes(link), BBSSettings.audioWaveformDensity.get(), 40);

        return waveform;
    }

    public List<ColorCode> readColorCodes(Link link)
    {
        try (InputStream stream = this.provider.getAsset(new Link(link.source, link.path + ".json")))
        {
            String string = IOUtils.readText(stream);
            ListType data = DataToString.listFromString(string);

            if (data != null && !data.isEmpty())
            {
                List<ColorCode> colorCodes = new ArrayList<>();

                for (BaseType type : data)
                {
                    if (!type.isList())
                    {
                        continue;
                    }

                    ColorCode colorCode = new ColorCode();

                    colorCode.fromData(type.asList());
                    colorCodes.add(colorCode);
                }

                if (!colorCodes.isEmpty())
                {
                    return colorCodes;
                }
            }
        }
        catch (IOException ignored)
        {}

        return null;
    }

    public void saveColorCodes(Link link, List<ColorCode> colorCodes)
    {
        File file = this.provider.getFile(link);

        if (file != null)
        {
            ListType data = new ListType();

            for (ColorCode color : colorCodes)
            {
                data.add(color.toData());
            }

            try
            {
                IOUtils.writeText(file, DataToString.toString(data, true));
            }
            catch (IOException e)
            {
                LOGGER.error("Failed to save waveform color codes for {}", link, e);
            }
        }
    }

    public SoundBuffer get(Link link, boolean includeWaveform)
    {
        if (!this.buffers.containsKey(link))
        {
            return this.load(link, includeWaveform);
        }

        SoundBuffer buffer = this.buffers.get(link);

        if (buffer != null && includeWaveform && buffer.getWaveform() == null)
        {
            try
            {
                buffer.setWaveform(this.generateWaveform(link, this.readPlayableWave(link)));
            }
            catch (AudioDecodeException e)
            {
                this.lastPlaybackFailure = e;
                LOGGER.warn("Sound {} waveform decode unavailable: {}", link, e.getMessage());
            }
            catch (Exception e)
            {
                LOGGER.error("Failed to generate waveform for {}", link, e);
            }
        }

        return buffer;
    }

    public SoundPlayer play(Link link)
    {
        this.requireContextThread("play");
        SoundBuffer buffer = this.get(link, false);

        if (buffer != null)
        {
            try
            {
                SoundPlayer player = new SoundPlayer(buffer);

                player.play();
                this.sounds.add(player);

                return player;
            }
            catch (RuntimeException | Error e)
            {
                this.lastPlaybackFailure = e;
                LOGGER.error("Failed to create sound source for {}", link, e);
            }
        }

        return null;
    }

    public SoundPlayer playUnique(Link link)
    {
        this.requireContextThread("playUnique");

        for (SoundPlayer player : this.sounds)
        {
            SoundBuffer buffer = player.getBuffer();

            if (player.isUnique() && buffer != null && Objects.equals(buffer.getId(), link))
            {
                return player;
            }
        }

        SoundBuffer buffer = this.get(link, true);

        if (buffer != null)
        {
            try
            {
                SoundPlayer player = new SoundPlayer(buffer).unique();

                player.setRelative(true);
                player.play();
                this.sounds.add(player);

                return player;
            }
            catch (RuntimeException | Error e)
            {
                this.lastPlaybackFailure = e;
                LOGGER.error("Failed to create unique sound source for {}", link, e);
            }
        }

        return null;
    }

    /**
     * Reconcile one transport owner's desired identity-keyed voices against its actual sources.
     * Absence from desired state releases the old source, including when desired is empty.
     */
    public void reconcile(Object owner, Map<?, VoiceRequest> desired, boolean playing)
    {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(desired, "desired");

        if (this.isOwnerRetired(owner))
        {
            return;
        }

        if (!this.contextThread.getAsBoolean())
        {
            IdentityHashMap<Object, VoiceRequest> copy = new IdentityHashMap<>();
            desired.forEach(copy::put);
            this.contextExecutor.accept(() -> this.reconcile(owner, copy, playing));

            return;
        }

        IdentityHashMap<Object, ManagedVoice> actual = this.ownedVoices.get(owner);

        if (actual == null && desired.isEmpty())
        {
            return;
        }

        if (actual == null)
        {
            actual = new IdentityHashMap<>();
            this.ownedVoices.put(owner, actual);
        }

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

        for (Map.Entry<?, VoiceRequest> entry : desired.entrySet())
        {
            Object clipIdentity = entry.getKey();
            VoiceRequest request = entry.getValue();

            if (clipIdentity == null || request == null || !request.isValid())
            {
                continue;
            }

            ManagedVoice voice = actual.get(clipIdentity);

            if (voice != null && !Objects.equals(voice.link, request.link()))
            {
                if (this.releaseVoice(voice))
                {
                    actual.remove(clipIdentity);
                    voice = null;
                }
                else
                {
                    /* Keep the failed source mapped so a later reconciliation can retry it. */
                    seen.put(clipIdentity, Boolean.TRUE);
                    continue;
                }
            }

            SoundBuffer buffer = voice == null ? this.get(request.link(), false) : voice.player.getBuffer();

            if (voice != null && buffer == null)
            {
                if (this.releaseVoice(voice))
                {
                    actual.remove(clipIdentity);
                    voice = null;
                    buffer = this.get(request.link(), false);
                }
                else
                {
                    seen.put(clipIdentity, Boolean.TRUE);
                    continue;
                }
            }

            if (buffer == null || request.seconds() < 0F || request.seconds() >= buffer.getDuration())
            {
                continue;
            }

            if (voice == null)
            {
                try
                {
                    SoundPlayer player = new SoundPlayer(buffer).managed();

                    player.setRelative(true);
                    voice = new ManagedVoice(request.link(), player);
                    actual.put(clipIdentity, voice);
                    this.sounds.add(player);
                }
                catch (RuntimeException | Error e)
                {
                    this.lastPlaybackFailure = e;
                    LOGGER.error("Failed to create owned sound source for {}", request.link(), e);
                    continue;
                }
            }

            try
            {
                this.applyVoice(voice, request, playing);
                seen.put(clipIdentity, Boolean.TRUE);
            }
            catch (RuntimeException | Error e)
            {
                this.lastPlaybackFailure = e;
                LOGGER.error("Failed to reconcile owned sound source for {}", request.link(), e);
                if (this.releaseVoice(voice))
                {
                    actual.remove(clipIdentity);
                }
                else
                {
                    seen.put(clipIdentity, Boolean.TRUE);
                }
            }
        }

        Iterator<Map.Entry<Object, ManagedVoice>> iterator = actual.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<Object, ManagedVoice> entry = iterator.next();

            if (!seen.containsKey(entry.getKey()))
            {
                if (this.releaseVoice(entry.getValue()))
                {
                    iterator.remove();
                }
            }
        }

        if (actual.isEmpty())
        {
            this.ownedVoices.remove(owner);
        }
    }

    private void applyVoice(ManagedVoice voice, VoiceRequest request, boolean playing)
    {
        SoundPlayer player = voice.player;

        player.setVolume(request.gain());
        float current = player.getPlaybackPosition();
        float difference = Math.abs(request.seconds() - current);
        boolean mustSeek = difference > (playing ? PLAYING_DRIFT_SECONDS : PAUSED_DRIFT_SECONDS);

        if (!player.isPlaying() && playing)
        {
            /* Resume and creation always reconcile the timeline before play. */
            mustSeek = true;
        }

        if (mustSeek)
        {
            player.setPlaybackPosition(request.seconds());
        }

        if (playing)
        {
            if (!player.isPlaying())
            {
                player.play();
            }
        }
        else if (player.isPlaying())
        {
            player.pause();
        }

        voice.expectedSeconds = request.seconds();
    }

    public void releaseOwner(Object owner)
    {
        if (owner == null)
        {
            return;
        }

        /* Fence already-queued off-thread reconciliations before enqueueing teardown. */
        this.retireOwner(owner);

        if (!this.contextThread.getAsBoolean())
        {
            this.contextExecutor.accept(() -> this.releaseOwner(owner));

            return;
        }

        this.retryPendingPlayers();

        IdentityHashMap<Object, ManagedVoice> voices = this.ownedVoices.get(owner);

        if (voices == null)
        {
            this.retryPendingBuffers();
            return;
        }

        for (ManagedVoice voice : new ArrayList<>(voices.values()))
        {
            if (this.releaseVoice(voice))
            {
                voices.entrySet().removeIf(entry -> entry.getValue() == voice);
            }
        }

        if (voices.isEmpty())
        {
            this.ownedVoices.remove(owner);
        }

        this.retryPendingBuffers();
    }

    private synchronized boolean isOwnerRetired(Object owner)
    {
        boolean retired = false;
        Iterator<WeakReference<Object>> iterator = this.retiredOwners.iterator();

        while (iterator.hasNext())
        {
            Object candidate = iterator.next().get();

            if (candidate == null)
            {
                iterator.remove();
            }
            else if (candidate == owner)
            {
                retired = true;
            }
        }

        return retired;
    }

    private synchronized void retireOwner(Object owner)
    {
        if (!this.isOwnerRetired(owner))
        {
            this.retiredOwners.add(new WeakReference<>(owner));
        }
    }

    public void stop(Link link)
    {
        this.requireContextThread("stop");
        this.releasePlayersMatching(buffer -> buffer != null && Objects.equals(buffer.getId(), link));
    }

    /* Updating methods */

    public void update()
    {
        this.requireContextThread("update");
        this.retryPendingPlayers();
        Iterator<SoundPlayer> iterator = this.sounds.iterator();

        while (iterator.hasNext())
        {
            SoundPlayer player = iterator.next();

            if (player.canBeRemoved())
            {
                if (this.deletePlayer(player))
                {
                    iterator.remove();
                }
            }
        }

        this.retryPendingBuffers();
    }

    /** Global reset: all sources are detached/deleted before any cached buffer is deleted. */
    public void deleteSounds()
    {
        if (!this.contextThread.getAsBoolean())
        {
            this.contextExecutor.accept(this::deleteSounds);

            return;
        }

        List<SoundPlayer> players = new ArrayList<>(this.sounds);

        /* Fence every owner before releasing any source, including owners with no active voice. */
        for (Object owner : new ArrayList<>(this.ownedVoices.keySet()))
        {
            this.retireOwner(owner);
        }

        for (SoundPlayer player : players)
        {
            if (this.deletePlayer(player))
            {
                this.sounds.remove(player);
                this.removeOwnedVoice(player);
            }
        }

        this.retryPendingPlayers();

        Iterator<Map.Entry<Link, SoundBuffer>> cached = this.buffers.entrySet().iterator();

        while (cached.hasNext())
        {
            SoundBuffer buffer = cached.next().getValue();

            if (buffer != null)
            {
                this.pendingBuffers.put(buffer, Boolean.TRUE);
            }

            cached.remove();
        }

        this.retryPendingBuffers();
    }

    /** Invalidate every source referencing the asset before deleting its buffer. */
    public void deleteSound(Link audio)
    {
        if (!this.contextThread.getAsBoolean())
        {
            this.contextExecutor.accept(() -> this.deleteSound(audio));

            return;
        }

        SoundBuffer buffer = this.buffers.remove(audio);

        if (buffer == null)
        {
            return;
        }

        this.pendingBuffers.put(buffer, Boolean.TRUE);
        this.releasePlayersMatching(candidate -> candidate == buffer);
        this.retryPendingBuffers();
    }

    private void releaseBuffer(SoundBuffer buffer)
    {
        if (buffer == null)
        {
            return;
        }

        this.pendingBuffers.put(buffer, Boolean.TRUE);
        this.releasePlayersMatching(candidate -> candidate == buffer);
        this.retryPendingBuffers();
    }

    private void releasePlayersMatching(java.util.function.Predicate<SoundBuffer> predicate)
    {
        List<SoundPlayer> matching = new ArrayList<>();

        for (SoundPlayer player : this.sounds)
        {
            if (predicate.test(player.getBuffer()))
            {
                matching.add(player);
            }
        }

        for (SoundPlayer player : matching)
        {
            if (this.deletePlayer(player))
            {
                this.sounds.remove(player);
                this.removeOwnedVoice(player);
            }
        }
    }

    private boolean releaseVoice(ManagedVoice voice)
    {
        if (voice == null)
        {
            return true;
        }

        boolean deleted = this.deletePlayer(voice.player);

        if (deleted)
        {
            this.sounds.remove(voice.player);
        }

        return deleted;
    }

    private void removeOwnedVoice(SoundPlayer player)
    {
        Iterator<Map.Entry<Object, IdentityHashMap<Object, ManagedVoice>>> owners = this.ownedVoices.entrySet().iterator();

        while (owners.hasNext())
        {
            IdentityHashMap<Object, ManagedVoice> voices = owners.next().getValue();
            voices.entrySet().removeIf(entry -> entry.getValue().player == player);

            if (voices.isEmpty())
            {
                owners.remove();
            }
        }
    }

    private boolean deletePlayer(SoundPlayer player)
    {
        if (player == null)
        {
            return true;
        }

        try
        {
            player.delete();
        }
        catch (RuntimeException | Error e)
        {
            this.pendingPlayers.put(player, Boolean.TRUE);
            this.recordCleanupFailure(e);
            LOGGER.error("Failed to delete sound source; cleanup continued", e);
        }

        if (player.isDeleted())
        {
            this.pendingPlayers.remove(player);
        }

        return player.isDeleted();
    }

    private void retryPendingPlayers()
    {
        for (SoundPlayer player : new ArrayList<>(this.pendingPlayers.keySet()))
        {
            if (this.deletePlayer(player))
            {
                this.pendingPlayers.remove(player);
                this.sounds.remove(player);
                this.removeOwnedVoice(player);
            }
        }
    }

    private boolean deleteBuffer(SoundBuffer buffer)
    {
        if (buffer == null)
        {
            return true;
        }

        try
        {
            buffer.delete();
        }
        catch (RuntimeException | Error e)
        {
            this.recordCleanupFailure(e);
            LOGGER.error("Failed to delete sound buffer {}; cleanup continued", buffer.getId(), e);
        }

        return buffer.isCleanupComplete();
    }

    private void retryPendingBuffers()
    {
        Iterator<SoundBuffer> iterator = this.pendingBuffers.keySet().iterator();

        while (iterator.hasNext())
        {
            SoundBuffer buffer = iterator.next();

            if (this.hasSourceReference(buffer))
            {
                continue;
            }

            if (this.deleteBuffer(buffer))
            {
                iterator.remove();
            }
        }
    }

    private boolean hasSourceReference(SoundBuffer buffer)
    {
        for (SoundPlayer player : this.sounds)
        {
            if (player.getBuffer() == buffer)
            {
                return true;
            }
        }

        return false;
    }

    private synchronized void recordCleanupFailure(Throwable failure)
    {
        if (this.lastCleanupFailure == null)
        {
            this.lastCleanupFailure = failure;
        }
        else if (this.lastCleanupFailure != failure)
        {
            this.lastCleanupFailure.addSuppressed(failure);
        }
    }

    private void requireContextThread(String operation)
    {
        if (!this.contextThread.getAsBoolean())
        {
            throw new IllegalStateException("SoundManager." + operation + " must run on the client/OpenAL context thread");
        }
    }

    /* Watch dog listener implementation */

    @Override
    public void accept(Path path, WatchDogEvent event)
    {
        if (path == null)
        {
            return;
        }

        Link link = BBSMod.getProvider().getLink(path.toFile());
        String pathLower = link.path.toLowerCase(java.util.Locale.ROOT);

        if (pathLower.endsWith(".ogg") || pathLower.endsWith(".wav"))
        {
            this.contextExecutor.accept(() -> this.deleteSound(link));
        }
    }

    public record VoiceRequest(Link link, float seconds, float gain)
    {
        public VoiceRequest
        {
            Objects.requireNonNull(link, "link");
        }

        boolean isValid()
        {
            return Float.isFinite(this.seconds) && Float.isFinite(this.gain) && this.gain >= 0F;
        }
    }

    private static final class ManagedVoice
    {
        private final Link link;
        private final SoundPlayer player;
        private float expectedSeconds;

        private ManagedVoice(Link link, SoundPlayer player)
        {
            this.link = link;
            this.player = player;
        }
    }
}
