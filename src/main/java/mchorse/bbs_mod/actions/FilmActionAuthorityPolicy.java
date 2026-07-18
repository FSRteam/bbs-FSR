package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Central authority boundary for server-side film actions and player state. */
public final class FilmActionAuthorityPolicy
{
    private static final String VISUAL_ACTION_TYPE = "bbs:swipe";

    private FilmActionAuthorityPolicy()
    {}

    public static boolean requiresAdministrator(@Nullable Film film)
    {
        if (film == null)
        {
            return true;
        }

        try
        {
            for (Replay replay : film.replays.getList())
            {
                if (replay == null || replay.actions == null)
                {
                    return true;
                }

                for (Clip clip : replay.actions.get())
                {
                    if (requiresAdministrator(clip))
                    {
                        return true;
                    }
                }
            }

            return false;
        }
        catch (RuntimeException e)
        {
            return true;
        }
    }

    public static boolean requiresAdministrator(@Nullable Clip clip)
    {
        /* This exact-class allowlist is deliberately closed. A subclass can
         * add server effects despite inheriting SwipeActionClip's marker. */
        return clip == null || clip.getClass() != SwipeActionClip.class;
    }

    public static boolean requiresAdministrator(@Nullable Film film, @Nullable MapType rawFilm)
    {
        if (film == null || requiresAdministrator(rawFilm))
        {
            return true;
        }

        try
        {
            BaseType rawReplaysValue = rawFilm.get("replays");

            if (!(rawReplaysValue instanceof ListType rawReplays))
            {
                return true;
            }

            java.util.List<Replay> typedReplays = film.replays.getList();

            if (rawReplays.size() != typedReplays.size())
            {
                return true;
            }

            for (int i = 0; i < rawReplays.size(); i++)
            {
                BaseType rawReplayValue = rawReplays.get(i);
                Replay typedReplay = typedReplays.get(i);

                if (!(rawReplayValue instanceof MapType rawReplay) || typedReplay == null)
                {
                    return true;
                }

                BaseType rawActionsValue = rawReplay.get("actions");
                int rawActionCount = 0;

                if (rawActionsValue != null)
                {
                    if (!(rawActionsValue instanceof ListType rawActions))
                    {
                        return true;
                    }

                    rawActionCount = rawActions.size();
                }

                if (rawActionCount != typedReplay.actions.size())
                {
                    return true;
                }
            }

            return requiresAdministrator(film);
        }
        catch (RuntimeException e)
        {
            return true;
        }
    }

    public static boolean requiresAdministrator(@Nullable MapType rawFilm)
    {
        if (rawFilm == null)
        {
            return true;
        }

        try
        {
            BaseType rawReplaysValue = rawFilm.get("replays");

            if (!(rawReplaysValue instanceof ListType rawReplays))
            {
                return true;
            }

            for (BaseType rawReplayValue : rawReplays)
            {
                if (!(rawReplayValue instanceof MapType rawReplay))
                {
                    return true;
                }

                BaseType rawActionsValue = rawReplay.get("actions");

                if (rawActionsValue == null)
                {
                    continue;
                }

                if (!(rawActionsValue instanceof ListType rawActions))
                {
                    return true;
                }

                for (BaseType rawActionValue : rawActions)
                {
                    if (!(rawActionValue instanceof MapType rawAction)
                        || !VISUAL_ACTION_TYPE.equals(rawAction.getString("type", null)))
                    {
                        return true;
                    }
                }
            }

            return false;
        }
        catch (RuntimeException e)
        {
            return true;
        }
    }

