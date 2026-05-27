package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionCollision;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

public class UIParticleSchemeCollisionSection extends UIParticleSchemeComponentSection<ParticleComponentMotionCollision>
{
    public UIToggle enabled;
    public UIToggle realisticCollision;
    public UIToggle entityCollision;
    public UIToggle momentum;
    public UIToggle realisticCollisionDrag;
    public UITrackpad drag;
    public UITrackpad bounciness;
    public UITrackpad randomBounciness;
    public UITrackpad rotationDrag;
    public UITrackpad radius;
    public UIToggle expire;
    public UITextbox expirationDelay;
    public UIToggle preserveEnergy;
    public UITrackpad damp;
    public UITrackpad randomDamp;
    public UITrackpad splitCount;
    public UITrackpad splitThreshold;

    private boolean wasPresent;

    public UIParticleSchemeCollisionSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.enabled = new UIToggle(UIKeys.SNOWSTORM_COLLISION_ENABLED, (b) -> this.editor.dirty());
        this.realisticCollision = new UIToggle(UIKeys.SNOWSTORM_COLLISION_REALISTIC, (b) ->
        {
            this.component.realisticCollision = b.getValue();
            this.editor.dirty();
        });
        this.entityCollision = new UIToggle(UIKeys.SNOWSTORM_COLLISION_ENTITY, (b) ->
        {
            this.component.entityCollision = b.getValue();
            this.editor.dirty();
        });
        this.momentum = new UIToggle(UIKeys.SNOWSTORM_COLLISION_MOMENTUM, (b) ->
        {
            this.component.momentum = b.getValue();
            this.editor.dirty();
        });
        this.realisticCollisionDrag = new UIToggle(UIKeys.SNOWSTORM_COLLISION_REALISTIC_DRAG, (b) ->
        {
            this.component.realisticCollisionDrag = b.getValue();
            this.editor.dirty();
        });
        this.drag = new UITrackpad((value) ->
        {
            this.component.collisionDrag = value.floatValue();
            this.editor.dirty();
        });
        this.drag.tooltip(UIKeys.SNOWSTORM_COLLISION_DRAG);
        this.bounciness = new UITrackpad((value) ->
        {
            this.component.bounciness = value.floatValue();
            this.editor.dirty();
        });
        this.bounciness.tooltip(UIKeys.SNOWSTORM_COLLISION_BOUNCINESS);
        this.randomBounciness = new UITrackpad((value) ->
        {
            this.component.randomBounciness = Math.abs(value.floatValue());
            this.editor.dirty();
        });
        this.randomBounciness.tooltip(UIKeys.SNOWSTORM_COLLISION_RANDOM_BOUNCINESS);
        this.rotationDrag = new UITrackpad((value) ->
        {
            this.component.rotationCollisionDrag = value.floatValue();
            this.editor.dirty();
        });
        this.rotationDrag.tooltip(UIKeys.SNOWSTORM_COLLISION_ROTATION_DRAG);
        this.radius = new UITrackpad((value) ->
        {
            this.component.radius = value.floatValue();
            this.editor.dirty();
        });
        this.radius.tooltip(UIKeys.SNOWSTORM_COLLISION_RADIUS);
        this.expire = new UIToggle(UIKeys.SNOWSTORM_COLLISION_EXPIRE, (b) ->
        {
            this.component.expireOnImpact = b.getValue();
            this.editor.dirty();
        });
        this.expirationDelay = new UITextbox(10000, (str) ->
        {
            this.component.expirationDelay = this.parse(str, this.component.expirationDelay);
            this.editor.dirty();
        });
        this.expirationDelay.tooltip(UIKeys.SNOWSTORM_COLLISION_EXPIRATION_DELAY);
        this.preserveEnergy = new UIToggle(UIKeys.SNOWSTORM_COLLISION_PRESERVE_ENERGY, (b) ->
        {
            this.component.preserveEnergy = b.getValue();
            this.editor.dirty();
        });
        this.damp = new UITrackpad((value) ->
        {
            this.component.damp = value.floatValue();
            this.editor.dirty();
        });
        this.damp.tooltip(UIKeys.SNOWSTORM_COLLISION_DAMP);
        this.damp.limit(0, 1);
        this.randomDamp = new UITrackpad((value) ->
        {
            this.component.randomDamp = Math.abs(value.floatValue());
            this.editor.dirty();
        });
        this.randomDamp.tooltip(UIKeys.SNOWSTORM_COLLISION_RANDOM_DAMP);
        this.randomDamp.limit(0, 1);
        this.splitCount = new UITrackpad((value) ->
        {
            this.component.splitParticleCount = (int) Math.abs(value);
            this.editor.dirty();
        });
        this.splitCount.tooltip(UIKeys.SNOWSTORM_COLLISION_SPLIT_COUNT);
        this.splitCount.limit(0, 99).integer();
        this.splitThreshold = new UITrackpad((value) ->
        {
            this.component.splitParticleSpeedThreshold = value.floatValue();
            this.editor.dirty();
        });
        this.splitThreshold.tooltip(UIKeys.SNOWSTORM_COLLISION_SPLIT_THRESHOLD);

        this.fields.add(this.enabled);
        this.fields.add(this.realisticCollision);
        this.fields.add(this.entityCollision);
        this.fields.add(this.momentum);
        this.fields.add(this.realisticCollisionDrag);
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_DRAG, this.drag));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_BOUNCINESS, this.bounciness));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_RANDOM_BOUNCINESS, this.randomBounciness));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_ROTATION_DRAG, this.rotationDrag));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_RADIUS, this.radius));
        this.fields.add(this.expire);
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_EXPIRATION_DELAY, this.expirationDelay));
        this.fields.add(this.preserveEnergy);
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_DAMP, this.damp));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_RANDOM_DAMP, this.randomDamp));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_SPLIT_COUNT, this.splitCount));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_COLLISION_SPLIT_THRESHOLD, this.splitThreshold));
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_COLLISION_TITLE;
    }

    @Override
    public void beforeSave(ParticleScheme scheme)
    {
        this.component.enabled = this.enabled.getValue() ? MolangParser.ONE : MolangParser.ZERO;
    }

    @Override
    protected ParticleComponentMotionCollision getComponent(ParticleScheme scheme)
    {
        this.wasPresent = this.scheme.get(ParticleComponentMotionCollision.class) != null;

        return scheme.getOrCreate(ParticleComponentMotionCollision.class);
    }

    @Override
    protected void fillData()
    {
        this.enabled.setValue(this.wasPresent);
        this.realisticCollision.setValue(this.component.realisticCollision);
        this.entityCollision.setValue(this.component.entityCollision);
        this.momentum.setValue(this.component.momentum);
        this.realisticCollisionDrag.setValue(this.component.realisticCollisionDrag);
        this.drag.setValue(this.component.collisionDrag);
        this.bounciness.setValue(this.component.bounciness);
        this.randomBounciness.setValue(this.component.randomBounciness);
        this.rotationDrag.setValue(this.component.rotationCollisionDrag);
        this.radius.setValue(this.component.radius);
        this.expire.setValue(this.component.expireOnImpact);
        this.expirationDelay.setText(this.component.expirationDelay == null ? "" : this.component.expirationDelay.toString());
        this.preserveEnergy.setValue(this.component.preserveEnergy);
        this.damp.setValue(this.component.damp);
        this.randomDamp.setValue(this.component.randomDamp);
        this.splitCount.setValue(this.component.splitParticleCount);
        this.splitThreshold.setValue(this.component.splitParticleSpeedThreshold);
    }
}
