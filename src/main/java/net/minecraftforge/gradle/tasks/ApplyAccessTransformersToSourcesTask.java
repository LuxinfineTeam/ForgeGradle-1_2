package net.minecraftforge.gradle.tasks;

import net.minecraftforge.gradle.delayed.DelayedFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Aligns the cached decompiled sources with the access flags of the actual
 * AT-applied binary jar.
 *
 * <p>AT rules are not interpreted as text here. The clean main jar and the
 * AT-applied main jar are compared with ASM; only members whose bytecode
 * access flags really changed are rewritten in the corresponding .java
 * declaration. Rules that FML/SpecialSource ignore (downgrades, missing
 * members, unresolved mappings, etc.) therefore never produce misleading
 * source code.</p>
 *
 * <p>The declarations themselves are edited with JDT AST rewriter, loaded from
 * an isolated classloader with unsigned copies of the Eclipse jars. This
 * avoids the signer conflict between the signed Eclipse jars and Gradle's
 * instrumented buildscript classloader.</p>
 */
@UntrackedTask(because = "Applies project access transformers to a cached sources jar")
public class ApplyAccessTransformersToSourcesTask extends DefaultTask {
    private static final int VISIBILITY_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;

    private static final String HELPER_CLASS = "net.minecraftforge.gradle.tasks.JdtSourceAccessRewriter";

    private DelayedFile inJar;
    private DelayedFile cleanJar;
    private DelayedFile atJar;
    private DelayedFile outJar;

    private final ArrayList<DelayedFile> ats = new ArrayList<>();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getInJar() {
        return inJar.call();
    }

