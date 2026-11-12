package mindustry.net;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.net.*;

import static mindustry.Vars.*;

/** Handles control of bleeding edge builds. */
public class BeControl{
    private static final int updateInterval = 60;

    private boolean checkUpdates = true;
    private boolean updateAvailable;
    private String updateUrl, updateDigest;
    private int updateBuild;

    /** @return whether this is a bleeding edge build. */
    public boolean active(){
        return Version.type.equals("bleeding-edge") && !steam;
    }

    public BeControl(){

    }

    public void init(){
        if(active()){
            Timer.schedule(() -> {
                if((Vars.clientLoaded || headless) && checkUpdates && !mobile){
                    checkUpdate(t -> {});
                }
            }, updateInterval, updateInterval);
        }

        if(OS.hasProp("becopy")){
            try{
                Fi dest = Fi.get(OS.prop("becopy"));
                Fi self = Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

                for(Fi file : self.parent().findAll(f -> !f.equals(self))) file.delete();

                self.copyTo(dest);
            }catch(Throwable e){
                e.printStackTrace();
            }
        }
    }

    /** asynchronously checks for updates. */
    public void checkUpdate(Boolc done){
        Http.get("https://api.github.com/repos/Anuken/MindustryBuilds/releases/latest")
        .error(e -> {
            //don't log the error, as it would clog output if there is no internet. make sure it's handled to prevent infinite loading.
            done.get(false);
        })
        .submit(res -> {
            Jval val = Jval.read(res.getResultAsString());
            int newBuild = Strings.parseInt(val.getString("tag_name", "0"));
            if(newBuild > Version.build){
                Jval asset = val.get("assets").asArray().find(v -> v.getString("name", "").startsWith(headless ? "Mindustry-BE-Server" : "Mindustry-BE-Desktop"));
                String url = asset == null ? "" : asset.getString("browser_download_url", "");
                String digest = asset == null ? "" : asset.getString("digest", "");
                if(url.isEmpty() || !digest.matches("sha256:[0-9a-fA-F]{64}")){
                    updateAvailable = false;
                    Core.app.post(() -> done.get(false));
                    return;
                }
                updateAvailable = true;
                updateBuild = newBuild;
                updateUrl = url;
                updateDigest = digest.substring("sha256:".length());
                Core.app.post(() -> {
                    showUpdateDialog();
                    done.get(true);
                });
            }else{
                Core.app.post(() -> done.get(false));
            }
        });
    }

    /** @return whether a new update is available */
    public boolean isUpdateAvailable(){
        return updateAvailable;
    }

