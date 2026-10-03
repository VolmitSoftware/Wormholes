package art.arcane.wormholes.modded.client.render;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructDeclarator;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;

import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PortalIrisClipping {
    public static final String UNIFORM = "wormholes_ClipPlane";

    private static final String MAIN = "wormholes_clipMain";
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)\\b");

    private PortalIrisClipping() {
    }

    public static Result transform(Map<PatchShaderType, String> sources, int maxClipDistances) {
        Objects.requireNonNull(sources);
        if (maxClipDistances <= 0) {
            throw new IllegalArgumentException("No clip distances available");
        }
        ASTParser parser = new ASTParser();
        EnumMap<PatchShaderType, TranslationUnit> trees = new EnumMap<>(PatchShaderType.class);
        BitSet occupied = new BitSet(maxClipDistances);
        for (Map.Entry<PatchShaderType, String> source : sources.entrySet()) {
            if (source.getValue() == null) {
                continue;
            }
            Matcher version = VERSION.matcher(source.getValue());
            if (!version.find()) {
                throw new IllegalArgumentException("Shader has no version directive");
            }
            parser.getLexer().version = Version.fromNumber(Integer.parseInt(version.group(1)));
            TranslationUnit tree = parser.parseTranslationUnit(RootSupplier.PREFIX_UNORDERED_ED_EXACT, source.getValue());
            inspect(tree, occupied, maxClipDistances);
            trees.put(source.getKey(), tree);
        }
        int distance = occupied.nextClearBit(0);
        if (distance >= maxClipDistances) {
            throw new IllegalArgumentException("Shader uses every available clip distance");
        }
        PatchShaderType emitting = emittingStage(trees);
        EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
        result.putAll(sources);
        for (Map.Entry<PatchShaderType, TranslationUnit> entry : trees.entrySet()) {
            TranslationUnit tree = entry.getValue();
            parser.getLexer().version = tree.getVersionStatement().version;
            boolean resized = resizeDeclarations(parser, tree, distance);
            if (entry.getKey() == emitting) {
                inject(parser, tree, emitting, distance);
            }
            if (resized || entry.getKey() == emitting) {
                result.put(entry.getKey(), ASTPrinter.printSimple(tree));
            }
        }
        return new Result(result, distance, existingDistances(occupied));
    }

    private static Set<Integer> existingDistances(BitSet occupied) {
        Set<Integer> distances = new HashSet<>(occupied.cardinality());
        for (int index = occupied.nextSetBit(0); index >= 0; index = occupied.nextSetBit(index + 1)) {
            distances.add(index);
        }
        return Set.copyOf(distances);
    }

    private static void inspect(TranslationUnit tree, BitSet occupied, int maxClipDistances) {
        for (Identifier identifier : tree.getRoot().nodeIndex.getStream(Identifier.class).toList()) {
            String name = identifier.getName();
            if (name.equals(UNIFORM) || name.equals(MAIN)) {
                throw new IllegalArgumentException("Shader uses reserved portal clipping identifier: " + name);
            }
            if (!name.equals("gl_ClipDistance")) {
                continue;
            }
            ASTNode parent = identifier.getParent();
            if (parent instanceof DeclarationMember || parent instanceof StructDeclarator) {
                continue;
            }
            if (!(parent instanceof ReferenceExpression || parent instanceof MemberAccessExpression)
                || !(parent.getParent() instanceof ArrayAccessExpression access)
                || access.getLeft() != parent
                || !(access.getRight() instanceof LiteralExpression literal) || !literal.isInteger()) {
                throw new IllegalArgumentException("Shader clip distances require literal component indexes");
            }
            long index = literal.getInteger();
            if (index < 0 || index >= maxClipDistances) {
                throw new IllegalArgumentException("Shader clip distance index exceeds device limits");
            }
            occupied.set((int) index);
        }
    }

    private static PatchShaderType emittingStage(Map<PatchShaderType, TranslationUnit> trees) {
        if (trees.containsKey(PatchShaderType.GEOMETRY)) {
            return PatchShaderType.GEOMETRY;
        }
        if (trees.containsKey(PatchShaderType.TESS_EVAL)) {
            return PatchShaderType.TESS_EVAL;
        }
        if (trees.containsKey(PatchShaderType.VERTEX)) {
            return PatchShaderType.VERTEX;
        }
        throw new IllegalArgumentException("Shader has no vertex emitting stage");
    }

    private static boolean resizeDeclarations(ASTParser parser, TranslationUnit tree, int distance) {
        boolean resized = false;
        for (Identifier identifier : tree.getRoot().nodeIndex.getStream(Identifier.class).toList()) {
            if (!identifier.getName().equals("gl_ClipDistance")) {
                continue;
            }
            ArraySpecifier array = switch (identifier.getParent()) {
                case DeclarationMember member -> member.getArraySpecifier();
                case StructDeclarator member -> member.getArraySpecifier();
                default -> null;
            };
            if (array == null) {
                continue;
            }
            if (array.getDimensions().size() != 1) {
                throw new IllegalArgumentException("Shader clip distance declaration must be one dimensional");
            }
            Expression dimension = array.getDimensions().getFirst();
            if (dimension == null) {
                continue;
            }
            if (!(dimension instanceof LiteralExpression literal) || !literal.isInteger()) {
                throw new IllegalArgumentException("Shader clip distance array size must be literal");
            }
            if (literal.getInteger() <= distance) {
                dimension.replaceByAndDelete(parser.parseExpression(tree.getRoot(), Integer.toString(distance + 1)));
                resized = true;
            }
        }
        return resized;
    }

    private static void inject(ASTParser parser, TranslationUnit tree, PatchShaderType stage, int distance) {
        Root root = tree.getRoot();
        String assignment = "gl_ClipDistance[" + distance + "] = dot(gl_Position, " + UNIFORM + ");";
        root.indexBuildSession(() -> {
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, "uniform vec4 " + UNIFORM + ";");
            if (stage == PatchShaderType.GEOMETRY) {
                injectEmissions(parser, tree, assignment.substring(0, assignment.length() - 1));
                return;
            }
            root.rename("main", MAIN);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                "void main() { " + MAIN + "(); " + assignment + " }");
        });
    }

    private static void injectEmissions(ASTParser parser, TranslationUnit tree, String assignment) {
        Root root = tree.getRoot();
        for (FunctionCallExpression call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (call.getFunctionName() == null) {
                continue;
            }
            String name = call.getFunctionName().getName();
            if (name.equals("EmitVertex") || name.equals("EmitStreamVertex")) {
                String emission = ASTPrinter.printSimple(call);
                call.replaceByAndDelete(parser.parseExpression(root, "(" + assignment + ", " + emission + ")"));
            }
        }
    }

    public record Result(Map<PatchShaderType, String> sources, int clipDistance, Set<Integer> existingDistances) {
        public Result {
            EnumMap<PatchShaderType, String> copy = new EnumMap<>(PatchShaderType.class);
            copy.putAll(sources);
            sources = Collections.unmodifiableMap(copy);
            existingDistances = Set.copyOf(existingDistances);
        }
    }
}
