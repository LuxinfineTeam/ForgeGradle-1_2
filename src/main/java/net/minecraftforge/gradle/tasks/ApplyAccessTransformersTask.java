package net.minecraftforge.gradle.tasks;

import net.md_5.specialsource.AccessMap;
import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.RemapperProcessor;
import net.minecraftforge.gradle.delayed.DelayedFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies only access transformers to an already deobfuscated jar.
 * No SRG remapping, no exceptor and no decompilation: the input is the
 * clean, shared jar taken from maven-local, the output is the per-project
 * jar in the dirty dir. With no transformers this is a plain copy.
 */
@UntrackedTask(because = "Applies project access transformers to a cached jar")
public class ApplyAccessTransformersTask extends DefaultTask {
    private DelayedFile inJar;
    private DelayedFile outJar;
    private DelayedFile fieldCsv;
    private DelayedFile methodCsv;

    private final ArrayList<DelayedFile> ats = new ArrayList<>();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getInJar() {
        return inJar.call();
    }

    public void setInJar(DelayedFile inJar) {
        this.inJar = inJar;
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

    @Internal
    public List<DelayedFile> getMappingCsvs() {
        List<DelayedFile> files = new ArrayList<>();
        if (fieldCsv != null)
            files.add(fieldCsv);
        if (methodCsv != null)
            files.add(methodCsv);
        return files;
    }

    public void setMappingCsvs(DelayedFile fieldCsv, DelayedFile methodCsv) {
        this.fieldCsv = fieldCsv;
        this.methodCsv = methodCsv;
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
        File out = getOutJar();
        out.getParentFile().mkdirs();
        Set<File> transformers = new HashSet<>();
        for (DelayedFile at : ats) {
            File file = getProject().file(at).getCanonicalFile();
            if (file.isFile())
                transformers.add(file);
        }
        if (transformers.isEmpty()) {
            Files.copy(in.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        AccessMap accessMap = loadAccessMap(transformers, readRenames());
        JarMapping mapping = new JarMapping();
        Jar input = Jar.init(in);
        RemapperProcessor processor = new RemapperProcessor(null, null, accessMap);
        JarRemapper remapper = new JarRemapper(null, mapping, processor);
        remapper.remapJar(input, out);
    }

    /**
     * AT files for 1.7 use searge member names, while the recompiled jar uses
     * MCP names. Register both spellings so the same transformer set works for
     * the SRG jar and for the recompiled jar.
     */
    private AccessMap loadAccessMap(Set<File> transformers, Map<String, String> renames) throws IOException {
        AccessMap accessMap = new AccessMap();
        for (File at : transformers) {
            try (BufferedReader reader = Files.newBufferedReader(at.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    if (comment != -1)
                        line = line.substring(0, comment);
                    line = line.trim();
                    int space = line.indexOf(' ');
                    if (space < 0)
                        continue;
                    String access = line.substring(0, space).trim();
                    String symbol = line.substring(space + 1).trim();
                    if (access.isEmpty() || symbol.isEmpty())
                        continue;
                    List<String> symbols = normalizeSymbols(symbol, renames);
                    for (String normalized : symbols)
                        accessMap.addAccessChange(access + " " + normalized);
                }
            }
        }
        return accessMap;
    }

    /**
     * Forge ATs use {@code class member} notation. SpecialSource expects
     * {@code class.member}, so join the parts the same way ProcessJarTask does.
     * Also return the MCP spelling of searge member names: the SRG jar needs
     * the original name, the recompiled jar needs the mapped one.
     */
    private List<String> normalizeSymbols(String symbol, Map<String, String> renames) {
        String[] parts = symbol.trim().split("\\s+");
        if (parts.length < 2)
            return java.util.Collections.singletonList(parts[0]);

        String owner = parts[0];
        String memberPart = parts[1];
        StringBuilder suffix = new StringBuilder();
        int paren = memberPart.indexOf('(');
        String member = paren == -1 ? memberPart : memberPart.substring(0, paren);
        if (paren != -1)
            suffix.append(memberPart.substring(paren));
        for (int i = 2; i < parts.length; i++) {
            if (suffix.length() > 0)
                suffix.append(' ');
            suffix.append(parts[i]);
        }

        List<String> symbols = new ArrayList<>();
        symbols.add(owner + "." + member + suffix);
        String renamed = renames.get(member);
        if (renamed != null && !renamed.equals(member))
            symbols.add(owner + "." + renamed + suffix);
        return symbols;
    }

    private Map<String, String> readRenames() throws IOException {
        Map<String, String> renames = new HashMap<>();
        for (DelayedFile csvFile : getMappingCsvs()) {
            File csv = getProject().file(csvFile);
            if (!csv.isFile())
                continue;
            try (BufferedReader reader = Files.newBufferedReader(csv.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",", -1);
                    if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty())
                        renames.put(parts[0], parts[1]);
                }
            }
        }
        return renames;
    }

    @Internal
    public DelayedFile getDelayedOutput() {
        return outJar;
    }
}
