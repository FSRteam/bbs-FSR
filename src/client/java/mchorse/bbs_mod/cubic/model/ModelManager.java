package mchorse.bbs_mod.cubic.model;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.MolangHelper;
import mchorse.bbs_mod.cubic.model.config.ModelConfig;
import mchorse.bbs_mod.cubic.model.loaders.BOBJModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.CubicModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.GeoCubicModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.JemModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.VoxModelLoader;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.IOUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.pose.PoseManager;
import mchorse.bbs_mod.utils.pose.ShapeKeysManager;
import mchorse.bbs_mod.utils.watchdog.IWatchDogListener;
import mchorse.bbs_mod.utils.watchdog.WatchDogEvent;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class ModelManager implements IWatchDogListener
{
    public static final String MODELS_PREFIX = "models/";

    /**
     * Model loaders an addon added.
     *
     * <p>Suppliers rather than loaders: the list is rebuilt from scratch on every asset reload,
     * so anything appended to it directly would survive exactly until the user saved a file in the
     * assets folder.</p>
     */
    private static final List<Supplier<IModelLoader>> EXTRA_LOADERS = new ArrayList<>();

    public final Map<String, ModelInstance> models = new HashMap<>();

    /** Keys ever queued for the background loader — a failed load is never retried until forgotten. */
    private final Set<String> requested = new HashSet<>();

    public final List<IModelLoader> loaders = new ArrayList<>();
    public final AssetProvider provider;
    public final MolangParser parser;

    private ModelLoader loader = new ModelLoader(this);

    public ModelManager(AssetProvider provider)
    {
        this.provider = provider;
        this.parser = new MolangParser();

        MolangHelper.registerVars(this.parser);

        this.setupLoaders();
    }

    /** Teaches BBS to read a model format of an addon's. */
    public static void registerLoader(Supplier<IModelLoader> loader)
    {
        if (loader != null)
        {
            EXTRA_LOADERS.add(loader);
        }
    }

    /** Removes a previously added loader supplier; returns whether it was there. */
    public static boolean unregisterLoader(Supplier<IModelLoader> loader)
    {
        return loader != null && EXTRA_LOADERS.remove(loader);
    }

    /** The loader suppliers added so far, in registration order. */
    public static List<Supplier<IModelLoader>> getRegisteredLoaders()
    {
        return java.util.Collections.unmodifiableList(EXTRA_LOADERS);
    }

    private void setupLoaders()
    {
        this.loaders.clear();
        this.loaders.add(new BOBJModelLoader());
        this.loaders.add(new CubicModelLoader());
        this.loaders.add(new GeoCubicModelLoader());
        this.loaders.add(new JemModelLoader());
        this.loaders.add(new VoxModelLoader());

        for (Supplier<IModelLoader> extra : EXTRA_LOADERS)
        {
            IModelLoader loader = extra.get();

            if (loader != null)
            {
                this.loaders.add(loader);
            }
        }
    }

    /**
     * Get all models that can be loaded by
     */
    public List<String> getAvailableKeys()
    {
        List<Link> models = new ArrayList<>(BBSMod.getProvider().getLinksFromPath(Link.assets("models"), true));
        Set<String> keys = new HashSet<>();

        models.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));

        for (Link link : models)
        {
            if (this.isRelodable(link))
            {
                String path = link.path;

                int slash = path.indexOf('/');
                int lastSlash = path.lastIndexOf('/');

                if (slash != lastSlash)
                {
                    path = path.substring(slash + 1, lastSlash);

                    keys.add(path);
                }
            }
        }

        return new ArrayList<>(keys);
    }

    public ModelInstance getModel(String id)
    {
        ModelInstance model = this.models.get(id);

        if (model != null)
        {
            return model;
        }

        /* Queued exactly once; a failed load stays in requested and is never retried, which
         * is what the old null-in-the-map marker meant. */
        if (this.requested.add(id))
        {
            this.loader.add(id);
        }

        return null;
    }

    public ModelInstance loadModel(String id)
    {
        ModelInstance model = null;
        Link modelLink = Link.assets(MODELS_PREFIX + id);
        Collection<Link> links = this.provider.getLinksFromPath(modelLink, true);
        MapType config = this.loadConfig(modelLink);

        for (IModelLoader loader : this.loaders)
        {
            model = loader.load(id, this, modelLink, links, config);

            if (model != null)
            {
                break;
            }
        }

        if (model == null)
        {
            System.err.println("Model \"" + id + "\" wasn't loaded properly, or was loaded with no top level groups!");
        }
        else
        {
            System.out.println("Model \"" + id + "\" was loaded!");

            model.setup();
            this.models.put(id, model);
        }

        return model;
    }

    private MapType loadConfig(Link modelLink)
    {
        try (InputStream asset = this.provider.getAsset(modelLink.combine("config.json")))
        {
            String string = IOUtils.readText(asset);

            return (MapType) DataToString.fromString(string);
        }
        catch (Exception e)
        {}

        return null;
    }

    /**
     * Write a model's {@link ModelConfig} back to its {@code config.json}, in the user assets folder
     * ({@code config/bbs/assets/models/<id>/}). For a built-in model served from the jar this forks a
     * user copy that overrides it on the next load. Returns whether the file was written.
     */
    public boolean saveConfig(String id, ModelConfig config)
    {
        return this.saveConfig(id, config.toData().asMap());
    }

    public boolean saveConfig(String id, MapType data)
    {
        File file = this.provider.getFile(Link.assets(MODELS_PREFIX + id).combine("config.json"));

        if (file == null)
        {
            return false;
        }

        file.getParentFile().mkdirs();

        return DataToString.writeSilently(file, data, true);
    }

    public void reload()
    {
        for (ModelInstance model : this.models.values())
        {
            if (model != null)
            {
                model.delete();
            }
        }

        this.models.clear();
        this.requested.clear();
        PoseManager.INSTANCE.clear();
        ShapeKeysManager.INSTANCE.clear();
        this.setupLoaders();
    }

    public boolean isRelodable(Link link)
    {
        if (!link.path.startsWith(MODELS_PREFIX))
        {
            return false;
        }

        if (link.path.contains("/animations/") || link.path.contains("/shapes/"))
        {
            return false;
        }

        return link.path.endsWith(".bbs.json")
            || link.path.endsWith(".geo.json")
            || link.path.endsWith(".bobj")
            || link.path.endsWith(".obj")
            || link.path.endsWith(".animation.json")
            || link.path.endsWith(".jem")
            || link.path.endsWith(".jpm")
            || link.path.endsWith(".vox")
            || link.path.endsWith("/config.json");
    }

    /**
     * Watch dog listener implementation. This is a pretty bad hardcoded
     * solution that would only work for the cubic model loader.
     */
    @Override
    public void accept(Path path, WatchDogEvent event)
    {
        Link link = BBSMod.getProvider().getLink(path.toFile());

        if (link == null)
        {
            return;
        }

        if (!link.path.startsWith(MODELS_PREFIX))
        {
            return;
        }

        String modelPath = link.path.substring(MODELS_PREFIX.length());

        if (this.isRelodable(link))
        {
            /* A model is the folder the file sits in. */
            this.forget(StringUtils.parentPath(modelPath));

            return;
        }

        /* Not a file a loader reads. A deleted model folder arrives exactly this way - by the time the
         * event is handled the path is no longer a directory, so it names the model itself - and without
         * this a model deleted and put back under the same name came back as the copy still in memory,
         * which is what made a rejoin the only way to see it change. */
        this.forget(modelPath);

        for (String key : new ArrayList<>(this.models.keySet()))
        {
            if (key.startsWith(modelPath + "/"))
            {
                this.forget(key);
            }
        }
    }

    /**
     * Drop every model of a folder, so the next request loads them again. For a source BBS does not
     * watch — the models a resource pack serves — where a change arrives as one event for all of them
     * rather than as a file the watchdog saw.
     */
    public void forgetFolder(String prefix)
    {
        for (String key : new ArrayList<>(this.models.keySet()))
        {
            if (key.startsWith(prefix))
            {
                this.forget(key);
            }
        }

        /* A model that failed to load is remembered as requested and never retried, so it has to go
         * too, or a pack that arrives later can never be picked up. */
        for (String key : new ArrayList<>(this.requested))
        {
            if (key.startsWith(prefix))
            {
                this.requested.remove(key);
            }
        }
    }

    /** Drop a model from the cache so the next request loads it from disk again. */
    private void forget(String key)
    {
        if (key.isEmpty())
        {
            return;
        }

        ModelInstance model = this.models.remove(key);

        /* Un-mark it too, or the next getModel would treat the key as already queued and
         * the edited model would never reload. */
        this.requested.remove(key);

        if (model != null)
        {
            model.delete();
        }
    }
}