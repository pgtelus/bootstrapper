package com.samczsun.helios.bootstrapper;

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

class UpdaterTask implements Runnable {

    private int buildNumber;

    UpdaterTask(int currentBuildNumber) {
        this.buildNumber = currentBuildNumber;
    }

    public void run() {
        try {
            URL latestVersion = new URL("https://ci.samczsun.com/job/Helios/lastStableBuild/buildNumber");
            HttpURLConnection connection = (HttpURLConnection) latestVersion.openConnection();
            if (connection.getResponseCode() == 200) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Bootstrapper.copy(connection.getInputStream(), outputStream);
                String version = new String(outputStream.toByteArray(), "UTF-8");
                System.out.println("Latest version: " + version);
                int intVersion = Integer.parseInt(version);
                if (intVersion > buildNumber) {
                    int select = JOptionPane.showConfirmDialog(null, "There are " + (intVersion - buildNumber) + " patches available. Update?", null, JOptionPane.YES_NO_OPTION);
                    if (select == JOptionPane.YES_OPTION) {
                        Runtime.getRuntime().exec(new String[]{
                                "java",
                                "-jar",
                                Bootstrapper.BOOTSTRAPPER_FILE.getAbsolutePath(),
                                "-forceupdate"
                        });
                        System.exit(0);
                    }
                }
            } else {
                throw new IOException(connection.getResponseCode() + ": " + connection.getResponseMessage());
            }
        } catch (Throwable t) {
            Bootstrapper.displayError(t);
        }
    }
}
