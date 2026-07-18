package mchorse.bbs_mod.film;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.manager.BaseManager;
import mchorse.bbs_mod.utils.manager.storage.CompressedDataStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class FilmManager extends BaseManager<Film>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film");

    public FilmManager(Supplier<File> folder)
    {
        super(folder);

        this.backUps = true;
        this.storage = new CompressedDataStorage();
    }

    @Override
    protected Film createData(String id, MapType mapType)
    {
        Film film = new Film();

        if (mapType != null)
        {
            film.fromData(mapType);
        }

        return film;
    }

    /**
     * Read the persisted data without invoking Film or addon clip factories.
     * Client-reachable callers must make their raw authority decision before
     * passing this value to {@link #create(String, MapType)}.
     */
    @Nullable
    public MapType loadRaw(String id)
    {
        try
        {
            File file = this.getFile(id);

            return file == null ? null : this.storage.load(file);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=film.load phase=raw_decode result=rejected error_class={}",
                e.getClass().getName());
        }

        return null;
    }

    /**
     * Load a Film only after a policy has accepted its untyped representation.
     * The preflight is deliberately evaluated before {@code createData}, which
     * is the boundary that can instantiate registered addon ActionClips.
     */
    @Nullable
    public Film load(String id, Predicate<MapType> rawPreflight)
    {
        if (rawPreflight == null)
        {
            return null;
        }

        try
        {
            MapType rawFilm = this.loadRaw(id);

            if (rawFilm == null || !rawPreflight.test(rawFilm))
            {
                return null;
            }

            return this.create(id, rawFilm);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=film.load phase=preflight_or_typed_decode result=rejected error_class={}",
                e.getClass().getName());
        }

        return null;
    }

    @Override
    public Film load(String id)
    {
        return this.load(id, (rawFilm) -> true);
    }

    @Override
    protected String getExtension()
    {
        return ".dat";
    }
}
