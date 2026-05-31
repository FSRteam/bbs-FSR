package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.events.ParticleComponentEmitterLifetimeEvents;
import mchorse.bbs_mod.particles.components.events.ParticleComponentParticleLifetimeEvents;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionCollision;
import mchorse.bbs_mod.particles.events.ParticleCollisionEvents;
import mchorse.bbs_mod.particles.events.ParticleEventEffect;
import mchorse.bbs_mod.particles.events.ParticleEventNode;
import mchorse.bbs_mod.particles.events.ParticleEventTimeline;
import mchorse.bbs_mod.particles.events.ParticleEventTriggerList;
import mchorse.bbs_mod.particles.events.ParticleLoopingDistanceEvents;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.LinkedHashMap;
import java.util.Map;

public class UIParticleSchemeEventsSection extends UIParticleSchemeSection
{
    private UIElement namedEvents;
    private UIElement emitterTimeline;
    private UIElement emitterDistance;
    private UIElement emitterLoopingDistance;
    private UIElement particleTimeline;

    private ParticleComponentEmitterLifetimeEvents emitterEvents;
    private ParticleComponentParticleLifetimeEvents particleEvents;
    private ParticleComponentMotionCollision collisionEvents;

    public UIParticleSchemeEventsSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.namedEvents = UI.column();
        this.emitterTimeline = UI.column();
        this.emitterDistance = UI.column();
        this.emitterLoopingDistance = UI.column();
        this.particleTimeline = UI.column();
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_EVENTS_TITLE;
    }

    @Override
    public void setScheme(ParticleScheme scheme)
    {
        super.setScheme(scheme);

        this.emitterEvents = scheme.getOrCreate(ParticleComponentEmitterLifetimeEvents.class);
        this.particleEvents = scheme.getOrCreate(ParticleComponentParticleLifetimeEvents.class);
        this.collisionEvents = scheme.getOrCreate(ParticleComponentMotionCollision.class);

        this.rebuild();
    }

    private void rebuild()
    {
        this.fields.removeAll();
        this.namedEvents.removeAll();

        this.fields.add(UI.label(UIKeys.SNOWSTORM_EVENTS_NAMED, 16));

        for (Map.Entry<String, ParticleEventNode> entry : this.scheme.events.entrySet())
        {
            this.namedEvents.add(this.eventEntry(entry.getKey(), entry.getValue()));
        }

        UIIcon add = new UIIcon(Icons.ADD, (b) -> this.addEvent());
        add.tooltip(UIKeys.SNOWSTORM_EVENTS_ADD);
        this.fields.add(this.namedEvents, add);

        this.fields.add(UI.label(UIKeys.SNOWSTORM_EVENTS_EMITTER_TRIGGERS, 16));
        this.fields.add(this.triggerField(UIKeys.SNOWSTORM_EVENTS_CREATION, this.emitterEvents.creationEvent));
        this.fields.add(this.triggerField(UIKeys.SNOWSTORM_EVENTS_EXPIRATION, this.emitterEvents.expirationEvent));
        this.rebuildTimeline(this.emitterTimeline, this.emitterEvents.timeline, UIKeys.SNOWSTORM_EVENTS_TIMELINE, "0.5");
        this.rebuildTimeline(this.emitterDistance, this.emitterEvents.travelDistanceEvents, UIKeys.SNOWSTORM_EVENTS_TRAVEL_DISTANCE, "1");
        this.rebuildLoopingDistance();
        this.fields.add(this.emitterTimeline, this.emitterDistance, this.emitterLoopingDistance);

        this.fields.add(UI.label(UIKeys.SNOWSTORM_EVENTS_PARTICLE_TRIGGERS, 16));
        this.fields.add(this.triggerField(UIKeys.SNOWSTORM_EVENTS_CREATION, this.particleEvents.creationEvent));
        this.fields.add(this.triggerField(UIKeys.SNOWSTORM_EVENTS_EXPIRATION, this.particleEvents.expirationEvent));
        this.rebuildTimeline(this.particleTimeline, this.particleEvents.timeline, UIKeys.SNOWSTORM_EVENTS_TIMELINE, "0.5");
        this.fields.add(this.particleTimeline);

        this.fields.add(UI.label(UIKeys.SNOWSTORM_EVENTS_COLLISION_TRIGGERS, 16));
        this.fields.add(this.collisionTriggerField());

        this.editor.resize();
    }

    private UIElement eventEntry(String id, ParticleEventNode node)
    {
        UIElement column = UI.column();
        UIElement row = UI.row(4);
        UITextbox name = new UITextbox(1000, (str) ->
        {
            this.renameEvent(id, str);
            this.editor.markUndoBoundary();
        }).delayedInput();
        UIIcon remove = new UIIcon(Icons.REMOVE, (b) -> this.removeEvent(id));

        name.setText(id);
        name.tooltip(UIKeys.SNOWSTORM_EVENTS_ID);
        remove.tooltip(UIKeys.SNOWSTORM_EVENTS_REMOVE);

        row.add(name, remove);
        column.add(row, new UIEventNodeEditor(node, false));

        return column;
    }

    private UIElement triggerField(IKey label, ParticleEventTriggerList list)
    {
        UITextbox textbox = new UITextbox(10000, (str) ->
        {
            list.setFromCSV(str);
            this.markDirty();
        }).delayedInput();

        textbox.setText(list.toCSV());

        return this.labeledField(label, textbox);
    }

    private UIElement collisionTriggerField()
    {
        UIElement container = UI.column();

        container.add(UI.label(UIKeys.SNOWSTORM_EVENTS_EVENTS, 16));

        for (ParticleCollisionEvents.Entry entry : this.collisionEvents.collisionEvents.entries)
        {
            UIElement row = UI.row(4);
            UITextbox event = new UITextbox(10000, (str) ->
            {
                entry.event = str == null ? "" : str.trim();
                this.collisionEvents.collisionEvents.markEdited();
                this.markDirty();
            }).delayedInput();
            UITrackpad minSpeed = new UITrackpad((value) ->
            {
                entry.setMinSpeed(value.doubleValue());
                this.collisionEvents.collisionEvents.markEdited();
                this.markDirty();
            });
            UIIcon remove = new UIIcon(Icons.REMOVE, (b) ->
            {
                this.collisionEvents.collisionEvents.entries.remove(entry);
                this.collisionEvents.collisionEvents.markEdited();
                this.markDirty();
                this.rebuild();
            });

            event.setText(entry.event);
            event.tooltip(UIKeys.SNOWSTORM_EVENTS_EVENTS);
            minSpeed.setValue(entry.minSpeed);
            minSpeed.limit(0);
            minSpeed.tooltip(UIKeys.SNOWSTORM_EVENTS_MIN_SPEED);
            remove.tooltip(UIKeys.SNOWSTORM_EVENTS_REMOVE);
            minSpeed.w(58);
            row.add(event, this.labeledField(UIKeys.SNOWSTORM_EVENTS_MIN_SPEED, minSpeed), remove);
            container.add(row);
        }

        UIIcon add = new UIIcon(Icons.ADD, (b) ->
        {
            this.collisionEvents.collisionEvents.add("event", 0);
            this.collisionEvents.enabled = MolangParser.ONE;
            this.markDirty();
            this.rebuild();
        });

        add.tooltip(UIKeys.SNOWSTORM_EVENTS_ADD);
        container.add(add);

        return container;
    }

    private void rebuildTimeline(UIElement container, ParticleEventTimeline timeline, IKey label, String defaultKey)
    {
        container.removeAll();
        container.add(UI.label(label, 16));

        for (ParticleEventTimeline.Entry entry : timeline.entries)
        {
            UIElement row = UI.row(4);
            UITextbox key = new UITextbox(1000, (str) ->
            {
                entry.key = str;
                timeline.markEdited();
                this.markDirty();
            }).delayedInput();
            UITextbox events = new UITextbox(10000, (str) ->
            {
                entry.events.setFromCSV(str);
                timeline.markEdited();
                this.markDirty();
            }).delayedInput();
            UIIcon remove = new UIIcon(Icons.REMOVE, (b) ->
            {
                timeline.entries.remove(entry);
                timeline.markEdited();
                this.markDirty();
                this.rebuild();
            });

            key.setText(entry.key);
            key.tooltip(UIKeys.SNOWSTORM_EVENTS_TIME_OR_DISTANCE);
            events.setText(entry.events.toCSV());
            events.tooltip(UIKeys.SNOWSTORM_EVENTS_EVENTS);
            remove.tooltip(UIKeys.SNOWSTORM_EVENTS_REMOVE);
            key.w(58);
            row.add(key, events, remove);
            container.add(row);
        }

        UIIcon add = new UIIcon(Icons.ADD, (b) ->
        {
            timeline.add(defaultKey);
            this.markDirty();
            this.rebuild();
        });

        add.tooltip(UIKeys.SNOWSTORM_EVENTS_ADD);
        container.add(add);
    }

    private void rebuildLoopingDistance()
    {
        ParticleLoopingDistanceEvents looping = this.emitterEvents.loopingTravelDistanceEvents;

        this.emitterLoopingDistance.removeAll();
        this.emitterLoopingDistance.add(UI.label(UIKeys.SNOWSTORM_EVENTS_LOOPING_DISTANCE, 16));

        for (ParticleLoopingDistanceEvents.Entry entry : looping.entries)
        {
            UIElement row = UI.row(4);
            UITextbox distance = new UITextbox(1000, (str) ->
            {
                entry.setDistance(str);
                looping.markEdited();
                this.markDirty();
            }).delayedInput();
            UITextbox effects = new UITextbox(10000, (str) ->
            {
                entry.effects.setFromCSV(str);
                looping.markEdited();
                this.markDirty();
            }).delayedInput();
            UIIcon remove = new UIIcon(Icons.REMOVE, (b) ->
            {
                looping.entries.remove(entry);
                looping.markEdited();
                this.markDirty();
                this.rebuild();
            });

            distance.setText(entry.distance);
            distance.tooltip(UIKeys.SNOWSTORM_EVENTS_TIME_OR_DISTANCE);
            effects.setText(entry.effects.toCSV());
            effects.tooltip(UIKeys.SNOWSTORM_EVENTS_EVENTS);
            remove.tooltip(UIKeys.SNOWSTORM_EVENTS_REMOVE);
            distance.w(58);
            row.add(distance, effects, remove);
            this.emitterLoopingDistance.add(row);
        }

        UIIcon add = new UIIcon(Icons.ADD, (b) ->
        {
            looping.add(1);
            this.markDirty();
            this.rebuild();
        });

        add.tooltip(UIKeys.SNOWSTORM_EVENTS_ADD);
        this.emitterLoopingDistance.add(add);
    }

    private void addEvent()
    {
        String id = "event";
        int i = 1;

        while (this.scheme.events.containsKey(id))
        {
            id = "event" + i++;
        }

        this.scheme.events.put(id, new ParticleEventNode());
        this.markDirty();
        this.rebuild();
    }

    private void renameEvent(String oldId, String newId)
    {
        newId = newId == null ? "" : newId.trim();

        if (oldId.equals(newId) || newId.isEmpty() || this.scheme.events.containsKey(newId))
        {
            return;
        }

        LinkedHashMap<String, ParticleEventNode> renamed = new LinkedHashMap<>();

        for (Map.Entry<String, ParticleEventNode> entry : this.scheme.events.entrySet())
        {
            renamed.put(entry.getKey().equals(oldId) ? newId : entry.getKey(), entry.getValue());
        }

        this.scheme.events.clear();
        this.scheme.events.putAll(renamed);
        this.replaceTrigger(this.emitterEvents.creationEvent, oldId, newId);
        this.replaceTrigger(this.emitterEvents.expirationEvent, oldId, newId);
        this.replaceTimeline(this.emitterEvents.timeline, oldId, newId);
        this.replaceTimeline(this.emitterEvents.travelDistanceEvents, oldId, newId);
        this.replaceLooping(this.emitterEvents.loopingTravelDistanceEvents, oldId, newId);
        this.replaceTrigger(this.particleEvents.creationEvent, oldId, newId);
        this.replaceTrigger(this.particleEvents.expirationEvent, oldId, newId);
        this.replaceTimeline(this.particleEvents.timeline, oldId, newId);
        this.collisionEvents.collisionEvents.replaceEvent(oldId, newId);
        this.markDirty();
        this.rebuild();
    }

    private void removeEvent(String id)
    {
        this.scheme.events.remove(id);
        this.markDirty();
        this.rebuild();
    }

    private void replaceTimeline(ParticleEventTimeline timeline, String oldId, String newId)
    {
        for (ParticleEventTimeline.Entry entry : timeline.entries)
        {
            this.replaceTrigger(entry.events, oldId, newId);
        }
    }

    private void replaceLooping(ParticleLoopingDistanceEvents looping, String oldId, String newId)
    {
        for (ParticleLoopingDistanceEvents.Entry entry : looping.entries)
        {
            this.replaceTrigger(entry.effects, oldId, newId);
        }
    }

    private void replaceTrigger(ParticleEventTriggerList list, String oldId, String newId)
    {
        boolean changed = false;

        for (int i = 0; i < list.events.size(); i++)
        {
            if (list.events.get(i).equals(oldId))
            {
                list.events.set(i, newId);
                changed = true;
            }
        }

        if (changed)
        {
            list.markEdited();
        }
    }

    private void markDirty()
    {
        this.editor.dirty();
    }

    private class UIEventNodeEditor extends UIElement
    {
        private final ParticleEventNode node;
        private final boolean randomChild;

        UIEventNodeEditor(ParticleEventNode node, boolean randomChild)
        {
            this.node = node;
            this.randomChild = randomChild;

            this.column(4).vertical().stretch();
            this.rebuildNode();
        }

        private void rebuildNode()
        {
            this.removeAll();

            UICirculate type = new UICirculate((b) -> this.setNodeType(b.getValue()));

            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_NONE);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_EXPRESSION);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_LOG);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_PARTICLE);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_SOUND);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_SEQUENCE);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_NODE_RANDOMIZE);
            type.setValue(this.getNodeType());

            if (this.randomChild)
            {
                UITrackpad weight = new UITrackpad((value) ->
                {
                    this.node.weight = value.floatValue();
                    UIParticleSchemeEventsSection.this.markDirty();
                });

                weight.setValue(this.node.weight);
                this.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_WEIGHT, weight));
            }

            this.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_NODE, type));

            if (this.node.expression != null || this.getNodeType() == 1)
            {
                UITextbox expression = new UITextbox(10000, (str) ->
                {
                    this.node.expression = UIParticleSchemeEventsSection.this.parse(str, this.node.expression);
                    UIParticleSchemeEventsSection.this.editor.markUndoBoundary();
                }).delayedInput();

                expression.setText(this.node.expression == null ? "" : this.node.expression.toString());
                this.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_NODE_EXPRESSION, expression));
            }

            if ((this.node.log != null && !this.node.log.isEmpty()) || this.getNodeType() == 2)
            {
                UITextbox log = new UITextbox(10000, (str) ->
                {
                    this.node.log = str;
                    UIParticleSchemeEventsSection.this.markDirty();
                }).delayedInput();

                log.setText(this.node.log);
                this.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_NODE_LOG, log));
            }

            if (this.node.particleEffect != null || this.getNodeType() == 3)
            {
                this.add(this.particleEffectFields());
            }

            if ((this.node.soundEvent != null && !this.node.soundEvent.isEmpty()) || this.getNodeType() == 4)
            {
                UITextbox sound = new UITextbox(10000, (str) ->
                {
                    this.node.soundEvent = str;
                    UIParticleSchemeEventsSection.this.markDirty();
                }).delayedInput();

                sound.setText(this.node.soundEvent);
                this.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_SOUND, sound));
            }

            if (!this.node.sequence.isEmpty() || this.getNodeType() == 5)
            {
                this.add(this.children(this.node.sequence, false));
            }

            if (!this.node.randomize.isEmpty() || this.getNodeType() == 6)
            {
                this.add(this.children(this.node.randomize, true));
            }
        }

        private UIElement particleEffectFields()
        {
            if (this.node.particleEffect == null)
            {
                this.node.particleEffect = new ParticleEventEffect();
            }

            ParticleEventEffect effect = this.node.particleEffect;
            UIElement column = UI.column();
            UITextbox effectId = new UITextbox(10000, (str) ->
            {
                effect.effect = str;
                UIParticleSchemeEventsSection.this.markDirty();
            }).delayedInput();
            UICirculate type = new UICirculate((b) ->
            {
                effect.type = switch (b.getValue())
                {
                    case 1 -> "emitter_bound";
                    case 2 -> "particle";
                    case 3 -> "particle_with_velocity";
                    default -> "emitter";
                };

                UIParticleSchemeEventsSection.this.markDirty();
            });
            UITextbox preExpression = new UITextbox(10000, (str) ->
            {
                effect.preEffectExpression = str.trim().isEmpty() ? null : UIParticleSchemeEventsSection.this.parse(str, effect.preEffectExpression);
                UIParticleSchemeEventsSection.this.editor.markUndoBoundary();
            }).delayedInput();

            type.addLabel(UIKeys.SNOWSTORM_EVENTS_EFFECT_TYPE_EMITTER);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_EFFECT_TYPE_EMITTER_BOUND);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_EFFECT_TYPE_PARTICLE);
            type.addLabel(UIKeys.SNOWSTORM_EVENTS_EFFECT_TYPE_PARTICLE_VELOCITY);
            type.setValue(this.effectTypeIndex(effect.type));
            effectId.setText(effect.effect);
            preExpression.setText(effect.preEffectExpression == null ? "" : effect.preEffectExpression.toString());

            column.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_EFFECT, effectId));
            column.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_EFFECT_TYPE, type));
            column.add(UIParticleSchemeEventsSection.this.labeledField(UIKeys.SNOWSTORM_EVENTS_PRE_EFFECT, preExpression));

            return column;
        }

        private int effectTypeIndex(String type)
        {
            if ("emitter_bound".equals(type)) return 1;
            if ("particle".equals(type)) return 2;
            if ("particle_with_velocity".equals(type)) return 3;

            return 0;
        }

        private UIElement children(java.util.List<ParticleEventNode> children, boolean randomize)
        {
            UIElement column = UI.column();

            for (ParticleEventNode child : children)
            {
                UIElement row = UI.row(4);
                UIIcon remove = new UIIcon(Icons.REMOVE, (b) ->
                {
                    children.remove(child);
                    UIParticleSchemeEventsSection.this.markDirty();
                    this.rebuildNode();
                    UIParticleSchemeEventsSection.this.editor.resize();
                });

                remove.tooltip(UIKeys.SNOWSTORM_EVENTS_REMOVE);
                row.add(remove, new UIEventNodeEditor(child, randomize));
                column.add(row);
            }

            UIIcon add = new UIIcon(Icons.ADD, (b) ->
            {
                children.add(new ParticleEventNode());
                UIParticleSchemeEventsSection.this.markDirty();
                this.rebuildNode();
                UIParticleSchemeEventsSection.this.editor.resize();
            });

            add.tooltip(UIKeys.SNOWSTORM_EVENTS_ADD_CHILD);
            column.add(add);

            return column;
        }

        private int getNodeType()
        {
            if (!this.node.sequence.isEmpty()) return 5;
            if (!this.node.randomize.isEmpty()) return 6;
            if (this.node.particleEffect != null) return 3;
            if (this.node.soundEvent != null && !this.node.soundEvent.isEmpty()) return 4;
            if (this.node.expression != null) return 1;
            if (this.node.log != null && !this.node.log.isEmpty()) return 2;

            return 0;
        }

        private void setNodeType(int type)
        {
            this.node.clearKnownPayload();

            try
            {
                if (type == 1) this.node.expression = UIParticleSchemeEventsSection.this.scheme.parser.parseExpression("0");
                else if (type == 2) this.node.log = "event";
                else if (type == 3) this.node.particleEffect = new ParticleEventEffect();
                else if (type == 4) this.node.soundEvent = "ui.button.click";
                else if (type == 5) this.node.sequence.add(new ParticleEventNode());
                else if (type == 6) this.node.randomize.add(new ParticleEventNode());
            }
            catch (Exception e)
            {
                this.node.expression = MolangParser.ZERO;
            }

            UIParticleSchemeEventsSection.this.markDirty();
            this.rebuildNode();
            UIParticleSchemeEventsSection.this.editor.resize();
        }
    }
}
