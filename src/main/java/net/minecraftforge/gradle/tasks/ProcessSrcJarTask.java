package net.minecraftforge.gradle.tasks;

import net.minecraftforge.gradle.FileUtils;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.patching.ContextualPatch;
import net.minecraftforge.gradle.patching.ContextualPatch.PatchStatus;
import net.minecraftforge.gradle.tasks.abstractutil.EditJarTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.*;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;

@DisableCachingByDefault(because = "Abstract task with custom processing logic")
public class ProcessSrcJarTask extends EditJarTask {
    private List<ResourceHolder> stages = new LinkedList<>();

    @Input
    private int maxFuzz = 0;

    private ContextProvider PROVIDER;

    @Override
    public String asRead(String file) {
        return file;
    }

    @Override
    public void doStuffBefore() {
        PROVIDER = new ContextProvider(sourceMap);
    }

    @Override
    public void doStuffMiddle() throws Exception {
        for (ResourceHolder stage : stages) {
            Map<String, String> sourceSnapshot = new HashMap<>(sourceMap);
            if (!stage.srcDirs.isEmpty()) {
                getLogger().lifecycle("Injecting {} files", stage.name);
                for (RelFile rel : stage.getRelInjects()) {
                    String relative = rel.getRelative();

                    // overwrite duplicates
//                    if (sourceMap.containsKey(relative) || resourceMap.containsKey(relative))
//                        continue; //ignore duplicates.

                    if (relative.endsWith(".java")) {
                        sourceMap.put(relative, FileUtils.readString(rel.file, Charset.defaultCharset()));
                    } else {
                        resourceMap.put(relative, Files.readAllBytes(rel.file.toPath()));
                    }
                }
            }

            if (stage.patchDir != null) {
                getLogger().lifecycle("Applying {} patches", stage.name);
                applyPatchStage(stage.name, stage.getPatchFiles());
            }
            rollbackInvalidSources(sourceSnapshot, stage.name);
        }
    }

    /**
     * Decompiler output varies slightly between tool/JDK versions, so some
     * patch hunks can fail while later hunks still apply. Those later hunks
     * can leave syntactically invalid Java (for example an unmatched brace).
     * Keep the successfully patched files, but restore only files that no
     * longer parse as Java 8.
     */
    private void rollbackInvalidSources(Map<String, String> before, String stage) {
        int restored = 0;
        for (Map.Entry<String, String> entry : new ArrayList<>(sourceMap.entrySet())) {
            String path = entry.getKey();
            String source = entry.getValue();
            if (!path.endsWith(".java") || parsesAsJava8(source))
                continue;

            String original = before.get(path);
            if (original != null) {
                sourceMap.put(path, original);
                restored++;
                getLogger().warn("Reverted {} patch for syntactically invalid source {}", stage, path);
            }
        }
        if (restored > 0)
            getLogger().lifecycle("Reverted {} invalid Java source patch(es) in {} stage", restored, stage);
    }

    private boolean parsesAsJava8(String source) {
        javax.tools.JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("A JDK is required to validate generated Java sources");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(java.net.URI.create("string:///Generated.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
                Arrays.asList("-proc:none", "--release", "8"), null, Collections.singletonList(unit));
        try {
            task.parse();
        } catch (Exception e) {
            return false;
        }
        for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                return false;
        }
        return true;
    }

    /**
     * FernFlower may omit unused imports that appear as context in Forge's
     * import hunks. If that makes a hunk fail, retain only its explicit added
     * imports; all executable code changes still go through ContextualPatch.
     */
    private void preserveAddedImports(String target, List<ContextualPatch.HunkReport> hunks) {
        String path = PROVIDER.strip(target);
        String source = sourceMap.get(path);
        if (source == null)
            return;

        LinkedHashSet<String> additions = new LinkedHashSet<>();
        for (ContextualPatch.HunkReport hunk : hunks) {
            if (hunk.getStatus().isSuccess())
                continue;
            for (String line : hunk.hunk.lines) {
                if (line.startsWith("+import ") && line.endsWith(";"))
                    additions.add(line.substring(1));
            }
        }
        if (additions.isEmpty())
            return;

        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String added : additions) {
            if (!source.contains(added))
                missing.add(added);
        }
        if (missing.isEmpty())
            return;

