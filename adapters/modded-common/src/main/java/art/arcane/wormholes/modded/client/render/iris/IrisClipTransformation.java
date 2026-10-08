package art.arcane.wormholes.modded.client.render.iris;

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
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisClipTransformation {
    public static final String UNIFORM = "wormholes_ClipPlane";
    public static final int MAX_DISTANCES = 8;

    private static final String MAIN = "wormholes_clipMain";
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)\\b");

    private IrisClipTransformation() {
    }

    public static String uniform(int distance) {
        return UNIFORM + distance;
    }

    public static Map<PatchShaderType, String> transform(Map<PatchShaderType, String> sources) {
        ASTParser parser = new ASTParser();
        EnumMap<PatchShaderType, TranslationUnit> trees = new EnumMap<>(PatchShaderType.class);
        BitSet occupied = new BitSet(MAX_DISTANCES);
        for (Map.Entry<PatchShaderType, String> source : sources.entrySet()) {
            if (source.getValue() == null || source.getKey() == PatchShaderType.COMPUTE) {
                continue;
            }
            Matcher version = VERSION.matcher(source.getValue());
            if (!version.find()) {
                throw new IllegalArgumentException("Shader has no version directive");
            }
            parser.getLexer().version = Version.fromNumber(Integer.parseInt(version.group(1)));
            TranslationUnit tree = parser.parseTranslationUnit(RootSupplier.PREFIX_UNORDERED_ED_EXACT, source.getValue());
            inspect(tree, occupied);
            trees.put(source.getKey(), tree);
        }
        int distance = occupied.nextClearBit(0);
        if (distance >= MAX_DISTANCES) {
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
        return result;
    }

    private static void inspect(TranslationUnit tree, BitSet occupied) {
        for (Identifier identifier : tree.getRoot().nodeIndex.getStream(Identifier.class).toList()) {
            String name = identifier.getName();
            if (name.startsWith(UNIFORM) || name.equals(MAIN)) {
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
            if (index < 0 || index >= MAX_DISTANCES) {
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
        String uniform = uniform(distance);
        String assignment = "gl_ClipDistance[" + distance + "] = dot(gl_Position, " + uniform + ")";
        root.indexBuildSession(() -> {
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, "uniform vec4 " + uniform + ";");
            if (stage == PatchShaderType.GEOMETRY) {
                injectEmissions(parser, tree, assignment);
                return;
            }
            root.rename("main", MAIN);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "void main() { " + MAIN + "(); " + assignment + "; }");
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
}
