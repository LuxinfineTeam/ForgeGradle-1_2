package net.minecraftforge.gradle.tasks.abstractutil;

import com.google.common.io.ByteStreams;
import net.minecraftforge.gradle.delayed.DelayedFile;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@UntrackedTask(because = "Abstract base class for jar editing tasks")
public abstract class EditJarTask extends CachedTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    protected DelayedFile inJar;

    @OutputFile
    @Cached
    protected DelayedFile outJar;

    protected HashMap<String, String> sourceMap = new HashMap<>();
    protected HashMap<String, byte[]> resourceMap = new HashMap<>();

    @TaskAction
    public void doTask() throws Throwable {
        doStuffBefore();
        getLogger().debug("Reading jar: " + inJar);
        readJarAndClean(getInJar());

        doStuffMiddle();

        getLogger().debug("Saving jar: " + outJar);
        saveJar(getOutJar());
        doStuffAfter();
    }

    public abstract String asRead(String file);

    /**
     * Do Stuff before the jar is read
     *
     * @throws Exception for convenience
     */
    public abstract void doStuffBefore() throws Exception;

    /**
     * Do Stuff after the jar is read, but before it is written.
     *
     * @throws Exception for convenience
     */
    public abstract void doStuffMiddle() throws Exception;

    /**
     * Do Stuff after the jar is Written
     *
     * @throws Exception for convenience
     */
    public abstract void doStuffAfter() throws Exception;

    private void readJarAndClean(final File jar) throws IOException {
        // begin reading jar
        final ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar.toPath()));
        ZipEntry entry;
        String fileStr;

        while ((entry = zin.getNextEntry()) != null) {
            // no META or dirs. wel take care of dirs later.
            if (entry.getName().contains("META-INF")) {
                continue;
            }

            // resources or directories.
            if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                resourceMap.put(entry.getName(), ByteStreams.toByteArray(zin));
            } else {
                // source!
                fileStr = new String(ByteStreams.toByteArray(zin), Charset.defaultCharset());

                fileStr = asRead(fileStr);

                sourceMap.put(entry.getName(), fileStr);
            }
        }

        zin.close();
    }

    private void saveJar(File output) throws IOException {
        //Чисто технически IDEA может автоматически подтянуть еще не доделанный .jar как источник сорцев, получить
        //ошибку из-за "битого" архива, и этот файл тупо залипнет до полной инвалидации кешей IDE. Чтобы этого точно
        //не произошло - генерируем файл во временной папке, а затем переносим уже после полной готовности
        Path outputPath = output.toPath().toAbsolutePath();
        Path parent = outputPath.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, output.getName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (JarOutputStream zout = new JarOutputStream(Files.newOutputStream(temporary))) {
                // write in resources
                for (Map.Entry<String, byte[]> entry : resourceMap.entrySet()) {
                    zout.putNextEntry(new JarEntry(entry.getKey()));
                    zout.write(entry.getValue());
                    zout.closeEntry();
                }

                // write in sources
                for (Map.Entry<String, String> entry : sourceMap.entrySet()) {
                    zout.putNextEntry(new JarEntry(entry.getKey()));
                    zout.write(entry.getValue().getBytes());
                    zout.closeEntry();
                }
            }

            try {
                Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved)
                Files.deleteIfExists(temporary);
        }
    }

    public File getInJar() {
        return inJar.call();
    }

    public void setInJar(DelayedFile inJar) {
        this.inJar = inJar;
    }

    public File getOutJar() {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

    @Internal
    public HashMap<String, byte[]> getResourceMap() {
        return resourceMap;
    }

    public void setResourceMap(HashMap<String, byte[]> resourceMap) {
        this.resourceMap = resourceMap;
    }

    @Internal
    public HashMap<String, String> getSourceMap() {
        return sourceMap;
    }

    public void setSourceMap(HashMap<String, String> sourceMap) {
        this.sourceMap = sourceMap;
    }
}
