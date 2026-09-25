package mchorse.bbs_mod.ui.film.view;

import net.minecraft.client.Camera;

/** Keeps picking's world-camera origin stable while later world passes run. */
public final class ViewCameraSnapshot extends Camera
{
    public void capture(Camera camera)
    {
        this.setPosition(camera.getPosition());
        this.setRotation(camera.getYRot(), camera.getXRot());
    }
}
