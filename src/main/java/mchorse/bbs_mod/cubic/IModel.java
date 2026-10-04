package mchorse.bbs_mod.cubic;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.pose.Pose;

import java.util.Collection;
import java.util.Set;

/**
 * A drivable cubic model. The bone-tree shape queries live on {@link IBoneHierarchy}, which this
 * interface extends (upstream e341fa733 split them out so a vanilla entity rig can sit in the
 * same widgets) — the pose, shape key and animation methods are what make a model more than a
 * skeleton, and a vanilla rig supplies none of them.
 */
public interface IModel extends IBoneHierarchy
{
    public Pose createPose();

    public void resetPose();

    public void applyPose(Pose pose);

    public Set<String> getShapeKeys();

    public String getAnchor();

    public Collection<String> getAllGroupKeys();

    /**
     * The bone that name addresses, or null when this model has no such bone. The one lookup
     * every poser needs: what used to be "is this a cubic model or a BOBJ one, and which of the
     * two bone maps do I reach into" is now this call plus {@link RigBone}.
     */
    public RigBone getBone(String name);

    public Collection<String> getAllChildrenKeys(String key);

    public Collection<ModelGroup> getAllGroups();

    public Collection<BOBJBone> getAllBOBJBones();

    public Collection<String> getAdjacentGroups(String groupName);

    public Collection<String> getHierarchyGroups(String groupName);

    public Collection<String> getRootGroupKeys();

    public Collection<String> getDirectChildrenKeys(String key);

    public String getParentGroupKey(String key);

    public void apply(IEntity target, Animation action, float tick, float blend, float transition, boolean skipInitial);

    public void postApply(IEntity target, Animation action, float tick, float transition);
}