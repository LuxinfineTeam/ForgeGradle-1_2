package net.minecraftforge.gradle.tasks.abstractutil;

import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@CacheableTask
public class DownloadTask extends DefaultTask {
    @Input
    private DelayedString url;
    @OutputFile
    private DelayedFile output;

    @TaskAction
    public void doTask() throws IOException, URISyntaxException {
        File output = getOutput();
        output.getParentFile().mkdirs();

        getLogger().debug("Downloading " + getUrl() + " to " + output);

        // TODO: check etags... maybe?

        HttpURLConnection connect = (HttpURLConnection) new URI(getUrl()).toURL().openConnection();
        connect.setRequestProperty("User-Agent", Constants.USER_AGENT);
        connect.setInstanceFollowRedirects(true);

        try (InputStream inStream = connect.getInputStream()) {
            Files.copy(inStream, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        getLogger().info("Download complete");
    }

    public File getOutput() {
        return output.call();
    }

    public void setOutput(DelayedFile output) {
        this.output = output;
    }

    public String getUrl() {
        return url.call();
    }

    public void setUrl(DelayedString url) {
        this.url = url;
    }
}
