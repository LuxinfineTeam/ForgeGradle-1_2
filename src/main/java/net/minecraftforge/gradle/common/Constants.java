package net.minecraftforge.gradle.common;

import groovy.lang.Closure;
import net.minecraftforge.gradle.ProjectBuildDirHelper;
import net.minecraftforge.gradle.StringUtils;
import net.minecraftforge.gradle.json.version.OS;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.specs.Spec;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Constants {
    // OS
    public enum SystemArch {
        BIT_32, BIT_64;

        public String toString() {
            return StringUtils.lower(name()).replace("bit_", "");
        }
    }

    public static final OS OPERATING_SYSTEM = OS.CURRENT;
    public static final SystemArch SYSTEM_ARCH = getArch();
    public static final String HASH_FUNC = "MD5";
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11";

    // extension nam
    public static final String EXT_NAME_MC = "minecraft";
    public static final String EXT_NAME_JENKINS = "jenkins";

    /**
     * @deprecated unused
     */
    @SuppressWarnings("serial")
    @Deprecated
    public static final Closure<Boolean> CALL_FALSE = new Closure<Boolean>(null) {
        public Boolean call(Object o) {
            return false;
        }
    };

    public static final Spec<? super Task> SPEC_FALSE = e -> false;

    // urls
    /** @deprecated AWS s3 Minecraft.Download is not live */
    public static final String MC_JSON_URL = "https://s3.amazonaws.com/Minecraft.Download/versions/{MC_VERSION}/{MC_VERSION}.json";
    /** @deprecated AWS s3 Minecraft.Download is not live */
    public static final String MC_JAR_URL = "https://s3.amazonaws.com/Minecraft.Download/versions/{MC_VERSION}/{MC_VERSION}.jar";
    /** @deprecated AWS s3 Minecraft.Download is not live */
    public static final String MC_SERVER_URL = "https://s3.amazonaws.com/Minecraft.Download/versions/{MC_VERSION}/minecraft_server.{MC_VERSION}.jar";
    public static final String MC_JSON_INDEX_URL = "https://piston-meta.mojang.com/mc/game/version_manifest.json";
    public static final String MCP_URL = "https://files.minecraftforge.net/fernflower-fix-1.0.zip";
    public static final String ASSETS_URL = "https://resources.download.minecraft.net";
    public static final String LIBRARY_URL = "https://libraries.minecraft.net/";
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net";
    /** @deprecated AWS s3 Minecraft.Download is not live */
    public static final String ASSETS_INDEX_URL = "https://s3.amazonaws.com/Minecraft.Download/indexes/{ASSET_INDEX}.json";

    // MCP things
    public static final String CONFIG_MCP_DATA = "mcpSnapshotDataConfig";
    public static final String MCP_JSON_URL     = FORGE_MAVEN + "/de/oceanlabs/mcp/versions.json";

    // things in the cache dir.
    public static final String NATIVES_DIR = "{CACHE_DIR}/minecraft/net/minecraft/minecraft_natives/{MC_VERSION}";
    public static final String MCP_DATA_DIR = "{CACHE_DIR}/minecraft/de/oceanlabs/mcp/mcp_{MAPPING_CHANNEL}/{MAPPING_VERSION}/";
    public static final String JAR_CLIENT_FRESH = "{CACHE_DIR}/minecraft/net/minecraft/minecraft/{MC_VERSION}/minecraft-{MC_VERSION}.jar";
    public static final String JAR_SERVER_FRESH = "{CACHE_DIR}/minecraft/net/minecraft/minecraft_server/{MC_VERSION}/minecraft_server-{MC_VERSION}.jar";
    public static final String JAR_MERGED = "{CACHE_DIR}/minecraft/net/minecraft/minecraft_merged/{MC_VERSION}/minecraft_merged-{MC_VERSION}.jar";
    public static final String FERNFLOWER = "{CACHE_DIR}/minecraft/fernflower-fixed.jar";
    public static final String EXCEPTOR = "{CACHE_DIR}/minecraft/exceptor.jar";
    public static final String ASSETS = "{CACHE_DIR}/minecraft/assets";
    public static final String JSONS_DIR = "{CACHE_DIR}/minecraft/versionJsons";
    public static final String VERSION_JSON_INDEX = JSONS_DIR + "/index.json";
    public static final String VERSION_JSON = JSONS_DIR + "/{MC_VERSION}.json";

    // util
    public static final String NEWLINE = System.getProperty("line.separator");

    // helper methods
    public static File cacheFile(Project project, String... otherFiles) {
        return Constants.file(project.getGradle().getGradleUserHomeDir(), otherFiles);
    }

    public static File file(File file, String... otherFiles) {
        String othersJoined = String.join("/", otherFiles);
        return new File(file, othersJoined);
    }

    public static File file(String... otherFiles) {
        String othersJoined = String.join("/", otherFiles);
        return new File(othersJoined);
    }

    public static List<String> getClassPath() {
        URL[] urls = ((URLClassLoader) Constants.class.getClassLoader()).getURLs();

        ArrayList<String> list = new ArrayList<>();
        for (URL url : urls) {
            list.add(url.getPath());
        }
        return list;
    }

    public static File getMinecraftDirectory() {
        String userDir = System.getProperty("user.home");

        switch (OPERATING_SYSTEM) {
            case LINUX:
                return new File(userDir, ".minecraft/");
            case WINDOWS:
                String appData = System.getenv("APPDATA");
                String folder = appData != null ? appData : userDir;
                return new File(folder, ".minecraft/");
            case OSX:
                return new File(userDir, "Library/Application Support/minecraft");
            default:
                return new File(userDir, "minecraft/");
        }
    }

    private static SystemArch getArch() {
        String name = StringUtils.lower(System.getProperty("os.arch"));
        if (name.contains("64")) {
            return SystemArch.BIT_64;
        } else {
            return SystemArch.BIT_32;
        }
    }

    public static String hash(File file) {
        if (file.getPath().endsWith(".zip") || file.getPath().endsWith(".jar"))
            return hashZip(file, HASH_FUNC);
        else
            return hash(file, HASH_FUNC);
    }

    public static List<String> hashAll(File file) {
        List<String> list = new ArrayList<>();

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    list.addAll(hashAll(f));
                }
            }
        } else if (!file.getName().equals(".cache")) {
            list.add(hash(file));
        }

        return list;
    }

    public static String hash(File file, String function) {
        try (InputStream fis = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance(function);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String hashZip(File file, String function) {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            MessageDigest hasher = MessageDigest.getInstance(function);

            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                hasher.update(entry.getName().getBytes());
                int bytesRead;
                while ((bytesRead = zin.read(buffer)) != -1) {
                    hasher.update(buffer, 0, bytesRead);
                }
            }

            return bytesToHex(hasher.digest());
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String hash(String str) {
        return hash(str.getBytes());
    }

    public static String hash(byte[] bytes) {
        return hash(bytes, HASH_FUNC);
    }

    public static String hash(byte[] bytes, String function) {
        try {
            MessageDigest complete = MessageDigest.getInstance(function);
            byte[] hash = complete.digest(bytes);
            return bytesToHex(hash);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_ARRAY[v >>> 4];
            hexChars[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * @deprecated breaks task cache
     */
    @Deprecated
    public static PrintStream getTaskLogStream(Project project, String name) {
        return getTaskLogStream(ProjectBuildDirHelper.getBuildDir(project), name);
    }

    public static PrintStream getTaskLogStream(File buildDir, String name) {
        final File taskLogs = new File(buildDir, "taskLogs");
        taskLogs.mkdirs();
        final File logFile = new File(taskLogs, name);
        logFile.delete(); //Delete the old log
        try {
            return new PrintStream(logFile);
        } catch (FileNotFoundException ignored) {}
        return null; // Should never get to here
    }


    /**
     * Throws a null runtime exception if the resource isnt found.
     *
     * @param resource String name of the resource your looking for
     * @return URL
     */
    public static URL getResource(String resource) {
        ClassLoader loader = BaseExtension.class.getClassLoader();

        if (loader == null)
            throw new RuntimeException("ClassLoader is null! IMPOSSIBRU");

        URL url = loader.getResource(resource);

        if (url == null)
            throw new RuntimeException("Resource " + resource + " not found");

        return url;
    }
}
