package mchorse.bbs_mod.api.events;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

public interface BBSEventRegistry
{
    BBSRegistrationResult registerSubscriber(Object subscriber);
}
