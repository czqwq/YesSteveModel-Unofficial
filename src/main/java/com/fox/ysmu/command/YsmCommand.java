package com.fox.ysmu.command;

import static com.fox.ysmu.compat.Utils.isValidResourceLocation;
import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.S2CPlaySound;

public class YsmCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "ysm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ysm <reload|playsound [name]>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            processReload(sender);
            return;
        }
        if (args.length >= 1 && "playsound".equalsIgnoreCase(args[0]) && args.length <= 2) {
            processPlaySound(sender, args.length == 2 ? args[1] : "");
            return;
        }
        throw new WrongUsageException(getCommandUsage(sender));
    }

    /**
     * {@code /ysm playsound [name]} - forwards to the issuer's own client.
     * <p>
     * The sound library lives entirely on the client: model sound bytes are only ever decoded there, and playback
     * goes through this client's {@code SoundSystem}. A dedicated server has no {@code Minecraft} instance at all,
     * so running the request here would list nothing and play nothing. The server's job is the permission check
     * ({@link #getRequiredPermissionLevel}) and the forward; the client does the rest, including the reply.
     * <p>
     * No name lists what that client has; a name plays it.
     */
    private void processPlaySound(ICommandSender sender, String soundName) {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        NetworkHandler.CHANNEL.sendTo(new S2CPlaySound(soundName), player);
    }

    private void processReload(ICommandSender sender) {
        StopWatch watch = new StopWatch();
        watch.start();
        checkModelFiles(sender, CUSTOM);
        ServerModelManager.reloadPacks();

        // CUI-02: 广播给全服所有玩家，而不是只广播命令执行者所在维度的玩家。
        // N-02（取舍：保留）：这是 /ysm reload 之后唯一的服务端权威重同步入口 —— 每个玩家恰好调用一次，
        // 不存在"同一事件被触发两次"；17 与 legacy 两条通道如何选择（以及协议打开时旧实现会两条都发）
        // 由 model/ServerModelManager.sendRequestSyncModelMessage（:119-124）决定，而它在 model/**，
        // 不在本任务 inScope → 记为跨任务依赖（fix-network 的 N-02 服务端侧 gate）。
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.getConfigurationManager() != null) {
            for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
                ServerModelManager.sendRequestSyncModelMessage(player);
            }
        } else {
            ServerModelManager.sendRequestSyncModelMessage(sender.getEntityWorld().playerEntities);
        }

        watch.stop();
        // CU-02: 语言串只能是 %s（%.2f 会被 ChatComponentTranslation 当作非法格式串并抛异常），
        // 且 %.2f 必须接收 double —— 传 long 会抛 IllegalFormatConversionException。
        sender.addChatMessage(new ChatComponentTranslation(
            "message.yes_steve_model.model.reload.info",
            String.format(Locale.ROOT, "%.2f", (double) watch.getTime())));
    }

    private void checkModelFiles(ICommandSender sender, Path rootPath) {
        Collection<File> dirs = FileUtils.listFiles(rootPath.toFile(), DirectoryFileFilter.INSTANCE, null);
        for (File dir : dirs) {
            String dirName = dir.getName();
            boolean noMainModelFile = true;
            boolean noArmModelFile = true;
            boolean noTextureFile = true;
            Collection<File> files = FileUtils.listFiles(rootPath.resolve(dirName).toFile(), FileFileFilter.FILE, null);
            for (File file : files) {
                String fileName = file.getName();
                if (MAIN_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noMainModelFile = false;
                }
                if (ARM_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noArmModelFile = false;
                }
                if (fileName.endsWith(".png")) {
                    noTextureFile = false;
                    String name = file.getName();
                    name = name.substring(0, name.length() - 4);
                    if (!isValidResourceLocation(name)) {
                        String showName = String.format("%s/%s.png", dirName, name);
                        sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.texture_name", showName));
                    }
                }
            }
            if (noMainModelFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_main_file", dirName));
            }
            if (noArmModelFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_arm_file", dirName));
            }
            if (noTextureFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_texture_file", dirName));
            }
        }
    }

    private static boolean isNotBlankFile(File file) {
        try {
            String fileText = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            return StringUtils.isNoneBlank(fileText);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
