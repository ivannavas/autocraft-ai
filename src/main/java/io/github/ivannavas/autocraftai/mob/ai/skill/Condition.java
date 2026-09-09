package io.github.ivannavas.autocraftai.mob.ai.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A yes-or-no question about the {@link Readings}, written the way the mentor writes it and answered
 * every tick a skill is considered.
 *
 * <p>The language is deliberately tiny: {@code and}, {@code or}, {@code not}, brackets, the six
 * comparisons, numbers, words, and the block tests {@code solid(f,u,r)}, {@code air(f,u,r)},
 * {@code breakable(f,u,r)}, {@code water(f,u,r)} and {@code lava(f,u,r)}. A bare name is a reading —
 * {@code armed}, {@code night}, {@code stuck} — and a name that is not a reading is a word to compare
 * against, so {@code cover == PIT} reads as one would expect. Nothing here can loop, allocate, or
 * reach anything but the readings, which is what makes text from a model safe to run once a tick.
 *
 * <pre>
 *   expr       := or
 *   or         := and ("or" and)*
 *   and        := unary ("and" unary)*
 *   unary      := "not" unary | "(" expr ")" | comparison
 *   comparison := term (("==" | "!=" | "<" | "<=" | ">" | ">=") term)?
 *   term       := number | "true" | "false" | name | name "(" int "," int "," int ")"
 * </pre>
 */
public final class Condition {

    private static final Condition ALWAYS = new Condition("true", new Literal(Boolean.TRUE));

    private final String text;
    private final Node root;

    private Condition(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /** A condition that always holds, for a skill that left one out. */
    public static Condition always() {
        return ALWAYS;
    }

    /**
     * Parses the text, or throws {@link IllegalArgumentException} saying where it went wrong. Empty
     * text is the condition that always holds.
     */
    public static Condition parse(String text) {
        if (text == null || text.isBlank()) {
            return ALWAYS;
        }
        Parser parser = new Parser(text);
        Node root = parser.expr();
        if (parser.peek() != null) {
            throw new IllegalArgumentException("unexpected '" + parser.peek() + "' in condition: " + text);
        }
        return new Condition(text.strip(), root);
    }

    public boolean test(Readings readings) {
        return truthy(root.eval(readings));
    }

    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        return value != null && !value.toString().isEmpty();
    }

    // --- the tree ---------------------------------------------------------------------------------

    private interface Node {
        Object eval(Readings readings);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object eval(Readings readings) {
            return value;
        }
    }

    /** A name: a reading when there is one of that name, otherwise the word itself. */
    private record Name(String name) implements Node {
        @Override
        public Object eval(Readings readings) {
            Object value = readings.value(name);
            return value == null ? name.toUpperCase(Locale.ROOT) : value;
        }
    }

    /** {@code has(x)}, {@code short(x)}, {@code near(x)}: a question about the bag or the neighbourhood. */
    private record WordTest(String test, String word) implements Node {
        @Override
        public Object eval(Readings readings) {
            return switch (test) {
                case "has" -> readings.count(word);
                case "short" -> readings.shortOf(word);
                case "near" -> readings.near(word);
                default -> Boolean.FALSE;
            };
        }
    }

    private record BlockTest(String test, int forward, int up, int right) implements Node {
        @Override
        public Object eval(Readings readings) {
            return switch (test) {
                case "solid" -> readings.solid(forward, up, right);
                case "air" -> readings.air(forward, up, right);
                case "breakable" -> readings.breakable(forward, up, right);
                case "water" -> readings.water(forward, up, right);
                case "lava" -> readings.lava(forward, up, right);
                default -> Boolean.FALSE;
            };
        }
    }

    private record Not(Node inner) implements Node {
        @Override
        public Object eval(Readings readings) {
            return !truthy(inner.eval(readings));
        }
    }

    private record And(List<Node> parts) implements Node {
        @Override
        public Object eval(Readings readings) {
            for (Node part : parts) {
                if (!truthy(part.eval(readings))) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }
    }

