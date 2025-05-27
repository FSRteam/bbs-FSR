package mchorse.bbs_mod.utils.iris;

import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;

import java.util.ArrayList;
import java.util.List;

public class ShaderCurves
{
    public static void reset()
    {

    }

    public static String processShader(AbsolutePackPath path, String source)
    {
        System.out.println(path.getPathString() + ": " + getDefines(source));

        return source;
    }

    private static List<String> getDefines(String source)
    {
        List<String> sources = new ArrayList<>();
        int index = 0;

        while ((index = source.indexOf("#define", index)) != -1)
        {
            int newLine = source.indexOf("\n", index);

            if (newLine == -1)
            {
                newLine = source.length();
            }

            String define = source.substring(index, newLine);

            sources.add(define);

            index = newLine;
        }

        return sources;
    }
}