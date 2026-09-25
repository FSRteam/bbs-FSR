package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.client.render.view.UnshadedTerrainProgram;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/** Exercises the production source reader with separate BBS and Sodium resource owners. */
public final class UnshadedShaderResourceTest
{
    private static final String VERTEX_PATH = "assets/bbs/shaders/camera/unshaded_terrain.vsh";
    private static final String SODIUM_LOADER = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader";
    private static final String SODIUM_CONSTANTS = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants";

    private UnshadedShaderResourceTest()
    {}

    public static void runAll() throws IOException
    {
        URL bundled = UnshadedTerrainProgram.class.getResource("/" + VERTEX_PATH);

        check(bundled != null, "the client output must include its bundled terrain shader");

        String location = bundled.toExternalForm();
        URL resourceRoot = new URL(location.substring(0, location.length() - VERTEX_PATH.length()));
        ClassLoader parent = UnshadedShaderResourceTest.class.getClassLoader();
        URL bbsCode = UnshadedTerrainProgram.class.getProtectionDomain().getCodeSource().getLocation();

        try
        {
            Class<?> sodiumClass = Class.forName(SODIUM_LOADER, false, parent);
            Class<?> constantsType = Class.forName(SODIUM_CONSTANTS, true, parent);
            URL sodiumCode = sodiumClass.getProtectionDomain().getCodeSource().getLocation();

            try (OwnedShaderLoader sodium = new OwnedShaderLoader(new URL[] {sodiumCode},
                     SODIUM_LOADER, "assets/sodium/", parent);
                 OwnedShaderLoader bbs = new OwnedShaderLoader(new URL[] {bbsCode, resourceRoot},
                     UnshadedTerrainProgram.class.getName(), "assets/bbs/", sodium))
            {
                ResourceLocation shader = ResourceLocation.fromNamespaceAndPath("bbs", "camera/unshaded_terrain.vsh");
                Class<?> sodiumType = Class.forName(SODIUM_LOADER, true, sodium);
                Method sodiumRead = sodiumType.getMethod("getShaderSource", ResourceLocation.class);

                try
                {
                    sodiumRead.invoke(null, shader);
                    throw new AssertionError("the fixture must reproduce Sodium's foreign-resource lookup failure");
                }
                catch (InvocationTargetException expected)
                {
                    check(expected.getCause() instanceof RuntimeException
                            && expected.getCause().getMessage().contains("Shader not found"),
                        "the foreign lookup must fail specifically because the BBS resource is isolated");
                }

                Class<?> program = Class.forName(UnshadedTerrainProgram.class.getName(), true, bbs);
                Method read = program.getDeclaredMethod("loadVertexSource", constantsType);
                Object constants = constantsType.getMethod("builder").invoke(null);

                constants.getClass().getMethod("add", String.class).invoke(constants, "BBS_SHADER_RESOURCE_TEST");
                read.setAccessible(true);

                String source = (String) read.invoke(null, constants.getClass().getMethod("build").invoke(constants));

                check(source.startsWith("#version 330 core"), "the BBS owner must load the actual bundled GLSL source");
                check(source.contains("#define BBS_SHADER_RESOURCE_TEST"), "the terrain program must retain Sodium's variant constants");
                check(!source.contains("#import"), "Sodium's includes must still resolve through their owning loader");
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IOException("Could not verify isolated terrain shader resources", exception);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class OwnedShaderLoader extends URLClassLoader
    {
        private final String owner;
        private final String resources;

        private OwnedShaderLoader(URL[] urls, String owner, String resources, ClassLoader parent)
        {
            super(urls, parent);
            this.owner = owner;
            this.resources = resources;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
        {
            if (!this.owner.equals(name))
            {
                return super.loadClass(name, resolve);
            }

            synchronized (this.getClassLoadingLock(name))
            {
                Class<?> loaded = this.findLoadedClass(name);

                if (loaded == null)
                {
                    loaded = this.findClass(name);
                }

                if (resolve)
                {
                    this.resolveClass(loaded);
                }

                return loaded;
            }
        }

        @Override
        public URL getResource(String name)
        {
            if (name.startsWith("assets/"))
            {
                return name.startsWith(this.resources) ? this.findResource(name) : null;
            }

            return super.getResource(name);
        }
    }
}
