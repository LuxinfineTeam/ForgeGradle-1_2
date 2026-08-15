package net.minecraftforge.gradle.tasks.abstractutil;

import groovy.lang.Closure;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.bundling.Jar;

import javax.inject.Inject;

/**
 * @deprecated not used anywhere
 */
@Deprecated
@UntrackedTask(because = "Abstract task with custom manifest handling")
public abstract class DelayedJar extends Jar {
    private Closure<?> closure = null;

    @Override
    public void copy() {
        if (closure != null) {
            super.manifest(closure);
        }
        super.copy();
    }

    public void setManifest(Closure<?> closure) {
        this.closure = closure;
    }

    @Override
    public Property<Long> getReproducibleFileTimestamp() {
        return getProject().getObjects().property(Long.class);
    }

    @Override
    public Property<String> getArchiveClassifier() {
        return getProject().getObjects().property(String.class);
    }

    @Override
    public Property<String> getArchiveExtension() {
        Property<String> prop = getProject().getObjects().property(String.class);
        prop.set("jar");
        return prop;
    }

    @Override
    public Property<String> getArchiveVersion() {
        return getProject().getObjects().property(String.class);
    }

    @Override
    public Property<String> getArchiveBaseName() {
        return getProject().getObjects().property(String.class);
    }

    @Override
    public Property<String> getArchiveAppendix() {
        return getProject().getObjects().property(String.class);
    }

    @Override
    public DirectoryProperty getDestinationDirectory() {
        return getProject().getObjects().directoryProperty();
    }

    @Override
    public Property<String> getArchiveFileName() {
        return getProject().getObjects().property(String.class);
    }

    @Inject
    public abstract FileSystemOperations getFileSystemOperations();
}
