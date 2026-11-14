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

    private static Set<String> prohibitedVariables = new HashSet<>();
    private static Set<String> prohibitedConstIdentifiers = new HashSet<>();

    public static final String BRIGHTNESS = "brightness";
    public static final String SUN_ROTATION = "sun_rotation";
    public static final String SUN_HORIZONTAL_ROTATION = "sun_horizontal_rotation";
    public static final String WEATHER = "weather";

    public static final String UNIFORM_IDENTIFIER = "bbs_";

    static
    {
        /* photon & Hysteria */
        prohibitedVariables.add("WATER_WAVE_ITERATIONS");
        prohibitedConstIdentifiers.add("get_luminance_from_exposure");
        prohibitedConstIdentifiers.add("get_exposure_from_luminance");
    }

    public static void reset()
    {
        variableMap.clear();
    }

    public static void finishLoading()
    {}

    public static String processSource(String source)
    {
        source = ShaderSunRotation.processSource(source);

        if (BBSSettings.shaderCurvesEnabled == null || !BBSSettings.shaderCurvesEnabled.get())
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
        /* Float options need not be declared as sliders. Integer options remain restricted
         * to the pack's sliders, since they may otherwise be array sizes or sample counts.
         * Keep options used by value-dependent preprocessor directives constant below. */
        List<String> sliders = BBSRendering.getShadersSliderOptions();

        variables.values().removeIf((v) -> v.integer && !sliders.contains(v.name));

        for (String prohibitedVariable : prohibitedVariables)
        {
            variables.remove(prohibitedVariable);
        }

        Matcher directives = Pattern.compile("(?m)^\\h*#\\h*(if|elif|define)\\b([^\\r\\n]*)").matcher(source);

        while (directives.find())
        {
            String directive = directives.group(1);
            String expression = directives.group(2).trim();

            if (directive.equals("define"))
            {
                /* Skip the macro's own name; references in its replacement must stay constant. */
                int end = 0;

                while (end < expression.length() && isIdentifierPart(expression.charAt(end)))
                {
                    end++;
                }

                expression = expression.substring(end);
            }

            final String references = expression;

            variables.values().removeIf((v) -> containsIdentifier(references, v.name));
        }
    }

    private static boolean containsIdentifier(String haystack, String identifier)
    {
        int from = 0;

        while ((from = haystack.indexOf(identifier, from)) != -1)
        {
            int end = from + identifier.length();
            boolean leftOk = from == 0 || !isIdentifierPart(haystack.charAt(from - 1));
            boolean rightOk = end >= haystack.length() || !isIdentifierPart(haystack.charAt(end));

            if (leftOk && rightOk)
            {
                return true;
            }

            from = end;
        }

        return false;
    }

    private static Map<String, ShaderVariable> parseVariables(String source)
    {
        Map<String, ShaderVariable> variables = new HashMap<>();
        Pattern definePattern = Pattern.compile("^\\s*(?!//)\\s*#define +([\\w_]+) +([\\d.]+) *// *(\\[|OptionAnnotatedSource)");
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
        StringBuilder out = new StringBuilder(source.length());
        int length = source.length();
        int i = 0;
        boolean macro = false;

        while (i < length)
        {
            char c = source.charAt(i);

            if (c == '#') macro = true;
            if (c == '\n') macro = false;

            if (isIdentifierStart(c) && !macro)
            {
                int start = i;
                int j = i + 1;

                while (j < length && isIdentifierPart(source.charAt(j)))
                {
                    j++;
                }

                String identifier = source.substring(start, j);
                String replacement = variables.containsKey(identifier) ? UNIFORM_IDENTIFIER  + identifier : identifier;

                out.append(replacement);

                i = j;
            }
            else
            {
                out.append(c);

                i++;
            }
        }

        return out.toString();
    }

    private static boolean isIdentifierStart(char c)
    {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String removeConstFromRelevantVariables(String source)
    {
        Pair<String, Set<String>> pair = removeConst(source, (s) -> s.contains("bbs_"));
        Set<String> deconst = pair.b;

        source = pair.a;

        for (String constIdentifier : prohibitedConstIdentifiers)
        {
            deconst.add(constIdentifier);
        }

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
        int searchIndex = 0;
        int lastIndex = 0;
        int index;

        while ((index = source.indexOf("const ", searchIndex)) != -1)
        {
            int semicolon = source.indexOf(';', index);

            if (semicolon < 0)
            {
                break;
            }

            /* A declaration is one non-overlapping source interval. Searching again
             * from index + 1 can find the word "const" inside the same declaration
             * (usually in a comment or macro expression), making the next index less
             * than the previous semicolon and producing an invalid append range. */
            String substr = source.substring(index, semicolon);

            if (substr.indexOf('{') == -1 && function.apply(substr))
            {
                builder.append(source, lastIndex, index);
                builder.append(source, index + 6, semicolon);

                int equals = substr.indexOf('=');

                if (equals > 0)
                {
                    String declaration = substr.substring(0, equals).trim();
                    int separator = declaration.lastIndexOf(' ');

                    if (separator >= 0 && separator + 1 < declaration.length())
                    {
                        deconst.add(declaration.substring(separator + 1).trim());
                    }
                }
            }
            else
            {
                builder.append(source, lastIndex, semicolon);
            }

            lastIndex = semicolon;
            searchIndex = semicolon + 1;
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