    private record Or(List<Node> parts) implements Node {
        @Override
        public Object eval(Readings readings) {
            for (Node part : parts) {
                if (truthy(part.eval(readings))) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    }

    private record Compare(String op, Node left, Node right) implements Node {
        @Override
        public Object eval(Readings readings) {
            Object a = left.eval(readings);
            Object b = right.eval(readings);
            if (a instanceof Number x && b instanceof Number y) {
                double d = x.doubleValue() - y.doubleValue();
                return switch (op) {
                    case "==" -> d == 0.0;
                    case "!=" -> d != 0.0;
                    case "<" -> d < 0.0;
                    case "<=" -> d <= 0.0;
                    case ">" -> d > 0.0;
                    default -> d >= 0.0;
                };
            }
            String s = String.valueOf(a).toUpperCase(Locale.ROOT);
            String t = String.valueOf(b).toUpperCase(Locale.ROOT);
            return switch (op) {
                case "==" -> s.equals(t);
                case "!=" -> !s.equals(t);
                default -> Boolean.FALSE;
            };
        }
    }

    // --- the parser -------------------------------------------------------------------------------

    private static final class Parser {

        private final List<String> tokens = new ArrayList<>();
        private int at;

        Parser(String text) {
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':') {
                    int start = i;
                    while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i))
                            || text.charAt(i) == '_' || text.charAt(i) == '-' || text.charAt(i) == '.'
                            || text.charAt(i) == ':')) {
                        i++;
                    }
                    tokens.add(text.substring(start, i));
                } else if ("=!<>".indexOf(c) >= 0) {
                    int start = i;
                    i++;
                    if (i < text.length() && text.charAt(i) == '=') {
                        i++;
                    }
                    tokens.add(text.substring(start, i));
                } else if ("(),".indexOf(c) >= 0) {
                    tokens.add(String.valueOf(c));
                    i++;
                } else {
                    throw new IllegalArgumentException("cannot read '" + c + "' in condition: " + text);
                }
            }
        }

        String peek() {
            return at < tokens.size() ? tokens.get(at) : null;
        }

        private String take() {
            if (at >= tokens.size()) {
                throw new IllegalArgumentException("condition ends too soon");
            }
            return tokens.get(at++);
        }

        private boolean accept(String word) {
            if (word.equalsIgnoreCase(peek())) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(String word) {
            if (!accept(word)) {
                throw new IllegalArgumentException("expected '" + word + "' but found '" + peek() + "'");
            }
        }

        Node expr() {
            return or();
        }

        private Node or() {
            List<Node> parts = new ArrayList<>();
            parts.add(and());
            while (accept("or")) {
                parts.add(and());
            }
            return parts.size() == 1 ? parts.get(0) : new Or(parts);
        }

        private Node and() {
            List<Node> parts = new ArrayList<>();
            parts.add(unary());
            while (accept("and")) {
                parts.add(unary());
            }
            return parts.size() == 1 ? parts.get(0) : new And(parts);
        }

        private Node unary() {
            if (accept("not")) {
                return new Not(unary());
            }
            if (accept("(")) {
                Node inner = expr();
                expect(")");
                return inner;
            }
            return comparison();
        }

        private Node comparison() {
            Node left = term();
            String next = peek();
            if (next != null && ("==".equals(next) || "!=".equals(next) || "<".equals(next)
                    || "<=".equals(next) || ">".equals(next) || ">=".equals(next))) {
                String op = take();
                if ("=".equals(op)) {
                    op = "==";
                }
                return new Compare(op, left, term());
            }
            if ("=".equals(next)) {
                take();
                return new Compare("==", left, term());
            }
            return left;
        }

        private Node term() {
            String token = take();
            if ("true".equalsIgnoreCase(token)) {
                return new Literal(Boolean.TRUE);
            }
            if ("false".equalsIgnoreCase(token)) {
                return new Literal(Boolean.FALSE);
            }
            if (token.matches("-?\\d+(\\.\\d+)?")) {
                return new Literal(Double.parseDouble(token));
            }
            if (!token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("cannot read '" + token + "' in condition");
            }
            if (accept("(")) {
                String test = token.toLowerCase(Locale.ROOT);
                if (List.of("has", "short", "near").contains(test)) {
                    String word = take().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                    if (!word.matches("[a-z][a-z0-9_]{0,40}")) {
                        throw new IllegalArgumentException(test + " takes an item or resource name, not '"
                                + word + "'");
                    }
                    expect(")");
                    return new WordTest(test, word);
                }
                int forward = number();
                expect(",");
                int up = number();
                expect(",");
                int right = number();
                expect(")");
                if (!List.of("solid", "air", "breakable", "water", "lava").contains(test)) {
                    throw new IllegalArgumentException("no block test called '" + token + "'");
                }
                return new BlockTest(test, forward, up, right);
            }
            return new Name(token);
        }

        private int number() {
            String token = take();
            if (!token.matches("-?\\d+")) {
                throw new IllegalArgumentException("expected a whole number but found '" + token + "'");
            }
            int value = Integer.parseInt(token);
            if (Math.abs(value) > Skill.REACH) {
                throw new IllegalArgumentException("offset " + value + " is further than a skill may reach");
            }
            return value;
        }
    }
}
