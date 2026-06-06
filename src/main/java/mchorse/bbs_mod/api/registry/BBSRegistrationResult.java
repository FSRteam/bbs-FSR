package mchorse.bbs_mod.api.registry;

public final class BBSRegistrationResult
{
    private final BBSRegistrationStatus status;
    private final String id;
    private final String reason;
    private final String keptBy;

    private BBSRegistrationResult(BBSRegistrationStatus status, String id, String reason, String keptBy)
    {
        this.status = status;
        this.id = id;
        this.reason = reason;
        this.keptBy = keptBy;
    }

    public static BBSRegistrationResult accepted(String id)
    {
        return new BBSRegistrationResult(BBSRegistrationStatus.ACCEPTED, id, null, null);
    }

    public static BBSRegistrationResult duplicate(String id, String keptBy)
    {
        return new BBSRegistrationResult(BBSRegistrationStatus.DUPLICATE, id, "duplicate id", keptBy);
    }

    public static BBSRegistrationResult rejected(String id, String reason)
    {
        return new BBSRegistrationResult(BBSRegistrationStatus.REJECTED, id, reason, null);
    }

    public static BBSRegistrationResult skipped(String id, String reason)
    {
        return new BBSRegistrationResult(BBSRegistrationStatus.SKIPPED, id, reason, null);
    }

    public static BBSRegistrationResult deferred(String id, String phase)
    {
        return new BBSRegistrationResult(BBSRegistrationStatus.DEFERRED, id, phase, null);
    }

    public BBSRegistrationStatus status()
    {
        return this.status;
    }

    public String id()
    {
        return this.id;
    }

    public String reason()
    {
        return this.reason;
    }

    public String keptBy()
    {
        return this.keptBy;
    }

    public boolean accepted()
    {
        return this.status == BBSRegistrationStatus.ACCEPTED;
    }

    @Override
    public String toString()
    {
        return "BBSRegistrationResult{" +
            "status=" + this.status +
            ", id='" + this.id + '\'' +
            ", reason='" + this.reason + '\'' +
            ", keptBy='" + this.keptBy + '\'' +
            '}';
    }
}
