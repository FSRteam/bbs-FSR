package bbssmokefixture.v1;

import mchorse.bbs_mod.forms.forms.Form;

/**
 * 1.0 smoke fixture form. Its client renderer (registered by {@link ClientPlugin}
 * via {@code BBSPluginClientContext.forms()}) draws a solid red box in the
 * world and in the form list preview, so a tester can add it to a film and
 * immediately see the v1 appearance.
 */
public final class SmokeForm extends Form
{
}
