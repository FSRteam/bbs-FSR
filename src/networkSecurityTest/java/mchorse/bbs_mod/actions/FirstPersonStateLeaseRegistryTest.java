package mchorse.bbs_mod.actions;

public final class FirstPersonStateLeaseRegistryTest
{
    private FirstPersonStateLeaseRegistryTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("FirstPersonStateLeaseRegistryTest passed");
    }

    public static void runAll()
    {
        FirstPersonStateLeaseRegistry<Object> registry = new FirstPersonStateLeaseRegistry<>();
        Object target = new Object();
        FirstPersonStateLeaseRegistry.Lease<Object> first = registry.create(target);
        FirstPersonStateLeaseRegistry.Lease<Object> second = registry.create(target);

        check(first.acquire(), "the first first-person lease was rejected");
        check(first.acquire(), "reacquiring the same first-person lease was not idempotent");
        check(!second.acquire(), "two runtimes acquired the same first-person target");

        first.release();
        check(!first.isHeld(), "a released first-person lease remained held");
        check(second.acquire(), "a first-person lease was not transferable after release");

        Object equalTargetA = new String("same-target");
        Object equalTargetB = new String("same-target");
        FirstPersonStateLeaseRegistry.Lease<Object> equalA = registry.create(equalTargetA);
        FirstPersonStateLeaseRegistry.Lease<Object> equalB = registry.create(equalTargetB);

        check(equalTargetA.equals(equalTargetB) && equalTargetA != equalTargetB, "the identity fixture is invalid");
        check(equalA.acquire() && equalB.acquire(), "distinct player connections shared an equals-based lease");
        check(!FirstPersonStateLeaseRegistry.<Object>deniedLease().acquire(), "the fail-closed lease granted ownership");

        registry.clear();
        check(!second.isHeld() && !equalA.isHeld() && !equalB.isHeld(), "registry reset retained first-person ownership");
        check(first.acquire(), "registry reset did not make the target acquirable");

        cloneTransferIsAtomicAndConflictSafe();
    }

    private static void cloneTransferIsAtomicAndConflictSafe()
    {
        FirstPersonStateLeaseRegistry<Object> registry = new FirstPersonStateLeaseRegistry<>();
        Object original = new Object();
        Object replacement = new Object();
        FirstPersonStateLeaseRegistry.Lease<Object> owner = registry.create(original);
        FirstPersonStateLeaseRegistry.Lease<Object> replacementContender = registry.create(replacement);

        check(owner.acquire(), "the original player could not acquire its first-person lease");
        check(owner.transfer(original, replacement), "a held lease did not move to the cloned player identity");
        check(owner.isHeld(), "the transferred first-person lease lost ownership");
        check(!replacementContender.acquire(), "the cloned player acquired a second lease during transfer");
        check(registry.create(original).acquire(), "the retired player identity remained leased after transfer");
        check(!owner.transfer(original, new Object()), "a stale expected identity moved the replacement lease");

        owner.release();
        check(replacementContender.acquire(), "the replacement identity stayed fenced after teardown released its lease");

        Object conflictingOriginal = new Object();
        Object conflictingReplacement = new Object();
        FirstPersonStateLeaseRegistry.Lease<Object> source = registry.create(conflictingOriginal);
        FirstPersonStateLeaseRegistry.Lease<Object> destination = registry.create(conflictingReplacement);

        check(source.acquire() && destination.acquire(), "the clone-conflict fixture could not acquire distinct identities");
        check(!source.transfer(conflictingOriginal, conflictingReplacement), "clone transfer stole another runtime's lease");
        check(source.isHeld() && destination.isHeld(), "failed clone transfer corrupted either exact lease owner");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
