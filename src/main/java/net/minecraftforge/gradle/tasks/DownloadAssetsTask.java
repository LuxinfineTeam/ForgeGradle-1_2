package net.minecraftforge.gradle.tasks;

import groovy.lang.Closure;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import net.minecraftforge.gradle.json.version.AssetIndex;
import net.minecraftforge.gradle.json.version.AssetIndex.AssetEntry;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@DisableCachingByDefault(because = "Downloads assets from remote server")
public class DownloadAssetsTask extends DefaultTask {
    DelayedFile assetsDir;

    Supplier<AssetIndex> index;

    @Input
    DelayedString indexName;

    private boolean errored = false;
    private File virtualRoot = null;
    private final List<Asset> assetsToDownload = new ArrayList<>();
    private final File minecraftDir = new File(Constants.getMinecraftDirectory(), "assets/objects");

    private static final int MAX_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 8);
    private static final int MAX_TRIES = 5;

    @TaskAction
    public void doTask() throws ParserConfigurationException, SAXException, IOException, InterruptedException {
        File out = new File(getAssetsDir(), "objects");
        out.mkdirs();

        AssetIndex index = getIndex();

        // check virtual
        if (index.virtual) {
            virtualRoot = new File(getAssetsDir(), "virtual/" + getIndexName());
            virtualRoot.mkdirs();
        }

        for (Entry<String, AssetEntry> e : index.objects.entrySet()) {
            Asset asset = new Asset(e.getKey(), e.getValue().hash, e.getValue().size);
            File file_hashed = new File(out, asset.path);
            File file_virtual = new File(virtualRoot, asset.name);

            // exists but not the right size?? delete
            if (file_hashed.exists() && file_hashed.length() != asset.size)
                file_hashed.delete();

            // File or virtual doesnt exist? add to the list.
            if (!file_hashed.exists()) {
                assetsToDownload.add(asset);
                continue;
            }

            if (index.virtual) {
                if (file_virtual.exists() && (file_virtual.length() != asset.size || !asset.hash.equalsIgnoreCase(Constants.hash(file_virtual, "SHA")))) {
                    file_virtual.delete();
                }

                if (!file_virtual.exists()) {
                    assetsToDownload.add(asset);
                }
            }
        }

        getLogger().debug("Finished parsing JSON");
        int total = assetsToDownload.size();
        getLogger().debug("Files Missing: " + total + "/" + index.objects.size());

        if (total == 0) {
            getLogger().lifecycle("All assets are up to date");
            return;
        }

        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS)) {
            List<Future<?>> futures = new ArrayList<>();

            for (Asset asset : assetsToDownload) {
                futures.add(executor.submit(() -> {
                    try {
                        downloadAsset(asset);
                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == total) {
                            getLogger().lifecycle("Progress: " + done + "/" + total + " (" + (done * 100 / total) + "%)");
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        getLogger().error("Failed to download asset: " + asset.name, e);
                    }
                }));
            }

            // Ожидание завершения всех задач
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    getLogger().error("Error during asset download", e.getCause());
                }
            }
        }

        if (errors.get() > 0) {
            getLogger().error("Failed to download " + errors.get() + " assets!");
            this.setDidWork(false);
            errored = true;
        } else {
            getLogger().lifecycle("Successfully downloaded all " + total + " assets");
        }
    }

    private void downloadAsset(Asset asset) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            try {
                File file = new File(getAssetsDir(), "objects/" + asset.path);

                if (file.exists() && file.length() != asset.size) {
                    file.delete();
                }

                if (!file.exists()) {
                    file.getParentFile().mkdirs();

                    File localMc = new File(minecraftDir, asset.path);
                    BufferedInputStream stream;

                    // check for local copy
                    if (localMc.exists() && Constants.hash(localMc, "SHA").equals(asset.hash)) {
                        stream = new BufferedInputStream(Files.newInputStream(localMc.toPath()));
                    } else {
                        stream = new BufferedInputStream(new URL(Constants.ASSETS_URL + "/" + asset.path).openStream());
                    }

                    try {
                        Files.copy(stream, file.toPath());
                    } finally {
                        stream.close();
                    }
                }

                String hash = Constants.hash(file, "SHA");
                if (asset.hash.equals(hash)) {
                    // Success - copy to virtual if needed
                    if (virtualRoot != null) {
                        File virtual = new File(virtualRoot, asset.name);
                        virtual.getParentFile().mkdirs();
                        if (virtual.exists() && !Constants.hash(virtual, "SHA").equalsIgnoreCase(asset.hash)) {
                            virtual.delete();
                        }

                        if (!virtual.exists()) {
                            Files.copy(file.toPath(), virtual.toPath());
                        }
                    }
                    return; // Success
                } else {
                    file.delete();
                    lastException = new IOException("Hash mismatch for " + asset.name + ": expected " + asset.hash + ", got " + hash);
                    if (attempt < MAX_TRIES) {
                        getLogger().warn("Download attempt " + attempt + " failed for " + asset.name + ", retrying...");
                    }
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_TRIES) {
                    getLogger().warn("Download attempt " + attempt + " failed for " + asset.name + ": " + e.getMessage());
                }
            }
        }

        throw new Exception("Failed to download " + asset.name + " after " + MAX_TRIES + " attempts", lastException);
    }

    @OutputDirectory
    public File getAssetsDir() {
        return assetsDir.call();
    }

    public void setAssetsDir(DelayedFile assetsDir) {
        this.assetsDir = assetsDir;
    }

    @Input
    public AssetIndex getIndex() {
        return index.get();
    }

    /**
     * @deprecated use {@link #setIndex(Supplier)} variant
     */
    @Deprecated
    public void setIndex(@Deprecated Closure<AssetIndex> index) {
        this.index = index::call;
    }

    public void setIndex(Supplier<AssetIndex> index) {
        this.index = index;
    }

    public String getIndexName() {
        return indexName.call();
    }

    public void setIndexName(DelayedString indexName) {
        this.indexName = indexName;
    }

    private static class Asset {
        public final String name;
        public final String path;
        public final String hash;
        public final long size;

        Asset(String name, String hash, long size) {
            this.name = name;
            this.path = hash.substring(0, 2) + "/" + hash;
            this.hash = hash.toLowerCase();
            this.size = size;
        }
    }
}
