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

    public static final String BRIGHTNESS = "brightness";
    public static final String SUN_ROTATION = "sun_rotation";

    public static void reset()
    {
        variableMap.clear();
    }

    public static void finishLoading()
    {}

    public static String processSource(String source)
    {
        List<String> filter = BBSRendering.getSliderOptions();

        if (!BBSSettings.shaderCurvesEnabled.get())
        {
            return source;
        }

        List<ShaderVariable> variables = getShaderVariables(source);

        if (!variables.isEmpty())
        {
            variables.removeIf((v) -> !filter.contains(v.name));

            String collected = variables.stream().map((m) -> m.name).collect(Collectors.joining("|"));
            String string = "(#define +\\w+ +.*(" + collected + ")|#elif.*(" + collected + ")|#if.*(" + collected + "))";
            Pattern pattern = Pattern.compile(string);
            Matcher matcher = pattern.matcher(source);

            while (matcher.find())
            {
                variables.removeIf((v) ->
                {
                    String group = matcher.group(2);

                    if (group == null) group = matcher.group(3);
                    if (group == null) group = matcher.group(4);

                    return v.name.equals(group);
                });
            }
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
            /* Replace defines with uniform identifiers */
            String collected = variables.stream().map((m) -> m.name).collect(Collectors.joining("|"));
            String string = "(?<!#define |[_\\w\\d])(" + collected + ")(?![_\\w\\d])";
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
            String removeConst = "(const +)([^=]*=[^;]*bbs_[^;]*;)";
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

    private static List<ShaderVariable> getShaderVariables(String source)
    {
        List<ShaderVariable> variables = new ArrayList<>();
        int index = 0;
        Pattern definePattern = Pattern.compile("^\\s*(?!//)\\s*#define +([\\w_]+) +([\\d.]+) *// *\\[");

        while ((index = source.indexOf("#define", index)) != -1)
        {
            int newLine = source.indexOf("\n", index);

            if (newLine == -1)
            {
                newLine = source.length();
            }

            int lastNewLine = source.lastIndexOf('\n', index);
            String define = source.substring(lastNewLine != -1 ? lastNewLine : index, newLine).trim();
            Matcher matcher = definePattern.matcher(define);

            if (matcher.find())
            {
                String name = matcher.group(1);
                boolean present = false;

                for (ShaderVariable variable : variables)
                {
                    if (variable.name.equals(name))
                    {
                        present = true;
                    }
                }

                if (!present)
                {
                    String defaultValue = matcher.group(2);
                    boolean integer = !defaultValue.contains(".");
                    ShaderVariable variable = new ShaderVariable(name, defaultValue, integer);

                    variables.add(variable);
                }
            }

            index = newLine;
        }

        return variables;
    }

    public static void addUniforms(List<CachedUniform> list)
    {
        BBSRendering.addUniforms(list, variableMap);
    }

    public static class ShaderVariable
    {
        public String name = "";
        public String uniformName = "";
        public boolean integer;
        public float defaultValue;
        public Float value;

        public ShaderVariable(String name, String defaultValue, boolean integer)
        {
            this.name = name;
            this.uniformName = "bbs_" + name;
            this.defaultValue = Float.parseFloat(defaultValue);
            this.integer = integer;
        }

        public String toUniformDeclaration()
        {
            return "uniform " + (this.integer ? "int" : "float") + " " + this.uniformName + ";";
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