    /** shows the dialog for updating the game on desktop, or a prompt for doing so on the server */
    public void showUpdateDialog(){
        if(!updateAvailable) return;

        if(!headless){
            checkUpdates = false;
            ui.showCustomConfirm(Core.bundle.format("be.update", "") + " " + updateBuild, "@be.update.confirm", "@ok", "@be.ignore", () -> {
                try{
                    boolean[] cancel = {false};
                    float[] progress = {0};
                    int[] length = {0};
                    Fi file = bebuildDirectory.child("client-be-" + updateBuild + ".jar");
                    Fi fileDest = OS.hasProp("becopy") ?
                        Fi.get(OS.prop("becopy")) :
                        Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

                    BaseDialog dialog = new BaseDialog("@be.updating");
                    download(updateUrl, updateDigest, file, i -> length[0] = i, v -> progress[0] = v, () -> cancel[0], () -> {
                        try{
                            Runtime.getRuntime().exec(OS.isMac ?
                                new String[]{javaPath, "-XstartOnFirstThread", "-DlastBuild=" + Version.build, "-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath()} :
                                new String[]{javaPath, "-DlastBuild=" + Version.build, "-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath()}
                            );
                            System.exit(0);
                        }catch(IOException e){
                            ui.showException(e);
                        }
                    }, e -> {
                        dialog.hide();
                        ui.showException(e);
                    });

                    dialog.cont.add(new Bar(() -> length[0] == 0 ? Core.bundle.get("be.updating") : (int)(progress[0] * length[0]) / 1024/ 1024 + "/" + length[0]/1024/1024 + " MB", () -> Pal.accent, () -> progress[0])).width(400f).height(70f);
                    dialog.buttons.button("@cancel", Icon.cancel, () -> {
                        cancel[0] = true;
                        dialog.hide();
                    }).size(210f, 64f);
                    dialog.setFillParent(false);
                    dialog.show();
                }catch(Exception e){
                    ui.showException(e);
                }
            }, () -> checkUpdates = false);
        }else{
            Log.info("&lcA new update is available: &lyBleeding Edge build @", updateBuild);
            if(Config.autoUpdate.bool()){
                Log.info("&lcAuto-downloading next version...");

                try{
                    //download new file from github
                    Fi source = Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
                    Fi dest = source.sibling("server-be-" + updateBuild + ".jar");

                    download(updateUrl, updateDigest, dest,
                    len -> Core.app.post(() -> Log.info("&ly| Size: @ MB.", Strings.fixed((float)len / 1024 / 1024, 2))),
                    progress -> {},
                    () -> false,
                    () -> Core.app.post(() -> {
                        Log.info("&lcSaving...");
                        SaveIO.save(saveDirectory.child("autosavebe." + saveExtension));
                        Log.info("&lcAutosaved.");

                        netServer.kickAll(KickReason.serverRestarting);
                        Threads.sleep(500);

                        Log.info("&lcVersion downloaded, exiting. Note that if you are not using a auto-restart script, the server will not restart automatically.");
                        //replace old file with new
                        dest.copyTo(source);
                        dest.delete();
                        System.exit(2); //this will cause a restart if using the script
                    }),
                    Throwable::printStackTrace);
                }catch(Exception e){
                    e.printStackTrace();
                }
            }
            checkUpdates = false;
        }
    }

    private void download(String furl, String expectedDigest, Fi dest, Intc length, Floatc progressor, Boolp canceled, Runnable done, Cons<Throwable> error){
        mainExecutor.submit(() -> {
            HttpURLConnection con = null;
            Fi staging = null;
            boolean verified = false;
            Throwable failure = null;
            try{
                if(expectedDigest == null || !expectedDigest.matches("[0-9a-fA-F]{64}")){
                    throw new IOException("Missing SHA-256 digest for update.");
                }
                con = (HttpURLConnection)new URL(furl).openConnection();
                con.setConnectTimeout(30000);
                con.setReadTimeout(30000);
                if(con.getResponseCode() != HttpURLConnection.HTTP_OK){
                    throw new IOException("Update download failed: HTTP " + con.getResponseCode());
                }
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                long size = con.getContentLengthLong(), counter = 0;
                length.get((int)Math.min(Integer.MAX_VALUE, Math.max(0, size)));
                dest.parent().mkdirs();
                staging = new Fi(File.createTempFile("mindustry-update-", ".part", dest.parent().file()));
                try(InputStream in = new BufferedInputStream(con.getInputStream());
                    OutputStream out = staging.write(false, 4096)){
                    byte[] data = new byte[4096];
                    int x;
                    while(!canceled.get() && (x = in.read(data)) != -1){
                        counter += x;
                        digest.update(data, 0, x);
                        out.write(data, 0, x);
                        if(size > 0) progressor.get((float)counter / size);
                    }
                }
                if(canceled.get()) return;
                if(size >= 0 && counter != size) throw new IOException("Incomplete update download.");
                if(!Strings.bytesToHex(digest.digest()).equalsIgnoreCase(expectedDigest)){
                    throw new IOException("Update SHA-256 digest mismatch.");
                }
                java.nio.file.Files.move(staging.file().toPath(), dest.file().toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                verified = true;
            }catch(Throwable e){
                failure = e;
            }finally{
                if(con != null) con.disconnect();
                if(staging != null) staging.delete();
            }
            if(failure != null){
                error.get(failure);
            }else if(verified){
                try{
                    done.run();
                }catch(Throwable e){
                    error.get(e);
                }
            }
        });
    }
}
