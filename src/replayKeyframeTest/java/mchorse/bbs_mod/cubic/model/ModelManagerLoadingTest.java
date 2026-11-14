package mchorse.bbs_mod.cubic.model;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.resources.AssetProvider;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;

/** Executes the real manager path with a stateful addon loader factory. */
public final class ModelManagerLoadingTest
{
    private ModelManagerLoadingTest()
    {
    }

    public static void runAll() throws ReflectiveOperationException
    {
        Field providerField = BBSMod.class.getDeclaredField("provider");
        providerField.setAccessible(true);
        AssetProvider previousProvider = (AssetProvider) providerField.get(null);
        AssetProvider provider = new AssetProvider();
        List<List<String>> loadsByInstance = new ArrayList<>();
        Supplier<IModelLoader> factory = () ->
        {
            List<String> loads = new ArrayList<>();
            loadsByInstance.add(loads);

            return (id, manager, link, links, config) ->
            {
                loads.add(id);
                return new ModelInstance(id, new Model(manager.parser), null, null)
                {
                    @Override
                    public void setup()
                    {
                    }
                };
            };
        };
        ModelManager.registerLoader(factory);

        try
        {
            providerField.set(null, provider);
            ModelManager manager = new ModelManager(provider);
            ModelInstance first = manager.loadModel("first");
            ModelInstance second = manager.loadModel("second");
            require(first != null && second != null, "addon loader was not consumed by the real manager");
            require(loadsByInstance.stream().filter((loads) -> loads.equals(List.of("first"))).count() == 1
                && loadsByInstance.stream().filter((loads) -> loads.equals(List.of("second"))).count() == 1
                && loadsByInstance.stream().noneMatch((loads) -> loads.size() > 1),
                "stateful addon loader instance leaked from one model load into another");
            require(manager.models.get("first") == first && manager.models.get("second") == second,
                "loaded model publication lost a successful instance");
            require(manager.getModel("first") == first, "published model was queued again");
            require(ModelManager.unregisterLoader(factory), "addon loader factory could not be unregistered");
            require(manager.loadModel("after_unregister") == null,
                "unregistered addon loader was retained in a manager prototype list");
            manager.reload();
            require(manager.models.isEmpty(), "reload retained a published model");
        }
        finally
        {
            ModelManager.unregisterLoader(factory);
            providerField.set(null, previousProvider);
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
