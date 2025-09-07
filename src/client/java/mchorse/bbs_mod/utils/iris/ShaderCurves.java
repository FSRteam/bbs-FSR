package mchorse.bbs_mod.utils.iris;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.Pair;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ShaderCurves
{
    public static Map<String, ShaderVariable> variableMap = new HashMap<>();

    public static final String BRIGHTNESS = "brightness";
    public static final String SUN_ROTATION = "sun_rotation";

    public static final String UNIFORM_IDENTIFIER = "bbs_";

    public static void reset()
    {
        variableMap.clear();
    }

    public static void finishLoading()
    {}

    public static String processSource(String source)
    {
        if (!BBSSettings.shaderCurvesEnabled.get())
        {
            return source;
        }

        long time = System.currentTimeMillis();

        Map<String, ShaderVariable> variables = parseVariables(source);

        if (!variables.isEmpty())
        {
            source = replaceMacroReferences(source, variables);
            source = removeConstFromRelevantVariables(source);
            source = insertUniforms(source, variables);

            for (ShaderVariable value : variables.values())
            {
                variableMap.putIfAbsent(value.name, value);
            }
        }

        System.out.println("[SHADER_CURVES] full: " + (System.currentTimeMillis() - time) + " " + source.length());

        return source;
    }

    private static Map<String, ShaderVariable> parseVariables(String source)
    {
        long time = System.currentTimeMillis();

        Map<String, ShaderVariable> variables = new HashMap<>();
        Pattern definePattern = Pattern.compile("^\\s*(?!//)\\s*#define +([\\w_]+) +([\\d.]+) *// *\\[");
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
            Matcher matcher = definePattern.matcher(define);

            if (matcher.find())
            {
                String name = matcher.group(1);
                String defaultValue = matcher.group(2);
                boolean integer = !defaultValue.contains(".");
                ShaderVariable variable = new ShaderVariable(name, defaultValue, integer);

                variables.putIfAbsent(variable.name, variable);
            }

            index = newLine;
        }

        System.out.println("[SHADER_CURVES] find variables: " + (System.currentTimeMillis() - time) + " " + source.length());

        time = System.currentTimeMillis();

        /* Remove irrelevant variables */
        List<String> filter = BBSRendering.getShadersSliderOptions();

        variables.values().removeIf((v) -> !filter.contains(v.name));

        String collected = variables.values().stream().map((m) -> m.name).collect(Collectors.joining("|"));
        String string = "(#define +\\w+ +.*(" + collected + ")|#elif.*(" + collected + ")|#if.*(" + collected + "))";
        Pattern pattern = Pattern.compile(string);
        Matcher matcher = pattern.matcher(source);

        while (matcher.find())
        {
            variables.values().removeIf((v) ->
            {
                String group = matcher.group(2);

                if (group == null) group = matcher.group(3);
                if (group == null) group = matcher.group(4);

                return v.name.equals(group);
            });
        }

        System.out.println("[SHADER_CURVES] remove variables: " + (System.currentTimeMillis() - time) + " " + source.length());

        return variables;
    }

    private static String replaceMacroReferences(String source, Map<String, ShaderVariable> variables)
    {
        long time = System.currentTimeMillis();

        String collected = variables.values().stream().map((m) -> m.name).collect(Collectors.joining("|"));
        String string = "(?<!#define |[_\\w\\d])(" + collected + ")(?![_\\w\\d])";
        Pattern pattern = Pattern.compile(string);
        Matcher matcher = pattern.matcher(source);
        StringBuilder sb = new StringBuilder();

        while (matcher.find())
        {
            String processed = matcher.group(1);

            matcher.appendReplacement(sb, UNIFORM_IDENTIFIER + processed);
        }

        matcher.appendTail(sb);

        System.out.println("[SHADER_CURVES] replace macros: " + (System.currentTimeMillis() - time) + " " + source.length());

        return sb.toString();
    }

    private static String removeConstFromRelevantVariables(String source)
    {
        long time = System.currentTimeMillis();

        Pair<String, Set<String>> pair = removeConst(source, (s) -> s.contains("bbs_"));
        Set<String> deconst = pair.b;

        source = pair.a;

        while (!deconst.isEmpty())
        {
            final Set<String> finalDeconst = deconst;

            pair = removeConst(source, (s) ->
            {
                for (String string : finalDeconst)
                {
                    if (s.contains(string)) return true;
                }

                return false;
            });
            source = pair.a;
            deconst = pair.b;
        }

        System.out.println("[SHADER_CURVES] remove const: " + (System.currentTimeMillis() - time) + " " + source.length());

        return source;
    }

    private static Pair<String, Set<String>> removeConst(String source, Function<String, Boolean> function)
    {
        Set<String> deconst = new HashSet<>();
        StringBuilder builder = new StringBuilder();
        int index = 0;
        int lastIndex = 0;

        while ((index = source.indexOf("const ", index + 1)) != -1)
        {
            int semicolon = source.indexOf(';', index);

            if (semicolon >= 0)
            {
                String substr = source.substring(index, semicolon);

                if (function.apply(substr))
                {
                    builder.append(source, lastIndex, index);
                    builder.append(source, index + 6, semicolon);

                    int equals = substr.indexOf('=');
                    String sub = substr.substring(0, equals).trim();

                    equals = sub.lastIndexOf(' ');
                    sub = sub.substring(equals).trim();

                    deconst.add(sub);
                }
                else
                {
                    builder.append(source, lastIndex, semicolon);
                }
            }

            lastIndex = semicolon;
        }

        builder.append(source, lastIndex, source.length());

        source = builder.toString();

        return new Pair<>(source, deconst);
    }

    private static String insertUniforms(String source, Map<String, ShaderVariable> variables)
    {
        int version = source.indexOf("#version");
        int nextNewLine = source.indexOf('\n', version);
        StringBuilder sb = new StringBuilder();

        for (ShaderVariable variable : variables.values())
        {
            sb.append(variable.toUniformDeclaration());
            sb.append('\n');
        }

        return source.substring(0, nextNewLine + 1) + sb + source.substring(nextNewLine + 1);
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
            this.uniformName = UNIFORM_IDENTIFIER + name;
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