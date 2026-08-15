package net.minecraftforge.gradle.user.patch;

import net.minecraftforge.gradle.JavaExtensionHelper;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.ProcessJarTask;
import net.minecraftforge.gradle.tasks.ProcessSrcJarTask;
import net.minecraftforge.gradle.tasks.RemapSourcesTask;
import net.minecraftforge.gradle.tasks.user.ApplyBinPatchesTask;
import net.minecraftforge.gradle.user.UserBasePlugin;
import net.minecraftforge.gradle.user.UserConstants;
import org.apache.tools.ant.types.Commandline;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static net.minecraftforge.gradle.common.Constants.JAR_MERGED;
import static net.minecraftforge.gradle.user.UserConstants.CLASSIFIER_DECOMPILED;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.*;

public abstract class UserPatchBasePlugin extends UserBasePlugin<UserPatchExtension> {
    @Override
    public void applyPlugin() {
        super.applyPlugin();

        // add the binPatching task
        {
            ApplyBinPatchesTask task = makeTask("applyBinPatches", ApplyBinPatchesTask.class);
            task.setInJar(delayedFile(JAR_MERGED));
            task.setOutJar(delayedFile(JAR_BINPATCHED));
            task.setPatches(delayedFile(BINPATCHES));
            task.setClassesJar(delayedFile(BINARIES_JAR));
            task.setResources(delayedFileTree(RES_DIR));
            task.dependsOn("mergeJars");

            project.getTasks().getByName("deobfBinJar").dependsOn(task);

            ProcessJarTask deobf = (ProcessJarTask) project.getTasks().getByName("deobfBinJar").dependsOn(task);
            deobf.setInJar(delayedFile(JAR_BINPATCHED));
            deobf.dependsOn(task);
        }

        // add source patching task
        {
            DelayedFile decompOut = delayedDirtyFile(null, CLASSIFIER_DECOMPILED, "jar", false);
            DelayedFile processed = delayedDirtyFile(null, CLASSIFIER_PATCHED, "jar", false);

            ProcessSrcJarTask patch = makeTask("processSources", ProcessSrcJarTask.class);
            patch.dependsOn("decompile");
            patch.setInJar(decompOut);
            patch.setOutJar(processed);
            configurePatching(patch);

            RemapSourcesTask remap = (RemapSourcesTask) project.getTasks().getByName("remapJar");
            remap.setInJar(processed);
            remap.dependsOn(patch);
        }
    }

    @Override
    public final void applyOverlayPlugin() {
    }

    @Override
    public final boolean canOverlayPlugin() {
        return false;
    }

    @Override
    public final UserPatchExtension getOverlayExtension() {
        return null; // nope.
    }

    /**
     * Allows for the configuration of tasks in AfterEvaluate
     */
    @Override
    protected void delayedTaskConfig() {
        // add src ATs
        ProcessJarTask binDeobf = (ProcessJarTask) project.getTasks().getByName("deobfBinJar");
        ProcessJarTask decompDeobf = (ProcessJarTask) project.getTasks().getByName("deobfuscateJar");

        // ATs from the ExtensionObject
        Object[] extAts = getExtension().getAccessTransformers().toArray();
        binDeobf.addTransformer(extAts);
        decompDeobf.addTransformer(extAts);

        // from the resources dirs
        {
            SourceSetContainer javaConv = JavaExtensionHelper.getSourceSet(project);

            SourceSet main = javaConv.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            SourceSet api = javaConv.getByName("api");

            for (File at : main.getResources().getFiles()) {
                if (at.getName().toLowerCase().endsWith("_at.cfg")) {
                    project.getLogger().lifecycle("Found AccessTransformer in main resources: " + at.getName());
                    binDeobf.addTransformer(at);
                    decompDeobf.addTransformer(at);
                }
            }

            for (File at : api.getResources().getFiles()) {
                if (at.getName().toLowerCase().endsWith("_at.cfg")) {
                    project.getLogger().lifecycle("Found AccessTransformer in api resources: " + at.getName());
                    binDeobf.addTransformer(at);
                    decompDeobf.addTransformer(at);
                }
            }
        }

        // from dependencies (if enabled)
        if (getExtension().getUseAtFromDependencies())
            extractATsFromDependencies(binDeobf, decompDeobf);

        // configure fuzzing.
        ProcessSrcJarTask patch = (ProcessSrcJarTask) project.getTasks().getByName("processSources");
        patch.setMaxFuzz(getExtension().getMaxFuzz());

        super.delayedTaskConfig();
    }

