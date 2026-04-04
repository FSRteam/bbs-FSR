package mchorse.bbs_mod.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Platform-agnostic loader facade.
 * Keep this interface stable with exactly these six methods.
 */
public interface LoaderAccess
{
    Path getGameDir();

    boolean isDevelopmentEnvironment();

    boolean isModLoaded(String modId);

    Optional<String> getModVersion(String modId);

    Optional<Path> getModFile(String modId);

    <T> List<T> getEntrypoints(String key, Class<T> type);
}
