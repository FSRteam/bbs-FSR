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

        Map<String, ShaderVariable> variables = parseVariables(source);

        if (!variables.isEmpty())
        {
            removeIrrelevantVariables(source, variables);

            source = replaceMacroReferences(source, variables);
            source = removeConstFromRelevantVariables(source);
            source = insertUniforms(source, variables);

            for (ShaderVariable value : variables.values())
            {
                variableMap.putIfAbsent(value.name, value);
            }
        }

        return source;
    }

    private static void removeIrrelevantVariables(String source, Map<String, ShaderVariable> variables)
    {
        /* Remove irrelevant variables */
        List<String> filter = BBSRendering.getShadersSliderOptions();

        variables.values().removeIf((v) -> !filter.contains(v.name));

        int index = 0;

        while ((index = source.indexOf("#", index + 1)) != -1)
        {
            int newLine = source.indexOf('\n', index);

            if (newLine >= 0)
            {
                String substr = source.substring(index, newLine);

                if (substr.startsWith("#if") || substr.startsWith("#elif"))
                {
                    variables.values().removeIf((v) -> substr.contains(v.name));
                }
                else if (substr.startsWith("#define"))
                {
                    final int WHITESPACE = 0, CHARACTERS = 1;
                    int iindex = 7;
                    int state = 0;
                    int switches = 0;

                    while (iindex < newLine - index)
                    {
                        char c = substr.charAt(iindex);

                        if (state == WHITESPACE && Character.isWhitespace(c))
                        {
                            state = CHARACTERS;
                            switches += 1;
                        }
                        else if (Character.isWhitespace(c))
                        {
                            state = WHITESPACE;
                        }

                        if (switches == 2)
                        {
                            break;
                        }

                        iindex += 1;
                    }

                    final String subsubstr = substr.substring(iindex);

                    variables.values().removeIf((v) -> subsubstr.contains(v.name));
                }
            }
        }
    }

    private static Map<String, ShaderVariable> parseVariables(String source)
    {
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

        return variables;
    }

    private static String replaceMacroReferences(String source, Map<String, ShaderVariable> variables)
    {
        List<String> names = variables.keySet().stream().toList();
        StringBuilder out = new StringBuilder(source.length() + names.size() * UNIFORM_IDENTIFIER.length());
        int n = source.length(), i = 0;
        final int NORMAL = 0, LINE = 1, BLOCK = 2;
        int state = NORMAL;

        while (i < n)
        {
            char c = source.charAt(i);

            if (state == NORMAL)
            {
                if (c == '/' && i + 1 < n)
                {
                    char d = source.charAt(i + 1);

                    if (d == '/') { out.append("//"); i += 2; state = LINE; continue; }
                    if (d == '*') { out.append("/*"); i += 2; state = BLOCK; continue; }
                }

                if (Character.isLetter(c) || c == '_')
                {
                    int start = i;
                    int j = i + 1;

                    while (j < n)
                    {
                        char cj = source.charAt(j);
                        if (Character.isLetterOrDigit(cj) || cj == '_') j++; else break;
                    }

                    String ident = source.substring(start, j);

                    if (names.contains(ident))
                    {
                        out.append(UNIFORM_IDENTIFIER);
                    }

                    out.append(ident);

                    i = j;

                    continue;
                }

                out.append(c);

                i++;
            }
            else if (state == LINE)
            {
                out.append(c);

                i++;

                if (c == '\n')
                {
                    state = NORMAL;
                }
            }
            else
            {
                if (c == '*' && i + 1 < n && source.charAt(i + 1) == '/')
                {
                    out.append("*/");

                    i += 2;
                    state = NORMAL;

                    continue;
                }

                out.append(c);

                i++;
            }
        }

        return out.toString();
    }

    private static String removeConstFromRelevantVariables(String source)
    {
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

        return new Pair<>(builder.toString(), deconst);
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