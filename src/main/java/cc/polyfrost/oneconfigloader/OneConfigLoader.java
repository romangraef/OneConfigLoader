package cc.polyfrost.oneconfigloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;


public class OneConfigLoader implements IFMLLoadingPlugin {
    private final IFMLLoadingPlugin transformer;

    public OneConfigLoader() {
        File oneConfigDir = new File(Launch.minecraftHome, "OneConfig");

        boolean update = true;
        String channel = "release";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(new File(oneConfigDir, "OneConfig.json").toPath()), StandardCharsets.UTF_8))) {
            JsonObject config = new JsonParser().parse(reader).getAsJsonObject();
            update = config.get("autoUpdate").getAsBoolean();
            channel = config.get("updateChannel").getAsInt() == 0 ? "release" : "snapshot";
        } catch (Exception ignored) {
        }

        if (!oneConfigDir.exists() && !oneConfigDir.mkdir())
            throw new IllegalStateException("Could not create OneConfig dir!");

        File oneConfigFile = new File(oneConfigDir, "OneConfig (1.8.9).jar");

        if (!isInitialized(oneConfigFile)) {
            JsonElement json = update ? getRequest("https://api.polyfrost.cc/oneconfig/1.8.9-forge") : null;

            if (json != null && json.isJsonObject()) {
                JsonObject jsonObject = json.getAsJsonObject();

                if (jsonObject.has(channel) && jsonObject.getAsJsonObject(channel).has("url")
                        && jsonObject.getAsJsonObject(channel).has("sha256")) {

                    String checksum = jsonObject.getAsJsonObject(channel).get("sha256").getAsString();
                    String downloadUrl = jsonObject.getAsJsonObject(channel).get("url").getAsString();

                    if (!oneConfigFile.exists() || !checksum.equals(getChecksum(oneConfigFile))) {
                        System.out.println("Updating OneConfig...");

                        File newOneConfigFile = new File(oneConfigDir, "OneConfig-NEW (1.8.9).jar");
                        downloadFile(downloadUrl, newOneConfigFile);

                        if (newOneConfigFile.exists() && checksum.equals(getChecksum(newOneConfigFile))) {
                            try {
                                Files.move(newOneConfigFile.toPath(), oneConfigFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                System.out.println("Updated OneConfig");
                            } catch (IOException ignored) {
                            }
                        } else {
                            if (newOneConfigFile.exists()) newOneConfigFile.delete();
                            System.out.println("Failed to update OneConfig, trying to continue...");
                        }
                    }
                }
            }

            if (!oneConfigFile.exists()) throw new IllegalStateException("OneConfig jar doesn't exist");
            addToClasspath(oneConfigFile);
        }
        try {
            transformer = ((IFMLLoadingPlugin) Launch.classLoader.findClass("cc.polyfrost.oneconfig.internal.plugin.LoadingPlugin").newInstance());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isInitialized(File file) {
        try {
            URL url = file.toURI().toURL();

            return Arrays.asList(((URLClassLoader) Launch.classLoader.getClass().getClassLoader()).getURLs()).contains(url);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void addToClasspath(File file) {
        try {
            URL url = file.toURI().toURL();
            Launch.classLoader.addURL(url);
            ClassLoader classLoader = Launch.classLoader.getClass().getClassLoader();
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(classLoader, url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void downloadFile(String url, File location) {
        try {
            URLConnection con = new URL(url).openConnection();
            con.setUseCaches(false);
            con.setRequestProperty("User-Agent", "OneConfig-Loader");
            InputStream in = con.getInputStream();
            Files.copy(in, location.toPath(), StandardCopyOption.REPLACE_EXISTING);
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static JsonElement getRequest(String site) {
        try {
            URL url = new URL(site);
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.setRequestProperty("User-Agent", "OneConfig-Loader");
            con.setRequestMethod("GET");
            int status = con.getResponseCode();
            if (status != 200) {
                System.out.println("API request failed, status code " + status);
                return null;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            JsonParser parser = new JsonParser();
            return parser.parse(content.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getChecksum(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            byte[] digested = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digested) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public String[] getASMTransformerClass() {
        return transformer == null ? new String[]{} : transformer.getASMTransformerClass();
    }

    @Override
    public String getModContainerClass() {
        return transformer == null ? null : transformer.getModContainerClass();
    }

    @Override
    public String getSetupClass() {
        return transformer == null ? null : transformer.getSetupClass();
    }

    @Override
    public void injectData(Map<String, Object> data) {
        if (transformer != null) transformer.injectData(data);
    }

    @Override
    public String getAccessTransformerClass() {
        return transformer == null ? null : transformer.getAccessTransformerClass();
    }
}