    public void setInJar(DelayedFile inJar) {
        this.inJar = inJar;
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getCleanJar() {
        return cleanJar.call();
    }

    public void setCleanJar(DelayedFile cleanJar) {
        this.cleanJar = cleanJar;
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getAtJar() {
        return atJar.call();
    }

    public void setAtJar(DelayedFile atJar) {
        this.atJar = atJar;
    }

    @OutputFile
    public File getOutJar() {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public List<DelayedFile> getAts() {
        return ats;
    }

    public void addTransformer(Object... objects) {
        for (Object object : objects) {
            if (object instanceof File)
                ats.add(new DelayedFile(getProject(), ((File) object).getAbsolutePath()));
            else if (object instanceof String)
                ats.add(new DelayedFile(getProject(), (String) object));
            else if (object != null)
                ats.add(new DelayedFile(getProject(), object.toString()));
        }
    }

    @TaskAction
    public void doTask() throws IOException {
        File in = getInJar();
        File transformed = getAtJar();
        File out = getOutJar();
        out.getParentFile().mkdirs();

        Map<String, ClassInfo> cleanClasses = readAccesses(getCleanJar());
        Map<String, ClassInfo> atClasses = readAccesses(transformed);
        Map<String, ClassChange> changes = diff(cleanClasses, atClasses);
        getLogger().info("AT sources bytecode diff: {} -> {}, {} class(es) changed",
                getCleanJar(), transformed, changes.size());
        if (changes.isEmpty()) {
            Files.copy(in.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        URLClassLoader jdtLoader = createJdtLoader();
        try {
            try (ZipFile zip = new ZipFile(in);
                 ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    byte[] data = readFully(zip.getInputStream(entry));

                    if (entry.getName().endsWith(".java") && !entry.getName().endsWith("package-info.java")) {
                        String className = entry.getName()
                                .substring(0, entry.getName().length() - ".java".length())
                                .replace('/', '.');
                        String source = new String(data, StandardCharsets.UTF_8);
                        String rewritten = applyChanges(source, className, changes, jdtLoader);
                        data = rewritten.getBytes(StandardCharsets.UTF_8);
                    }

                    output.putNextEntry(new ZipEntry(entry));
                    output.write(data);
                    output.closeEntry();
                }
            }
        } finally {
            if (jdtLoader != null) {
                try {
                    jdtLoader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Map<String, ClassInfo> readAccesses(File jar) throws IOException {
        Map<String, ClassInfo> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entry.getName().endsWith(".class"))
                    continue;

                byte[] data = readFully(zip.getInputStream(entry));
                ClassReader reader = new ClassReader(data);
                ClassInfo info = new ClassInfo();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                                      String superName, String[] interfaces) {
                        info.access = access;
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String desc,
                                                   String signature, Object value) {
                        info.fields.put(name, new MemberAccess(access, -1));
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String signature, String[] exceptions) {
                        if ("<clinit>".equals(name))
                            return null;
                        Type[] args = Type.getArgumentTypes(desc);
                        info.methods.put(methodKey(name, args.length), new MemberAccess(access, args.length));
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                result.put(reader.getClassName().replace('/', '.'), info);
            }
        }
        return result;
    }

    private Map<String, ClassChange> diff(Map<String, ClassInfo> clean, Map<String, ClassInfo> at) {
        Map<String, ClassChange> changes = new HashMap<>();
        for (Map.Entry<String, ClassInfo> entry : clean.entrySet()) {
            ClassInfo after = at.get(entry.getKey());
            if (after == null)
                continue;

            ClassInfo before = entry.getValue();
            ClassChange change = new ClassChange();
            int classDelta = before.access ^ after.access;
            if ((classDelta & VISIBILITY_MASK) != 0 || (classDelta & Opcodes.ACC_FINAL) != 0) {
                change.classChanged = true;
                change.classAccess = after.access;
                change.classVisibilityChanged = (classDelta & VISIBILITY_MASK) != 0;
                change.classFinalChanged = (classDelta & Opcodes.ACC_FINAL) != 0;
            }

            for (Map.Entry<String, MemberAccess> field : before.fields.entrySet()) {
                MemberAccess newAccess = after.fields.get(field.getKey());
                if (newAccess == null)
                    continue;
                int delta = field.getValue().access ^ newAccess.access;
                boolean visibility = (delta & VISIBILITY_MASK) != 0;
                boolean fin = (delta & Opcodes.ACC_FINAL) != 0;
                if (visibility || fin)
                    change.fields.put(field.getKey(),
                            new MemberChange(field.getKey(), -1, newAccess.access, visibility, fin));
            }

            for (Map.Entry<String, MemberAccess> method : before.methods.entrySet()) {
                MemberAccess newAccess = after.methods.get(method.getKey());
                if (newAccess == null)
                    continue;
                int delta = method.getValue().access ^ newAccess.access;
                if ((delta & VISIBILITY_MASK) != 0) {
                    String name = method.getKey().substring(0, method.getKey().indexOf('('));
                    change.methods.put(method.getKey(),
                            new MemberChange(name, newAccess.argCount, newAccess.access, true, false));
                }
            }

            if (!change.isEmpty())
                changes.put(entry.getKey(), change);
        }
        return changes;
    }

    private String applyChanges(String source, String sourceClass, Map<String, ClassChange> changes,
                                URLClassLoader jdtLoader) {
        Map<String, Object[]> targets = new HashMap<>();
        for (Map.Entry<String, ClassChange> entry : changes.entrySet()) {
            String owner = entry.getKey();
            if (!owner.equals(sourceClass) && !owner.startsWith(sourceClass + "$"))
                continue;

            String suffix = owner.substring(sourceClass.length());
            int nesting = suffix.isEmpty() ? 0 : suffix.split("\\$", -1).length - 1;
            int separator = Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$'));
            String simpleName = owner.substring(separator + 1);
            ClassChange change = entry.getValue();
            targets.put(nesting + ":" + simpleName, new Object[] {
                    change.classChanged ? change.classAccess : -1,
                    change.classVisibilityChanged,
                    change.classFinalChanged,
                    toMemberSpec(change.fields),
                    toMemberSpec(change.methods)
            });
        }
        if (targets.isEmpty())
            return source;
        if (jdtLoader == null) {
            getLogger().warn("JDT rewriter unavailable, leaving {} unchanged", sourceClass);
            return source;
        }

        try {
            Class<?> helper = jdtLoader.loadClass(HELPER_CLASS);
            Object result = helper.getMethod("rewrite", String.class, Map.class).invoke(null, source, targets);
            Object diagnostics = helper.getMethod("getLastDiagnostics").invoke(null);
            if (diagnostics instanceof String && !((String) diagnostics).isEmpty())
                getLogger().warn("{}", diagnostics);
            return (String) result;
        } catch (Exception e) {
            getLogger().warn("JDT rewrite failed for {}: {}", sourceClass, e.toString());
            return source;
        }
    }

    private Map<String, Object[]> toMemberSpec(Map<String, MemberChange> members) {
        Map<String, Object[]> result = new HashMap<>();
        for (Map.Entry<String, MemberChange> entry : members.entrySet()) {
            MemberChange member = entry.getValue();
            result.put(entry.getKey(), new Object[] {
                    member.access, member.visibilityChanged, member.finalChanged
            });
        }
        return result;
    }

    private URLClassLoader createJdtLoader() {
        try {
            Set<URL> sourceUrls = new LinkedHashSet<>();
            ClassLoader classLoader = getClass().getClassLoader();
            while (classLoader != null) {
                if (classLoader instanceof URLClassLoader) {
                    for (URL url : ((URLClassLoader) classLoader).getURLs()) {
                        if (isJdtUrl(url))
                            sourceUrls.add(url);
                    }
                }
                classLoader = classLoader.getParent();
            }
            if (sourceUrls.isEmpty()) {
                getLogger().warn("Could not locate JDT jars on the buildscript classpath");
                return null;
            }

            File unsignedDir = unsignedCopies(sourceUrls);
            List<URL> urls = new ArrayList<>();
            File[] jars = unsignedDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars == null || jars.length == 0) {
                getLogger().warn("Could not prepare unsigned JDT jars in {}", unsignedDir);
                return null;
            }
            for (File jar : jars)
                urls.add(jar.toURI().toURL());

            ClassLoader parent;
            try {
                parent = (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
            } catch (Exception e) {
                parent = null;
            }
            return new URLClassLoader(urls.toArray(new URL[0]), parent);
        } catch (Exception e) {
            getLogger().warn("Could not create isolated JDT classloader: {}", e.toString());
            return null;
        }
    }

    private boolean isJdtUrl(URL url) {
        if (!"file".equals(url.getProtocol()))
            return false;
        try {
            File file = new File(url.toURI());
            if (!file.isFile() || !file.getName().endsWith(".jar"))
                return false;
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("org/eclipse/jdt/")
                            || name.startsWith("org/eclipse/core/")
                            || name.startsWith("org/eclipse/text/")
                            || name.startsWith("org/eclipse/equinox/")
                            || name.startsWith("org/eclipse/osgi/")
                            || (HELPER_CLASS.replace('.', '/') + ".class").equals(name))
                        return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private File unsignedCopies(Set<URL> urls)
            throws IOException, NoSuchAlgorithmException, java.net.URISyntaxException {
        List<File> files = new ArrayList<>();
        StringBuilder keySource = new StringBuilder();
        for (URL url : urls) {
            File file = new File(url.toURI());
            files.add(file);
            keySource.append(file.getAbsolutePath()).append(':').append(file.lastModified()).append('\n');
        }
        String key = sha256(keySource.toString());
        File dir = new File(getProject().getGradle().getGradleUserHomeDir(),
                "caches/forgegradle/jdt-unsigned/" + key);
        if (!dir.isDirectory()) {
            if (!dir.mkdirs())
                throw new IOException("Could not create " + dir);
            for (File file : files)
                stripSignatures(file, new File(dir, file.getName()));
        }
        return dir;
    }

    private void stripSignatures(File source, File target) throws IOException {
        try (ZipFile zip = new ZipFile(source);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.startsWith("META-INF/SIG-")))
                    continue;
                output.putNextEntry(new ZipEntry(name));
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1)
                        output.write(buffer, 0, read);
                }
                output.closeEntry();
            }
        }
    }

    private String sha256(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : digest)
            result.append(String.format("%02x", value));
        return result.toString();
    }

    private String methodKey(String name, int argCount) {
        return name + "(" + argCount + ")";
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1)
            out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static final class ClassInfo {
        int access;
        final Map<String, MemberAccess> fields = new HashMap<>();
        final Map<String, MemberAccess> methods = new HashMap<>();
    }

    private static final class MemberAccess {
        final int access;
        final int argCount;

        MemberAccess(int access, int argCount) {
            this.access = access;
            this.argCount = argCount;
        }
    }

    private static final class ClassChange {
        boolean classChanged;
        boolean classVisibilityChanged;
        boolean classFinalChanged;
        int classAccess;
        final Map<String, MemberChange> fields = new HashMap<>();
        final Map<String, MemberChange> methods = new HashMap<>();

        boolean isEmpty() {
            return !classChanged && fields.isEmpty() && methods.isEmpty();
        }
    }

    private static final class MemberChange {
        final String name;
        final int argCount;
        final int access;
        final boolean visibilityChanged;
        final boolean finalChanged;

        MemberChange(String name, int argCount, int access, boolean visibilityChanged, boolean finalChanged) {
            this.name = name;
            this.argCount = argCount;
            this.access = access;
            this.visibilityChanged = visibilityChanged;
            this.finalChanged = finalChanged;
        }
    }
}