    private void extractATsFromDependencies(final ProcessJarTask binDeobf, final ProcessJarTask decompDeobf) {
        Set<File> processedJars = new HashSet<>();
        File atCacheDir = new File(project.getLayout().getBuildDirectory().getAsFile().get(), "at-cache");
        if (!atCacheDir.exists())
            atCacheDir.mkdirs();

        // Scan compile configuration for AT files in dependencies
        try {
            // Use compileClasspath which is resolvable, not implementation which isn't
            String configName = project.getConfigurations().findByName("compileClasspath") != null
                ? "compileClasspath"
                : UserConstants.CONFIG_COMPILE;

            project.getLogger().lifecycle("Scanning configuration '{}' for AccessTransformers in dependencies", configName);

            // Create a recursive copy and make it resolvable to avoid mutating the original
            Configuration copiedConfig = project.getConfigurations().getByName(configName).copyRecursive();
            copiedConfig.setCanBeResolved(true);

            for (File dep : copiedConfig.getFiles()) {
                if (!dep.getName().endsWith(".jar") || !processedJars.add(dep))
                    continue;

                try (ZipFile zipFile = new ZipFile(dep)) {
                    Enumeration<? extends ZipEntry> entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();

                        if (entryName.toLowerCase().endsWith("_at.cfg")) {
                            // Extract AT file to cache directory
                            String safeName = dep.getName().replace(".jar", "") + "_" + new File(entryName).getName();
                            File atFile = new File(atCacheDir, safeName);

                            try (InputStream is = zipFile.getInputStream(entry)) {
                                Files.copy(is, atFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }

                            project.getLogger().lifecycle("Found AccessTransformer in dependency " + dep.getName() + ": " + entryName);
                            binDeobf.addTransformer(atFile);
                            decompDeobf.addTransformer(atFile);
                        }
                    }
                } catch (Exception e) {
                    project.getLogger().warn("Failed to scan dependency for ATs: {}", dep.getName(), e);
                }
            }
        } catch (Exception e) {
            project.getLogger().warn("Failed to extract ATs from dependencies", e);
        }
    }

    @Override
    protected void doVersionChecks(String version) {
        if (version.indexOf('-') > 0)
            version = version.split("-")[1]; // We get passed the full version, including MC ver and branch, we only want api's version.
        int buildNumber = Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));

        doVersionChecks(version, buildNumber);
    }

    protected abstract void doVersionChecks(String version, int buildNumber);

    @Override
    protected DelayedFile getDevJson() {
        return delayedFile(JSON);
    }

    @Override
    protected String getSrcDepName() {
        return getApiName() + "Src";
    }

    @Override
    protected String getBinDepName() {
        return getApiName() + "Bin";
    }

    @Override
    protected boolean hasApiVersion() {
        return true;
    }

    @Override
    protected String getApiCacheDir(UserPatchExtension exten) {
        return "{CACHE_DIR}/minecraft/" + getApiPath(exten) + "/{API_NAME}/{API_VERSION}";
    }

    @Override
    protected String getSrgCacheDir(UserPatchExtension exten) {
        return "{API_CACHE_DIR}/" + UserConstants.MAPPING_APPENDAGE + "srgs";
    }

    @Override
    protected String getUserDevCacheDir(UserPatchExtension exten) {
        return "{API_CACHE_DIR}/unpacked";
    }

    @Override
    protected String getUserDev() {
        return getApiGroup() + ":{API_NAME}:{API_VERSION}";
    }

    @Override
    protected Class<UserPatchExtension> getExtensionClass() {
        return UserPatchExtension.class;
    }

    @Override
    protected String getApiVersion(UserPatchExtension exten) {
        return exten.getApiVersion();
    }

    @Override
    protected String getMcVersion(UserPatchExtension exten) {
        return exten.getVersion();
    }

    /**
     * THIS HAPPENS EARLY!  no delay tokens or stuff!
     *
     * @return url of the version json
     */
    protected abstract String getVersionsJsonUrl();

    @Override
    protected Iterable<String> getClientRunArgs() {
        return getRunArgsFromProperty();
        //return ImmutableList.of("--version", "1.7", "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker", "--username=ForgeDevName", "--accessToken", "FML", "--userProperties={}");
    }

    private Iterable<String> getRunArgsFromProperty() {
        List<String> ret = new ArrayList<>();
        String arg = (String) project.getProperties().get("runArgs");
        if (arg != null) {
            ret.addAll(Arrays.asList(Commandline.translateCommandline(arg)));
        }
        return ret;
    }

    @Override
    protected Iterable<String> getServerRunArgs() {
        return getRunArgsFromProperty();
    }

    /**
     * Add in the desired patching stages.
     * This happens during normal evaluation, and NOT AfterEvaluate.
     *
     * @param patch patching task
     */
    protected abstract void configurePatching(ProcessSrcJarTask patch);

    /**
     * Should be with separate with periods.
     *
     * @return API group
     */
    protected abstract String getApiGroup();

    /**
     * Should be with separate with slashes.
     *
     * @param exten extension object
     * @return api path
     */
    protected String getApiPath(UserPatchExtension exten) {
        return getApiGroup().replace('.', '/');
    }

    @Override
    protected String getStartDir() {
        return START_DIR;
    }

    @Override
    protected String getClientRunClass() {
        return "net.minecraft.launchwrapper.Launch";
    }

    @Override
    protected String getClientTweaker() {
        return "fml.common.launcher.FMLTweaker";
    }

    @Override
    protected String getServerTweaker() {
        return "fml.common.launcher.FMLServerTweaker";
    }

    @Override
    protected String getServerRunClass() {
        return getClientRunClass();
    }

    @Override
    public String resolve(String pattern, Project project, UserPatchExtension exten) {
        // override tweaker and server run class.
        // do run config stuff.
        String prefix = getMcVersion(exten).startsWith("1.8") ? "net.minecraftforge." : "cpw.mods.";
        pattern = pattern.replace("{RUN_CLIENT_TWEAKER}", prefix + getClientTweaker());
        pattern = pattern.replace("{RUN_SERVER_TWEAKER}", prefix + getServerTweaker());

        pattern = super.resolve(pattern, project, exten);

        return pattern;
    }

    @Override
    protected void configurePostDecomp(boolean decomp, boolean remove) {
        super.configurePostDecomp(decomp, remove);

        if (decomp && remove) {
            project.getTasks().getByName("applyBinPatches").onlyIf(Constants.SPEC_FALSE);
        }
    }
}
