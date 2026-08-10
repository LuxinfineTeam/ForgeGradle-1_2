package net.minecraftforge.gradle.tasks.dev;

import net.minecraftforge.gradle.ExecHelper;
import net.minecraftforge.gradle.delayed.DelayedFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

@DisableCachingByDefault(because = "Generates changelog from git submodule")
public class SubmoduleChangelogTask extends DefaultTask {
    private DelayedFile submodule;
    @Input
    private String moduleName;
    @Input
    private String prefix;

    private File outputFile;

    @TaskAction
    public void doTask() throws IOException {
        getLogger().lifecycle("");

        String[] output = runGit(getProject().getProjectDir(), "--no-pager", "diff", "--no-color", "--", getSubmodule().getName());
        if (output.length == 0) {
            getLogger().lifecycle("Could not grab submodule changes");
            return;
        }

        String start = null;
        String end = null;
        for (String line : output) {
            if (line.startsWith("-Subproject commit")) {
                start = line.substring(19);
            } else if (line.startsWith("+Subproject commit")) {
                end = line.substring(19);
                if (line.endsWith("-dirty")) {
                    end = end.substring(0, end.length() - 6);
                }
            }
        }

        if (start == null && end == null) {
            getLogger().lifecycle("Could not extract start and end range");
            return;
        }

        output = runGit(getSubmodule(), "--no-pager", "log", "--reverse", "--pretty=oneline", start + "..." + end);
        getLogger().lifecycle("Updated " + getModuleName() + ":");

        BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), Charset.defaultCharset());

        for (String line : output) {
            String out = getPrefix() + "@" + line;
            getLogger().lifecycle(out);
            writer.write(out);
            writer.newLine();
        }

        writer.flush();
        writer.close();
        getLogger().lifecycle("");
    }

    private String[] runGit(final File dir, final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExecHelper.exec(getProject(), exec -> {
            exec.setExecutable("git");
            exec.args((Object[]) args);
            exec.setStandardOutput(out);
            exec.setWorkingDir(dir);
        });

        return out.toString().trim().split("\n");
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public File getSubmodule() {
        return submodule.call();
    }

    public void setSubmodule(DelayedFile submodule) {
        this.submodule = submodule;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String name) {
        this.moduleName = name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    @OutputFile
    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }
}
