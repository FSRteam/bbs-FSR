package mchorse.bbs_mod.items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Guards the production trust-boundary wiring in addition to pure policies. */
public final class GunRuntimeWiringTest
{
    private static final Path GUN_ITEM = Path.of("src/main/java/mchorse/bbs_mod/items/GunItem.java");
    private static final Path PROJECTILE = Path.of("src/main/java/mchorse/bbs_mod/entity/GunProjectileEntity.java");
    private static final Path AUTHORIZED_COMMAND_EXECUTOR = Path.of("src/main/java/mchorse/bbs_mod/actions/AuthorizedCommandExecutor.java");
    private static final Path FILM_ACTION_AUTHORITY_POLICY = Path.of("src/main/java/mchorse/bbs_mod/actions/FilmActionAuthorityPolicy.java");

    public static void main(String[] args)
    {
        runAll();

        System.out.println("GunRuntimeWiringTest passed");
    }

    public static void runAll()
    {
        try
        {
            String item = Files.readString(GUN_ITEM);
            String projectile = Files.readString(PROJECTILE);
            String commandExecutor = Files.readString(AUTHORIZED_COMMAND_EXECUTOR);
            String authorityPolicy = Files.readString(FILM_ACTION_AUTHORITY_POLICY);

            require(item, "GunPropertiesPolicy.isAllowed(properties)", "GunItem runtime policy gate is missing");
            require(item, "InteractionResultHolder.fail(stack)", "GunItem does not fail closed");
            require(item, "ActionCommandContext.currentRequester()", "replay gun requester context is missing");
            require(item, "private static final ThreadLocal<OwnerScope> ACTOR", "gun replay owner cannot distinguish an absent scope from scoped null");
            require(item, "ACTOR.set(new OwnerScope(actor))", "scoped null still falls through to the deprecated global owner");
            require(item, "OwnerScope previous = ACTOR.get()", "nested gun owner scope is not captured");
            require(item, "ACTOR.set(previous)", "nested gun owner scope is not restored");
            require(item, "if (!(player instanceof SuperFakePlayer))", "deprecated replay owner can redirect a real player's interaction");
            require(item, "OwnerScope scope = ACTOR.get()", "explicit replay owner scope is not consulted");
            require(item, "scopedActor == null", "explicit non-actor replay scope is not represented");
            require(item, "owner.level() == level && !owner.isRemoved()", "cross-level or removed gun owner is accepted");
            require(item, "GunPropertiesPolicy.isUseAllowed(",
                "GunItem does not reject command-bearing use by a non-admin");
            require(item, "player.hasPermissions(PermissionUtils.ADMIN_PERMISSION_LEVEL)",
                "client hand/launch prediction ignores its server-synchronized permission level");
            require(item, "AuthorizedCommandExecutor.isAuthorized(requester, level.getServer())",
                "GunItem command-bearing use is not bound to current server authority");
            reject(item, "scopedActor == null ? (actor == null ? player : actor) : scopedActor",
                "scoped null still inherits the deprecated global actor");
            require(item, "GunProjectileBudget.RUNTIME.tryReserve(requester.getUUID())", "GunItem active reservation is missing");
            require(item, "projectile.setBudgetLease(lease)", "projectile lease attachment is missing");
            require(item, "lease.attachCleanup(projectile::discard)", "logout/reset projectile cleanup is missing");
            require(item, "if (!lease.isActive())", "cancelled projectile lease can still spawn");
            require(item, "added = level.addFreshEntity(projectile)", "successful projectile insertion is not tracked");
            require(item, "if (!added)", "failed projectile insertion does not reach release logic");
            require(item, "lease.close()", "GunItem exception/failure path does not release its lease");
            require(item, "projectile.setCommandRequester(requester)", "real command requester propagation is missing");
            require(item, "AuthorizedCommandExecutor.execute(", "GunItem command authorization is missing");
            reject(item, "performPrefixedCommand", "GunItem bypasses the authorized command executor");

            require(projectile, "GunPropertiesPolicy.isAllowed(this.properties)", "projectile tick policy gate is missing");
            require(projectile, "this.discard()", "invalid projectile is not discarded");
            require(projectile, "private ServerPlayer commandRequester", "projectile does not capture the exact requester connection");
            require(projectile, "AuthorizedCommandExecutor.isCurrentPlayer(requester, this.getServer())",
                "projectile accepts a stale requester at creation");
            require(projectile, "this.commandRequester = requester", "projectile stores a derived requester identity instead of the connection instance");
            require(projectile, "ServerPlayer requester = this.commandRequester", "terminal projectile command loses the captured requester during removal");
            require(projectile, "private void impact(ServerPlayer requester)", "block impact cannot preserve its requester across vanish removal");
            require(projectile, "this.impact(requester)", "block vanish drops the following impact command requester");
            requireOrderedWithin(
                projectile,
                "if (this.lifeLeft >= this.properties.lifeSpan)",
                "/* Movement code */",
                "this.vanish();",
                "return;",
                "expired projectile continues physics/collision after terminal removal"
            );
            require(projectile, "lease.close()", "projectile removal does not release its active lease");
            require(projectile, "AuthorizedCommandExecutor.execute(", "projectile command authorization is missing");
            reject(projectile, "getPlayerList().getPlayer(this.commandRequester)",
                "same-UUID replacement connection can inherit an old projectile command");
            reject(projectile, "private UUID commandRequester", "projectile retains only requester UUID instead of exact connection identity");
            reject(projectile, "performPrefixedCommand", "projectile bypasses the authorized command executor");
            reject(projectile, "protected int getPermissionLevel()", "projectile still grants itself command permission level 2");

            require(commandExecutor, "!isAuthorized(requester)", "command execution does not dynamically revalidate requester authority");
            require(commandExecutor, "level.getServer() != server", "command anchor can cross into a different server");
            require(authorityPolicy, "getPlayer(requester.getUUID()) == requester",
                "same-UUID replacement connection can inherit authority from the captured requester");
        }
        catch (IOException e)
        {
            throw new AssertionError("Could not inspect gun runtime wiring", e);
        }
    }

    private static void require(String source, String expected, String message)
    {
        check(source.contains(expected), message);
    }

    private static void reject(String source, String forbidden, String message)
    {
        check(!source.contains(forbidden), message);
    }

    private static void requireOrderedWithin(
        String source,
        String start,
        String end,
        String first,
        String second,
        String message
    )
    {
        int startIndex = source.indexOf(start);
        int endIndex = startIndex < 0 ? -1 : source.indexOf(end, startIndex);

        check(startIndex >= 0 && endIndex > startIndex, message);

        String section = source.substring(startIndex, endIndex);
        int firstIndex = section.indexOf(first);
        int secondIndex = section.indexOf(second, firstIndex + first.length());

        check(firstIndex >= 0 && secondIndex > firstIndex, message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
