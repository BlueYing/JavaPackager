/*
 * Copyright (c) 2021-2022 Osiris-Team.
 * All rights reserved.
 *
 * This software is copyrighted work, licensed under the terms
 * of the MIT-License. Consult the "LICENSE" file for details.
 */

package io.github.fvarrui.javapackager.utils.updater;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.fvarrui.javapackager.model.Platform;
import io.github.fvarrui.javapackager.utils.Logger;
import io.github.fvarrui.javapackager.utils.NativeUtils;
import org.apache.commons.io.FileUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Searches for updates and installs them is AUTOMATIC profile is selected.
 */
public class TaskJavaUpdater {

    public File downloadsDir = new File(NativeUtils.getUserTempFolder()+"/downloads");
    public File jdkPath;
    public AdoptV3API.OperatingSystemType osType;

    public TaskJavaUpdater(Platform platform) {
        switch (platform) {
            case linux:
                jdkPath = new File(NativeUtils.getUserTempFolder()+"/jdk/linux");
                osType = AdoptV3API.OperatingSystemType.LINUX;
                break;
            case mac:
                jdkPath = new File(NativeUtils.getUserTempFolder()+"/jdk/mac");
                osType = AdoptV3API.OperatingSystemType.MAC;
                break;
            case windows:
                jdkPath = new File(NativeUtils.getUserTempFolder()+"/jdk/win");
                osType = AdoptV3API.OperatingSystemType.WINDOWS;
                break;
            default:
        }
        jdkPath.mkdirs();
    }

    private String javaVersion, javaVendor;

    public void execute(String javaVersion, String javaVendor) throws Exception {
        Objects.requireNonNull(javaVersion);
        Objects.requireNonNull(javaVendor);
        if(!javaVendor.equals("adoptium")) throw new IllegalArgumentException("The provided Java vendor '"+javaVendor+"' is currently not supported!");
        Logger.info("Checking java installation...");
        AdoptV3API.OperatingSystemArchitectureType osArchitectureType = AdoptV3API.OperatingSystemArchitectureType.X64;
        boolean isLargeHeapSize = false;
        int currentBuildId = getBuildID(javaVersion, javaVendor);
        AdoptV3API.ImageType imageType = AdoptV3API.ImageType.JDK;

        JsonObject jsonReleases = new AdoptV3API().getReleases(
                osArchitectureType,
                isLargeHeapSize,
                imageType,
                true,
                true, // Changing this to false makes the api return even fewer versions, which is pretty weird.
                osType,
                50,
                AdoptV3API.VendorProjectType.JDK,
                AdoptV3API.ReleaseType.GENERAL_AVAILABILITY
        );

        JsonObject jsonLatestRelease = null;
        for (JsonElement e :
                jsonReleases.getAsJsonArray("versions")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("major").getAsString().equals(javaVersion)) {
                jsonLatestRelease = o;
                break;
            }
        }

        if (jsonLatestRelease == null){
            Logger.error("Couldn't find a matching major version to '" + javaVersion + "'.");
            return;
        }

        int latestBuildId = jsonLatestRelease.get("build").getAsInt();
        if (latestBuildId <= currentBuildId) {
            Logger.info("Your Java installation is on the latest version!");
            return;
        }

        // semver = the version string like: 11.0.0+28 for example // Not a typo ^-^
        String versionString = jsonLatestRelease.get("semver").toString().replace("\"", ""); // Returns with apostrophes ""

        JsonArray jsonVersionDetails = new AdoptV3API().getVersionInformation(
                versionString,
                osArchitectureType,
                isLargeHeapSize,
                imageType,
                true,
                true,
                osType,
                50,
                AdoptV3API.VendorProjectType.JDK,
                AdoptV3API.ReleaseType.GENERAL_AVAILABILITY);

        String checksum = jsonVersionDetails.get(0).getAsJsonObject().getAsJsonArray("binaries")
                .get(0).getAsJsonObject().get("package").getAsJsonObject().get("checksum").getAsString();

        // The release name that can be used to retrieve the download link
        String releaseName = jsonVersionDetails.get(0).getAsJsonObject().get("release_name").getAsString();
        String downloadURL = new AdoptV3API().getDownloadUrl(
                releaseName,
                osType,
                osArchitectureType,
                imageType,
                true,
                isLargeHeapSize,
                AdoptV3API.VendorProjectType.JDK
        );

        Logger.info("Update found " + currentBuildId + " -> " + latestBuildId);
        File final_dir_dest = jdkPath;
        File cache_dest = new File(downloadsDir + "/" + imageType + "-" + versionString + ".file");
        TaskJavaDownload download = new TaskJavaDownload();
        download.execute(downloadURL, cache_dest, osType);

        Logger.info("Java update downloaded. Checking hash...");
        if(!download.compareWithSHA256(checksum))
            throw new IOException("Hash of downloaded Java update is not valid!");
        Logger.info("Hash is valid, removing old installation...");
        FileUtils.deleteDirectory(final_dir_dest);
        final_dir_dest.mkdirs();

        Archiver archiver;
        if (download.isTar())
            archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP);
        else // A zip
            archiver = ArchiverFactory.createArchiver(ArchiveFormat.ZIP);

        // Extracts to /jdk8+189 thus we need to move its content to its parent dir
        archiver.extract(download.getNewCacheDest(), final_dir_dest);
        setBuildID(latestBuildId, javaVersion, javaVendor);
        File actualJdkPath = null;
        for (File file : jdkPath.listFiles()) {
            if(file.isDirectory()){
                actualJdkPath = file;
                break;
            }
        }
        for (File file : actualJdkPath.listFiles()) {
            Files.move(file, new File(jdkPath+"/"+file.getName()));
        }
        FileUtils.deleteDirectory(actualJdkPath);
        FileUtils.deleteDirectory(downloadsDir);
        Logger.info("Java update was installed successfully (" + currentBuildId + " -> " + latestBuildId + ") at "+jdkPath);
    }

    private String getFileNameWithoutID(String javaVersion, String javaVendor){
        return "java_packager_jdk_"+javaVersion+"_"+javaVendor+"_build_id";
    }

    private int getBuildID(String javaVersion, String javaVendor) throws IOException {
        for (File file : jdkPath.listFiles()) {
            if(file.getName().startsWith(getFileNameWithoutID(javaVersion, javaVendor))){
                return Integer.parseInt(file.getName().split(" ")[1]);
            }
        }
        setBuildID(0, javaVersion, javaVendor);
        return 0;
    }

    private void setBuildID(int id, String javaVersion, String javaVendor) throws IOException {
        for (File file : jdkPath.listFiles()) {
            if(file.getName().startsWith(getFileNameWithoutID(javaVersion, javaVendor))){
                file.delete();
            }
        }
        File file = new File(jdkPath+"/"+getFileNameWithoutID(javaVersion, javaVendor)+" "+id);
        file.createNewFile();
    }

}