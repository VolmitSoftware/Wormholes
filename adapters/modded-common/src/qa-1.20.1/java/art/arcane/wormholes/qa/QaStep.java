package art.arcane.wormholes.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One script line: an operation name and the unparsed remainder, which may reference ${variables}. An
 * {@code include <file>} line is replaced at parse time by the steps of that file, resolved next to the script.
 */
record QaStep(int line, String op, String rest) {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    static List<QaStep> parse(Path script) throws IOException {
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        List<QaStep> steps = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String text = lines.get(index).strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int space = text.indexOf(' ');
            String op = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
            String rest = space < 0 ? "" : text.substring(space + 1).strip();
            if (op.equals("include")) {
                steps.addAll(parse(script.resolveSibling(rest)));
                continue;
            }
            steps.add(new QaStep(index + 1, op, rest));
        }
        return steps;
    }

    String text() {
        return rest.isEmpty() ? op : op + " " + rest;
    }

    /** Substitutes variables; throws when one is undefined so the step fails instead of sending a broken command. */
    String resolve(Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(rest);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("Undefined variable ${" + matcher.group(1) + "}");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
