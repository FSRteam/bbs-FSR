package mchorse.bbs_mod.utils.iris;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ShaderCurves
{
    public static Map<String, ShaderVariable> variableMap = new HashMap<>();

    public static void reset()
    {
        variableMap.clear();
    }

    public static String processSource(String source)
    {
        if (!BBSSettings.shaderCurvesEnabled.get())
        {
            return source;
        }

        List<ShaderVariable> variables = new ArrayList<>();

        int index = 0;

        while ((index = source.indexOf("#define", index)) != -1)
        {
            int newLine = source.indexOf("\n", index);

            if (newLine == -1)
            {
                newLine = source.length();
            }

            int lastNewLine = source.lastIndexOf('\n', index);
            String define = source.substring(lastNewLine != -1 ? lastNewLine : index, newLine).trim();

            if (!define.startsWith("/") && define.contains("//[") && define.contains("."))
            {
                String[] split = define.split(" ");

                String name = split[1];
                String defaultValue = split[2];
                ShaderVariable variable = new ShaderVariable(name, defaultValue);

                variables.add(variable);
            }

            index = newLine;
        }

        int version = source.indexOf("#version");
        int nextNewLine = source.indexOf('\n', version);
        StringBuilder uniformString = new StringBuilder();

        for (ShaderVariable variable : variables)
        {
            uniformString.append(variable.toUniformDeclaration());
            uniformString.append('\n');

            variableMap.putIfAbsent(variable.name, variable);
        }

        if (!variables.isEmpty())
        {
            String string = "(?<!#define |[_\\w\\d])(" + variables.stream().map((m) -> m.name).collect(Collectors.joining("|")) + ")(?![_\\w\\d])";
            Pattern pattern = Pattern.compile(string);
            Matcher matcher = pattern.matcher(source);
            StringBuffer sb = new StringBuffer();

            while (matcher.find())
            {
                String processed = matcher.group(1);

                matcher.appendReplacement(sb, "bbs_" + processed);
            }

            matcher.appendTail(sb);

            source = sb.toString();

            /* Remove const from variables that have BBS uniforms */
            String removeConst = "(const)( +float.*=.*bbs_.*;)";
            pattern = Pattern.compile(removeConst);
            matcher = pattern.matcher(source);
            sb = new StringBuffer();

            while (matcher.find())
            {
                matcher.appendReplacement(sb, matcher.group(2));
            }

            matcher.appendTail(sb);

            source = sb.toString();
            source = source.substring(0, nextNewLine + 1) + uniformString + source.substring(nextNewLine + 1);
        }

        return source;
    }

    public static void addUniforms(List<CachedUniform> list)
    {
        BBSRendering.addUniforms(list, variableMap);
    }

    public static class ShaderVariable
    {
        public String name = "";
        public String uniformName = "";
        public float defaultValue;
        public Float value;

        public ShaderVariable(String name, String defaultValue)
        {
            this.name = name;
            this.uniformName = "bbs_" + name;
            this.defaultValue = Float.parseFloat(defaultValue);
        }

        public String toUniformDeclaration()
        {
            return "uniform float " + this.uniformName + ";";
        }

        public float getValue()
        {
            if (this.value == null)
            {
                return this.defaultValue;
            }

            float v = this.value;

            this.value = null;

            return v;
        }
    }
}