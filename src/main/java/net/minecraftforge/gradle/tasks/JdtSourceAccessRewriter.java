package net.minecraftforge.gradle.tasks;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * JDT-based modifier rewriter.
 *
 * <p>This class is intentionally free of Gradle APIs. It is loaded from an
 * isolated classloader with unsigned copies of the Eclipse jars, because the
 * signed Eclipse jars cannot share a flat classpath with Gradle's instrumented
 * buildscript classloader.</p>
 *
 * <p>The change spec passed to {@link #rewrite(String, Map)} uses only JDK
 * types so it can cross the classloader boundary:</p>
 *
 * <pre>
 * key:   nesting + ":" + simpleClassName
 * value: Object[] { Integer classAccess (-1 = none), Boolean classVisibilityChanged,
 *                   Boolean classFinalChanged, Map&lt;String, Object[]&gt; fields,
 *                   Map&lt;String, Object[]&gt; methods }
 * member value: Object[] { Integer access, Boolean visibilityChanged, Boolean finalChanged }
 * </pre>
 */
public final class JdtSourceAccessRewriter {
    private static String lastDiagnostics = "";

    private JdtSourceAccessRewriter() {
    }

    public static String getLastDiagnostics() {
        return lastDiagnostics;
    }

    public static String rewrite(String source, Map<String, Object[]> targets) {
        StringBuilder diagnostics = new StringBuilder();
        try {
            ASTParser parser = ASTParser.newParser(AST.JLS8);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(source.toCharArray());
            parser.setResolveBindings(false);
            parser.setBindingsRecovery(false);
            Map<String, String> options = new HashMap<>();
            options.put("org.eclipse.jdt.core.compiler.source", "1.8");
            options.put("org.eclipse.jdt.core.compiler.compliance", "1.8");
            options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "1.8");
            parser.setCompilerOptions(options);
            CompilationUnit unit = (CompilationUnit) parser.createAST(null);

            for (IProblem problem : unit.getProblems()) {
                if (problem.isError()) {
                    diagnostics.append("JDT parse error: ").append(problem.getMessage()).append('\n');
                    break;
                }
            }

            AST ast = unit.getAST();
            final ASTRewrite rewrite = ASTRewrite.create(ast);
            final boolean[] changed = new boolean[1];

            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    Object[] change = targets.get(typeKey(node));
                    if (change != null && (Integer) change[0] != -1) {
                        changed[0] |= applyModifiers(rewrite, ast, node, (Integer) change[0],
                                (Boolean) change[1], (Boolean) change[2]);
                    }
                    return true;
                }

                @Override
                public boolean visit(FieldDeclaration node) {
                    Object[] change = enclosingChange(node);
                    if (change == null)
                        return true;

                    Map<String, Object[]> fields = (Map<String, Object[]>) change[3];
                    for (Object fragment : node.fragments()) {
                        String name = ((VariableDeclarationFragment) fragment).getName().getIdentifier();
                        Object[] member = fields.get(name);
                        if (member == null)
                            continue;
                        changed[0] |= applyModifiers(rewrite, ast, node, (Integer) member[0],
                                (Boolean) member[1], (Boolean) member[2]);
                        break;
                    }
                    return true;
                }

                @Override
                public boolean visit(MethodDeclaration node) {
                    Object[] change = enclosingChange(node);
                    if (change == null)
                        return true;

                    String name = node.isConstructor() ? "<init>" : node.getName().getIdentifier();
                    Map<String, Object[]> methods = (Map<String, Object[]>) change[4];
                    Object[] member = methods.get(methodKey(name, node.parameters().size()));
                    if (member != null) {
                        changed[0] |= applyModifiers(rewrite, ast, node, (Integer) member[0],
                                (Boolean) member[1], (Boolean) member[2]);
                    }
                    return true;
                }

                private Object[] enclosingChange(ASTNode node) {
                    ASTNode parent = node.getParent();
                    while (parent != null && !(parent instanceof TypeDeclaration))
                        parent = parent.getParent();
                    return parent == null ? null : targets.get(typeKey((TypeDeclaration) parent));
                }

                private String typeKey(TypeDeclaration node) {
                    int nesting = 0;
                    ASTNode parent = node.getParent();
                    while (parent != null) {
                        if (parent instanceof TypeDeclaration)
                            nesting++;
                        parent = parent.getParent();
                    }
                    return nesting + ":" + node.getName().getIdentifier();
                }
            });

            if (!changed[0])
                return source;

            Document document = new Document(source);
            TextEdit edits = rewrite.rewriteAST(document, options);
            edits.apply(document);
            return document.get();
        } catch (Throwable t) {
            diagnostics.append("JDT rewrite failed: ").append(t).append('\n');
            return source;
        } finally {
            lastDiagnostics = diagnostics.toString();
        }
    }

    private static boolean applyModifiers(ASTRewrite rewrite, AST ast, BodyDeclaration node, int access,
                                          boolean visibilityChanged, boolean finalChanged) {
        boolean changed = false;
        ListRewrite modifiers = rewrite.getListRewrite(node, node.getModifiersProperty());

        if (visibilityChanged) {
            for (Object keyword : new ArrayList<Object>(node.modifiers())) {
                if (!(keyword instanceof Modifier))
                    continue;
                Modifier.ModifierKeyword value = ((Modifier) keyword).getKeyword();
                if (value == Modifier.ModifierKeyword.PUBLIC_KEYWORD
                        || value == Modifier.ModifierKeyword.PROTECTED_KEYWORD
                        || value == Modifier.ModifierKeyword.PRIVATE_KEYWORD) {
                    modifiers.remove((Modifier) keyword, null);
                    changed = true;
                }
            }
            String visibility = visibilityKeyword(access);
            if (!visibility.isEmpty()) {
                modifiers.insertFirst(ast.newModifier(keyword(visibility)), null);
                changed = true;
            }
        }

        if (finalChanged) {
            for (Object keyword : new ArrayList<Object>(node.modifiers())) {
                if (keyword instanceof Modifier
                        && ((Modifier) keyword).getKeyword() == Modifier.ModifierKeyword.FINAL_KEYWORD) {
                    modifiers.remove((Modifier) keyword, null);
                    changed = true;
                }
            }
            if ((access & 0x0010) != 0) { // ACC_FINAL
                modifiers.insertLast(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD), null);
                changed = true;
            }
        }
        return changed;
    }

    private static Modifier.ModifierKeyword keyword(String visibility) {
        if ("public".equals(visibility))
            return Modifier.ModifierKeyword.PUBLIC_KEYWORD;
        if ("protected".equals(visibility))
            return Modifier.ModifierKeyword.PROTECTED_KEYWORD;
        return Modifier.ModifierKeyword.PRIVATE_KEYWORD;
    }

    private static String visibilityKeyword(int access) {
        if ((access & 0x0001) != 0) // ACC_PUBLIC
            return "public";
        if ((access & 0x0004) != 0) // ACC_PROTECTED
            return "protected";
        if ((access & 0x0002) != 0) // ACC_PRIVATE
            return "private";
        return "";
    }

    private static String methodKey(String name, int argCount) {
        return name + "(" + argCount + ")";
    }
}