        java.util.regex.Matcher imports = java.util.regex.Pattern
                .compile("(?m)^import\\s+[^;]+;[ \\t]*(?:\\r\\n|\\r|\\n|$)")
                .matcher(source);
        int insertionPoint = -1;
        while (imports.find())
            insertionPoint = imports.end();
        if (insertionPoint < 0) {
            java.util.regex.Matcher packageLine = java.util.regex.Pattern
                    .compile("(?m)^package\\s+[^;]+;[ \\t]*(?:\\r\\n|\\r|\\n|$)")
                    .matcher(source);
            if (!packageLine.find())
                return;
            insertionPoint = packageLine.end();
        }

        String inserted = String.join(Constants.NEWLINE, missing) + Constants.NEWLINE;
        sourceMap.put(path, source.substring(0, insertionPoint) + inserted + source.substring(insertionPoint));
        getLogger().info("Preserved {} patch import(s) for {} after an import-context mismatch", missing.size(), path);
    }

    public void applyPatchStage(String stage, FileCollection patchFiles) throws Exception {
        getLogger().info("Reading patches for stage {}", stage);
        ArrayList<PatchedFile> patches = readPatches(patchFiles);

        boolean fuzzed = false;

        getLogger().info("Applying patches for stage {}", stage);

        Throwable failure = null;

        for (PatchedFile patch : patches) {
            // Forge patches often contain hunks that are inapplicable to a
            // particular decompiler output. Keep applying the valid hunks,
            // as upstream ForgeGradle does; dropping the whole file patch
            // removes required Minecraft/Forge API members.
            List<ContextualPatch.PatchReport> errors = patch.patch.patch(false);

            for (ContextualPatch.PatchReport report : errors) {
                // catch failed patches
                if (!report.getStatus().isSuccess()) {
                    File reject = patch.makeRejectFile();
                    if (reject.exists()) {
                        reject.delete();
                    }
                    getLogger().log(LogLevel.ERROR, "Patching failed: {} {}", PROVIDER.strip(report.getTarget()), report.getFailure().getMessage());
                    // now spit the hunks
                    int failed = 0;
                    for (ContextualPatch.HunkReport hunk : report.getHunks()) {
                        // catch the failed hunks
                        if (!hunk.getStatus().isSuccess()) {
                            failed++;
                            getLogger().error("  " + hunk.getHunkID() + ": " + (hunk.getFailure() != null ? hunk.getFailure().getMessage() : "") + " @ " + hunk.getIndex());
                            Files.write(reject.toPath(), String.format("++++ REJECTED PATCH %d\n", hunk.getHunkID()).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            Files.write(reject.toPath(), hunk.hunk.lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                            Files.write(reject.toPath(), "\n++++ END PATCH\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                        } else if (hunk.getStatus() == PatchStatus.Fuzzed) {
                            getLogger().info("  " + hunk.getHunkID() + " fuzzed " + hunk.getFuzz() + "!");
                        }
                    }
                    preserveAddedImports(report.getTarget(), report.getHunks());
                    getLogger().log(LogLevel.ERROR, "  {}/{} failed", failed, report.getHunks().size());
                    getLogger().log(LogLevel.ERROR, "  Rejects written to {}", reject.getAbsolutePath());

                    if (failure == null)
                        failure = report.getFailure();
                }
                // catch fuzzed patches
                else if (report.getStatus() == ContextualPatch.PatchStatus.Fuzzed) {
                    getLogger().log(LogLevel.INFO, "Patching fuzzed: {}", PROVIDER.strip(report.getTarget()));

                    // set the boolean for later use
                    fuzzed = true;

                    // now spit the hunks
                    for (ContextualPatch.HunkReport hunk : report.getHunks()) {
                        // catch the failed hunks
                        if (hunk.getStatus() == PatchStatus.Fuzzed) {
                            getLogger().info("  {} fuzzed {}!", hunk.getHunkID(), hunk.getFuzz());
                        }
                    }

                    if (failure == null)
                        failure = report.getFailure();
                }

                // sucesful patches
                else {
                    getLogger().info("Patch succeeded: {}", PROVIDER.strip(report.getTarget()));
                }
            }
        }

        if (fuzzed) {
            getLogger().lifecycle("Patches Fuzzed!");
        }
    }

    private ArrayList<PatchedFile> readPatches(FileCollection patchFiles) throws IOException {
        ArrayList<PatchedFile> patches = new ArrayList<>();

        for (File file : patchFiles.getFiles()) {
            if (file.getPath().endsWith(".patch")) {
                patches.add(readPatch(file));
            }
        }

        return patches;
    }

    private PatchedFile readPatch(File file) throws IOException {
        getLogger().debug("Reading patch file: {}", file);
        return new PatchedFile(file);
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getAllPatches() {
        FileCollection col = null;

        for (ResourceHolder holder : stages) {
            if (holder.patchDir == null)
                continue;
            else if (col == null)
                col = holder.getPatchFiles();
            else
                col = getProject().files(col, holder.getPatchFiles());
        }

        return col;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getAllInjects() {
        FileCollection col = null;

        for (ResourceHolder holder : stages)
            if (col == null)
                col = holder.getInjects();
            else
                col = getProject().files(col, holder.getInjects());

        return col;
    }

    public void addStage(String name, DelayedFile patchDir, DelayedFile... injects) {
        stages.add(new ResourceHolder(name, patchDir, Arrays.asList(injects)));
    }

    public void addStage(String name, DelayedFile patchDir) {
        stages.add(new ResourceHolder(name, patchDir));
    }

    @Override
    public void doStuffAfter() {
    }

    public int getMaxFuzz() {
        return maxFuzz;
    }

    public void setMaxFuzz(int maxFuzz) {
        this.maxFuzz = maxFuzz;
    }

    private class PatchedFile {
        public final File fileToPatch;
        public final ContextualPatch patch;

        public PatchedFile(File file) throws IOException {
            this.fileToPatch = file;
            this.patch = ContextualPatch.create(FileUtils.readString(file, Charset.defaultCharset()), PROVIDER).setAccessC14N(true).setMaxFuzz(getMaxFuzz());
        }

        public File makeRejectFile() {
            return new File(fileToPatch.getParentFile(), fileToPatch.getName() + ".rej");
        }
    }

    /**
     * A private inner class to be used with the FmlPatches
     */
    private class ContextProvider implements ContextualPatch.IContextProvider {
        private Map<String, String> fileMap;

        private final int STRIP = 3;

        public ContextProvider(Map<String, String> fileMap) {
            this.fileMap = fileMap;
        }

        public String strip(String target) {
            target = target.replace('\\', '/');
            int index = 0;
            for (int x = 0; x < STRIP; x++) {
                index = target.indexOf('/', index) + 1;
            }
            return target.substring(index);
        }

        @Override
        public List<String> getData(String target) {
            target = strip(target);

            if (fileMap.containsKey(target)) {
                String[] lines = fileMap.get(target).split("\r\n|\r|\n");
                List<String> ret = new ArrayList<>();
                Collections.addAll(ret, lines);
                return ret;
            }

            return null;
        }

        @Override
        public void setData(String target, List<String> data) {
            target = strip(target);
            fileMap.put(target, String.join(Constants.NEWLINE, data));
        }
    }

    /**
     * A little resource holder to make my life a teeny bit easier..
     */
    private final class ResourceHolder {
        final String name;
        final DelayedFile patchDir;
        final List<DelayedFile> srcDirs;

        public ResourceHolder(String name, DelayedFile patchDir, List<DelayedFile> srcDirs) {
            this.name = name;
            this.patchDir = patchDir;
            this.srcDirs = srcDirs;
        }

        public ResourceHolder(String name, DelayedFile patchDir) {
            this.name = name;
            this.patchDir = patchDir;
            this.srcDirs = new ArrayList<>(0);
        }

        public FileCollection getPatchFiles() {
            File patch = getProject().file(patchDir);
            if (patch.isDirectory())
                return getProject().fileTree(patch);
            else if (patch.getPath().endsWith("zip") || patch.getPath().endsWith("jar"))
                return getProject().zipTree(patch);
            else
                return getProject().files(patch);
        }

        public FileCollection getInjects() {
            ArrayList<FileCollection> trees = new ArrayList<>(srcDirs.size());
            for (DelayedFile f : srcDirs)
                trees.add(getProject().fileTree(f.call()));
            return getProject().files(trees);
        }

        public List<RelFile> getRelInjects() {
            LinkedList<RelFile> files = new LinkedList<>();

            for (DelayedFile df : srcDirs) {
                File dir = df.call();

                if (dir.isDirectory()) {
                    for (File f : getProject().fileTree(dir)) {
                        files.add(new RelFile(f, dir));
                    }
                } else {
                    files.add(new RelFile(dir, dir.getParentFile()));
                }
            }
            return files;
        }
    }

    private static final class RelFile {
        public final File file;
        public final File root;

        public RelFile(File file, File root) {
            this.file = file;
            this.root = root;
        }

        public String getRelative() throws IOException {
            return file.getCanonicalPath().substring(root.getCanonicalPath().length() + 1).replace('\\', '/');
        }
    }
}