    /**
     * Load persisted Film data through the raw action envelope before any
     * registered clip factory can run. An authorized requester may load every
     * registered action; an ordinary editor may load exact built-in Swipe data
     * only.
     */
    @Nullable
    public static Film loadFilmForRequester(
        @Nullable FilmManager films,
        @Nullable String id,
        boolean requesterAuthorized
    )
    {
        if (films == null || id == null)
        {
            return null;
        }

        try
        {
            return films.exists(id)
                ? films.load(id, (rawFilm) -> requesterAuthorized || !requiresAdministrator(rawFilm))
                : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Project a partial sync request onto a raw Film snapshot before invoking
     * the target value's fromData method. This protects broad Film/replay/action
     * replacements as well as leaf edits under an existing action clip.
     */
    public static boolean isRawMutationAllowedForNonAdministrator(
        @Nullable Film film,
        @Nullable BaseValue target,
        @Nullable DataPath requestedPath,
        @Nullable BaseType replacement
    )
    {
        if (film == null || target == null || requestedPath == null || replacement == null)
        {
            return false;
        }

        try
        {
            List<String> canonicalPath = target.getPathSegments();

            if (canonicalPath.isEmpty()
                || canonicalPath.size() > 256
                || !canonicalPath.equals(requestedPath.strings)
                || !canonicalPath.get(0).equals(film.getId())
                || !hasSafeExistingActionAncestor(target))
            {
                return false;
            }

            BaseType rawFilm = film.toData();

            if (!(rawFilm instanceof MapType))
            {
                return false;
            }

            BaseType candidate = replaceRawValue(rawFilm, canonicalPath, 1, replacement);

            return candidate instanceof MapType map && !requiresAdministrator(map);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean hasSafeExistingActionAncestor(BaseValue target)
    {
        BaseValue value = target;

        while (value != null)
        {
            if (value instanceof Clip clip)
            {
                BaseValue parent = clip.getParent();

                if (parent instanceof Clips && "actions".equals(parent.getId()))
                {
                    return clip.getClass() == SwipeActionClip.class;
                }

                return true;
            }

            value = value.getParent();
        }

        return true;
    }

    @Nullable
    private static BaseType replaceRawValue(
        BaseType current,
        List<String> path,
        int index,
        BaseType replacement
    )
    {
        if (index >= path.size())
        {
            return replacement.copy();
        }

        String segment = path.get(index);

        if (current instanceof MapType map)
        {
            if (index == path.size() - 1)
            {
                MapType copy = (MapType) map.copy();

                copy.put(segment, replacement.copy());

                return copy;
            }

            BaseType child = map.get(segment);

            if (child == null)
            {
                return null;
            }

            BaseType next = replaceRawValue(child, path, index + 1, replacement);

            if (next == null)
            {
                return null;
            }

            MapType copy = (MapType) map.copy();

            copy.put(segment, next);

            return copy;
        }

        if (current instanceof ListType list)
        {
            int listIndex;

            try
            {
                listIndex = Integer.parseInt(segment);
            }
            catch (NumberFormatException e)
            {
                return null;
            }

            BaseType child = list.get(listIndex);

            if (child == null)
            {
                return null;
            }

            BaseType next = replaceRawValue(child, path, index + 1, replacement);

            if (next == null)
            {
                return null;
            }

            ListType copy = (ListType) list.copy();

            copy.elements.set(listIndex, next);

            return copy;
        }

        return null;
    }

    public static boolean hasRequiredAuthority(@Nullable Film film, @Nullable ServerPlayer requester)
    {
        return !requiresAdministrator(film) || isRequesterAuthorized(requester);
    }

    public static boolean isRequesterAuthorized(@Nullable ServerPlayer requester)
    {
        if (requester == null || requester instanceof SuperFakePlayer)
        {
            return false;
        }

        try
        {
            MinecraftServer server = requester.getServer();
            boolean requesterPresent = server != null;
            boolean sameConnection = requesterPresent
                && server.getPlayerList().getPlayer(requester.getUUID()) == requester;
            boolean hasPermission = sameConnection
                && requester.createCommandSourceStack().hasPermission(2);

            return isRequesterAuthorized(requesterPresent, sameConnection, hasPermission);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    public static boolean isRequesterAuthorized(@Nullable ServerPlayer requester, @Nullable MinecraftServer expectedServer)
    {
        if (expectedServer == null)
        {
            return false;
        }

        try
        {
            return requester != null
                && requester.getServer() == expectedServer
                && isRequesterAuthorized(requester);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    public static boolean isRequesterAuthorized(boolean requesterPresent, boolean sameConnection, boolean hasPermission)
    {
        return requesterPresent && sameConnection && hasPermission;
    }

    public static boolean canApplyFirstPersonState(
        boolean capability,
        PlayerType type,
        boolean targetPresent,
        boolean requesterAuthorized
    )
    {
        return capability
            && type != null
            && type.appliesFirstPersonState()
            && targetPresent
            && requesterAuthorized;
    }

    public static boolean hasRuntimeAuthority(
        boolean filmRequiresAdministrator,
        boolean firstPersonStateApplied,
        boolean requesterAuthorized
    )
    {
        return (!filmRequiresAdministrator && !firstPersonStateApplied) || requesterAuthorized;
    }

}
