package com.mojang.minecraft.net;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import com.mojang.minecraft.GameSettings;
import com.mojang.minecraft.HotKeyData;
import com.mojang.minecraft.Minecraft;
import com.mojang.minecraft.PlayerListComparator;
import com.mojang.minecraft.PlayerListNameData;
import com.mojang.minecraft.SelectionBoxData;
import com.mojang.minecraft.SessionData;
import com.mojang.minecraft.gui.ErrorScreen;
import com.mojang.minecraft.gui.HUDScreen;
import com.mojang.minecraft.level.Level;
import com.mojang.minecraft.level.LevelLoader;
import com.mojang.minecraft.level.tile.Block;
import com.mojang.minecraft.level.tile.BlockID;
import com.mojang.minecraft.mob.HumanoidMob;
import com.mojang.minecraft.model.Model;
import com.mojang.minecraft.model.ModelManager;
import com.mojang.minecraft.physics.CustomAABB;
import com.mojang.util.ColorCache;
import com.mojang.util.LogUtil;
import com.mojang.util.MathHelper;
import com.oyasunadev.mcraft.client.util.Constants;

// This class is responsible for responding to individual packets coming from the client.
// It also handles CPE Negotiation process.
public final class PacketHandler {

    private int extEntriesExpected, extEntriesReceived;
    private boolean receivedExtInfo;

    private final Minecraft minecraft;

    public boolean isLoadingLevel;
    private long lastLevelProgress;

    // This object is used to store the level object while it's being loaded.
    // Packets that modify can modify the level before it loaded (like ENV_SET_COLOR)
    // should modify this object instead of "minecraft.level" while isLoadingLevel is true.
    private Level newLevel;

    public PacketHandler(Minecraft minecraft) {
        this.minecraft = minecraft;
        setLoadingLevel(true);
    }

    public void setLoadingLevel(boolean value) {
        if (value && !isLoadingLevel) {
            newLevel = new Level();
        } else if (!value && isLoadingLevel) {
            newLevel = null;
        }
        isLoadingLevel = value;
    }

    // return true if more packets should be read; return false if that's it
    public boolean handlePacket(NetworkManager networkHandler) throws IOException {
        networkHandler.in.flip();
        byte packetId = networkHandler.in.get(0);
        if (packetId < 0 || packetId > PacketType.packets.length - 1) {
            throw new IOException("Unknown packet ID received: " + packetId);
        }

        PacketType packetType = PacketType.packets[packetId];
        if (networkHandler.in.remaining() < packetType.length + 1) {
            // No more packets left to read
            networkHandler.in.compact();
            return false;
        }
        networkHandler.in.get();
        Object[] packetParams = new Object[packetType.params.length];

        for (int i = 0; i < packetParams.length; ++i) {
            packetParams[i] = networkHandler.readObject(packetType.params[i]);
        }

        if (networkHandler.isConnected()) {
            if (packetType.opcode > PacketType.UPDATE_PLAYER_TYPE.opcode) {
                handleExtendedPacket(networkHandler, packetType, packetParams);
            } else {
                handleStandardPacket(networkHandler, packetType, packetParams);
            }
        }

        if (networkHandler.isConnected()) {
            networkHandler.in.compact();
            return true;
        } else {
            // We've been disconnected!
            return false;
        }
    }

    private void handleStandardPacket(NetworkManager networkManager, PacketType packetType, Object[] packetParams) throws IOException {
        if (packetType == PacketType.IDENTIFICATION) {
            String name = packetParams[1].toString();
            String motd = packetParams[2].toString();
            minecraft.progressBar.setTitle(name);
            minecraft.progressBar.setText(motd);
            // Read WoM-style hack control flags
            //TODO: if(!isExtEnabled(ProtocolExtension.HACK_CONTROL)){ ... }
            minecraft.womConfig.readHax(name, motd);
            if (!receivedExtInfo) {
                // Only process WoM-style "cfg" command if CPE is not enabled
                minecraft.womConfig.readCfg(motd);
            }
            minecraft.player.userType = (Byte) packetParams[3];
            setLoadingLevel(true);
            if (minecraft.womConfig.isEnabled() && minecraft.womConfig.hasKey("server.sendwomid")) {
                String womIdCmd = "/womid ClassiCube" + Constants.CLASSICUBE_VERSION;
                networkManager.send(PacketType.CHAT_MESSAGE, -1, womIdCmd);
            }

        } else if (packetType == PacketType.LEVEL_INIT) {
            minecraft.selectionBoxes.clear();
            minecraft.setLevel(null);
            networkManager.levelData = new ByteArrayOutputStream();
            setLoadingLevel(true);

        } else if (packetType == PacketType.LEVEL_DATA) {
            short chunkLength = (short) packetParams[0];
            byte[] chunkData = (byte[]) packetParams[1];
            byte percentComplete = (byte) packetParams[2];

            // Update progress bar at most 10 times per second, to avoid long map load times.
            long now = System.currentTimeMillis();
            if (now - lastLevelProgress > 50) {
                // setProgress forces a full screen refresh, use sparingly!
                minecraft.progressBar.setProgress(percentComplete);
                lastLevelProgress = now;
            }

            networkManager.levelData.write(chunkData, 0, chunkLength);

        } else if (packetType == PacketType.LEVEL_FINALIZE) {
            minecraft.progressBar.setProgress(100);
            try {
                networkManager.levelData.close();
            } catch (IOException ex) {
                LogUtil.logError("Error receiving level data.");
                throw ex; // We are in an inconsistent state; abort!
            }

            byte[] decompressedStream = LevelLoader.decompress(
                    new ByteArrayInputStream(networkManager.levelData.toByteArray()));
            networkManager.levelData = null;
            short xSize = (short) packetParams[0];
            short ySize = (short) packetParams[1];
            short zSize = (short) packetParams[2];
            newLevel.setNetworkMode(true);
            newLevel.setData(xSize, ySize, zSize, decompressedStream);
            minecraft.setLevel(newLevel);
            minecraft.isConnecting = false;
            networkManager.levelLoaded = true;
            setLoadingLevel(false);

        } else if (packetType == PacketType.BLOCK_CHANGE) {
            if (minecraft.level != null) {
                minecraft.level.netSetTile(
                        (short) packetParams[0], (short) packetParams[1],
                        (short) packetParams[2], (byte) packetParams[3]);
            } // else: no level is loaded, ignore block change

        } else if (packetType == PacketType.SPAWN_PLAYER) {
            if (networkManager.isExtEnabled(ProtocolExtension.EXT_PLAYER_LIST_2)) {
                LogUtil.logWarning("Server tried to send SPAWN_PLAYER even though ExtPlayerList version 2 is in use.");
                return;
            }
            byte newPlayerId = (byte) packetParams[0];
            String newPlayerName = (String) packetParams[1];
            short newPlayerX = (short) packetParams[2];
            short newPlayerY = (short) packetParams[3];
            short newPlayerZ = (short) packetParams[4];
            byte newPlayerXRot = (byte) packetParams[5];
            byte newPlayerYRot = (byte) packetParams[6];
            handleSpawnPlayer(networkManager, newPlayerName, newPlayerId, newPlayerX, newPlayerY, newPlayerZ, newPlayerXRot, newPlayerYRot);

        } else if (packetType == PacketType.POSITION_ROTATION) {
            byte playerId = (byte) packetParams[0];
            short newX = (short) packetParams[1];
            short newY = (short) packetParams[2];
            short newZ = (short) packetParams[3];
            byte newXRot = (byte) packetParams[4];
            byte newYRot = (byte) packetParams[5];
            if (playerId < 0) {
                // Move self
                minecraft.player.moveTo(newX / 32F, newY / 32F, newZ / 32F,
                        newXRot * 360 / 256F, newYRot * 360 / 256F);
            } else {
                // Move another player
                newXRot = (byte) (newXRot + 128);
                newY = (short) (newY - 22);
                NetworkPlayer networkPlayer = networkManager.getPlayer(playerId);
                if (networkPlayer != null) {
                    networkPlayer.teleport(newX, newY, newZ,
                            newYRot * 360 / 256F, newXRot * 360 / 256F);
                } // else: unknown player ID given, ignore it.
            }

        } else if (packetType == PacketType.POSITION_ROTATION_UPDATE) {
            byte playerId = (byte) packetParams[0];
            byte deltaX = (byte) packetParams[1];
            byte deltaY = (byte) packetParams[2];
            byte deltaZ = (byte) packetParams[3];
            byte newXRot = (byte) packetParams[4];
            byte newYRot = (byte) packetParams[5];
            if (playerId >= 0) {
                newXRot = (byte) (newXRot + 128);
                NetworkPlayer networkPlayerInstance = networkManager.getPlayer(playerId);
                if (networkPlayerInstance != null) {
                    networkPlayerInstance.queue(deltaX, deltaY,
                            deltaZ, newYRot * 360 / 256F, newXRot * 360 / 256F);
                }
            } // else: This packet cannot be applied to self, and is ignored if playerId<0

        } else if (packetType == PacketType.ROTATION_UPDATE) {
            byte playerID = (byte) packetParams[0];
            byte newXRot = (byte) packetParams[1];
            byte newYRot = (byte) packetParams[2];
            if (playerID >= 0) {
                newXRot = (byte) (newXRot + 128);
                NetworkPlayer networkPlayerInstance = networkManager.getPlayer(playerID);
                if (networkPlayerInstance != null) {
                    networkPlayerInstance.queue(newYRot * 360 / 256F, newXRot * 360 / 256F);
                }
            } // else: This packet cannot be applied to self, and is ignored if playerId<0

        } else if (packetType == PacketType.POSITION_UPDATE) {
            byte playerID = (byte) packetParams[0];
            NetworkPlayer networkPlayerInstance = networkManager.getPlayer(playerID);
            if (playerID >= 0 && networkPlayerInstance != null) {
                networkPlayerInstance.queue((byte) packetParams[1],
                        (byte) packetParams[2], (byte) packetParams[3]);
            } // else: This packet cannot be applied to self, and is ignored if playerId<0

        } else if (packetType == PacketType.DESPAWN_PLAYER) {
            byte playerID = (byte) packetParams[0];
            NetworkPlayer targetPlayer = networkManager.removePlayer(playerID);
            if (playerID >= 0 && targetPlayer != null) {
                targetPlayer.unloadSkin(minecraft.textureManager);
                minecraft.level.removeEntity(targetPlayer);
            } // else: This packet cannot be applied to self, and is ignored if playerId<0

        } else if (packetType == PacketType.CHAT_MESSAGE) {
            byte messageType = (byte) packetParams[0];
            String message = (String) packetParams[1];
            if (messageType > 0 && networkManager.isExtEnabled(ProtocolExtension.MESSAGE_TYPES)) {
                // MESSAGE_TYPES CPE
                switch (messageType) {
                    case 1:
                        HUDScreen.ServerName = message;
                        break;
                    case 2:
                        HUDScreen.Compass = message;
                        break;
                    case 3:
                        HUDScreen.UserDetail = message;
                        break;
                    case 11:
                        HUDScreen.BottomRight1 = message;
                        break;
                    case 12:
                        HUDScreen.BottomRight2 = message;
                        break;
                    case 13:
                        HUDScreen.BottomRight3 = message;
                        break;
                    case 100:
                        HUDScreen.AnnouncementTimer = System.currentTimeMillis();
                        HUDScreen.Announcement = message;
                        break;
                    default:
                        // unknown MessageType: stick it into regular chat box
                        minecraft.hud.addChat(message);
                        break;
                }
            } else if (messageType < 0 && !networkManager.isExtEnabled(ProtocolExtension.MESSAGE_TYPES)) {
                // For compatibility with vanilla Minecraft: negative ID colors a message yellow
                minecraft.hud.addChat("&e" + message);
            } else {
                // Regular chat
                minecraft.hud.addChat(message);
            }

        } else if (packetType == PacketType.DISCONNECT) {
            setLoadingLevel(false); // Reset this, in case we get kicked while changing levels.
            networkManager.close();
            minecraft.setCurrentScreen(new ErrorScreen("Connection lost", (String) packetParams[0]));

        } else if (packetType == PacketType.UPDATE_PLAYER_TYPE) {
            minecraft.player.userType = (byte) packetParams[0];
        }
    }

    private void handleExtendedPacket(NetworkManager networkManager, PacketType packetType, Object[] packetParams) throws IOException {
        if (packetType == PacketType.EXT_INFO) {
            if (receivedExtInfo) {
                LogUtil.logWarning("Received multiple ExtInfo packets! Only one was expected.");
            }
            receivedExtInfo = true;
            String appName = (String) packetParams[0];
            short extensionCount = (short) packetParams[1];
            LogUtil.logInfo(String.format("Connecting to AppName \"%s\" with ExtensionCount %d",
                    appName, extensionCount));
            extEntriesExpected = extensionCount;

        } else if (packetType == PacketType.EXT_ENTRY) {
            extEntriesReceived++;
            String extName = (String) packetParams[0];
            int version = (int) packetParams[1];

            if (extEntriesReceived > extEntriesExpected) {
                LogUtil.logWarning(String.format(
                        "Expected %d ExtEntries but received too many (%d)! "
                        + "This ext will be ignored: %s with version %d",
                        extEntriesExpected, extEntriesReceived, extName, version));
            } else {
                ProtocolExtension serverExt = new ProtocolExtension(extName, version);
                LogUtil.logInfo(String.format("Receiving ext: %s with version: %d",
                        serverExt.name, serverExt.version));
                if (ProtocolExtension.isSupported(serverExt)) {
                    networkManager.enableExtension(serverExt);
                }

                if (extEntriesExpected == extEntriesReceived) {
                    ProtocolExtension[] enabledExtList = networkManager.listEnabledExtensions();
                    LogUtil.logInfo(String.format(
                            "Sending list of mutually-supported CPE extensions (%d)",
                            enabledExtList.length));
                    Object[] toSendParams = new Object[]{
                        Constants.CLIENT_NAME, (short) enabledExtList.length};
                    networkManager.send(PacketType.EXT_INFO, toSendParams);
                    for (ProtocolExtension ext : enabledExtList) {
                        LogUtil.logInfo(String.format("Sending ext: %s with version: %d",
                                ext.name, ext.version));
                        toSendParams = new Object[]{ext.name, ext.version};
                        networkManager.send(PacketType.EXT_ENTRY, toSendParams);
                    }
                }
            }

        } else if (packetType == PacketType.SELECTION_CUBOID) {
            if (!networkManager.isExtEnabled(ProtocolExtension.SELECTION_CUBOID)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: SelectionCuboid");
            }
            Level level = minecraft.level;
            byte selectionId = (byte) packetParams[0];
            String selectionName = (String) packetParams[1];
            // Selection coordinates must be clamped to map boundaries.
            int x1 = MathHelper.clamp((short) packetParams[2], 0, level.width);
            int y1 = MathHelper.clamp((short) packetParams[3], 0, level.height);
            int z1 = MathHelper.clamp((short) packetParams[4], 0, level.length);
            // Max values for coordinates may not exceed map dimensions.
            // They also cannot be lower than min values.
            int x2 = MathHelper.clamp((short) packetParams[5], x1, level.width);
            int y2 = MathHelper.clamp((short) packetParams[6], y1, level.height);
            int z2 = MathHelper.clamp((short) packetParams[7], z1, level.length);
            // Color components must be clamped to valid range (0-255)
            int r = MathHelper.clamp((short) packetParams[8], 0, 255);
            int g = MathHelper.clamp((short) packetParams[9], 0, 255);
            int b = MathHelper.clamp((short) packetParams[10], 0, 255);
            int a = MathHelper.clamp((short) packetParams[11], 0, 255);

            SelectionBoxData data = new SelectionBoxData(selectionId, selectionName,
                    new ColorCache(r / 255F, g / 255F, b / 255F, a / 255F),
                    new CustomAABB(x1, y1, z1, x2, y2, z2)
            );
            // If a cuboid with the same ID already exists, it will be replaced.
            minecraft.selectionBoxes.put(selectionId, data);

        } else if (packetType == PacketType.REMOVE_SELECTION_CUBOID) {
            if (!networkManager.isExtEnabled(ProtocolExtension.SELECTION_CUBOID)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: SelectionCuboid");
            }
            byte selectionId = (byte) packetParams[0];
            if (minecraft.selectionBoxes.remove(selectionId) == null) {
                LogUtil.logWarning("Attempting to remove selection with unknown id " + selectionId);
            }

        } else if (packetType == PacketType.ENV_SET_COLOR) {
            if (!networkManager.isExtEnabled(ProtocolExtension.ENV_COLORS)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: EnvColors");
            }
            byte envVariable = (byte) packetParams[0];
            int r = (short) packetParams[1];
            int g = (short) packetParams[2];
            int b = (short) packetParams[3];
            // If R, G, or B is out-of-range, we should reset the color to default.
            boolean doReset = (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255);
            int dec = (r & 0x0ff) << 16 | (g & 0x0ff) << 8 | b & 0x0ff;

            // Don't mess with "minecraft.level" if we're still in the process of loading a level.
            Level level;
            if (isLoadingLevel) {
                level = newLevel;
            } else {
                level = minecraft.level;
            }

            switch (envVariable) {
                case 0: // sky
                    if (doReset) {
                        level.skyColor = Level.DEFAULT_SKY_COLOR;
                    } else {
                        level.skyColor = dec;
                    }
                    break;
                case 1: // cloud
                    if (doReset) {
                        level.cloudColor = Level.DEFAULT_CLOUD_COLOR;
                    } else {
                        level.cloudColor = dec;
                    }
                    break;
                case 2: // fog
                    if (doReset) {
                        level.fogColor = Level.DEFAULT_FOG_COLOR;
                    } else {
                        level.fogColor = dec;
                    }
                    break;
                case 3: // ambient light
                    if (doReset) {
                        level.customShadowColor = null;
                    } else {
                        level.customShadowColor = new ColorCache(r / 255F, g / 255F, b / 255F);
                    }
                    if (!isLoadingLevel) {
                        minecraft.levelRenderer.refresh();
                    }
                    break;
                case 4: // diffuse color
                    if (doReset) {
                        level.customLightColor = null;
                    } else {
                        level.customLightColor = new ColorCache(r / 255F, g / 255F, b / 255F);
                    }
                    if (!isLoadingLevel) {
                        minecraft.levelRenderer.refresh();
                    }
                    break;
            }

        } else if (packetType == PacketType.ENV_SET_MAP_APPEARANCE) {
            if (!networkManager.isExtEnabled(ProtocolExtension.ENV_MAP_APPEARANCE)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: EnvMapAppearance");
            }
            String textureUrl = (String) packetParams[0];
            byte sideBlock = (byte) packetParams[1];
            byte edgeBlock = (byte) packetParams[2];
            short sideLevel = (short) packetParams[3];
            //LogUtil.logInfo("ENV_SET_MAP_APPEARANCE(" + textureUrl + "," + sideBlock + "," + edgeBlock + "," + sideLevel + ")");

            if (minecraft.level != null) {
                // Change waterLevel after level loading
                minecraft.level.waterLevel = sideLevel;
            } else {
                // Change waterLevel during level loading
                newLevel.waterLevel = sideLevel;
            }

            if (sideBlock > 0 && sideBlock < Block.blocks.length) {
                minecraft.textureManager.setSideBlock(sideBlock);
            } else {
                minecraft.textureManager.resetSideBlock();
            }
            if (edgeBlock > 0 && edgeBlock < Block.blocks.length) {
                minecraft.textureManager.setEdgeBlock(edgeBlock);
            } else {
                minecraft.textureManager.resetEdgeBlock();
            }

            if (minecraft.level != null) {
                minecraft.levelRenderer.refreshEnvironment();
            }

            if (!minecraft.settings.canServerChangeTextures) {
                LogUtil.logInfo("Denied server's request to change the texture pack.");
            } else if (textureUrl.length() > 0) {
                File textureDir = new File(Minecraft.getMinecraftDirectory(), "/skins/terrain");
                if (!textureDir.exists()) {
                    textureDir.mkdirs();
                }
                String hash = minecraft.getHash(textureUrl);
                if (hash != null) {
                    File file = new File(textureDir, hash + ".png");
                    BufferedImage image;
                    if (!file.exists()) {
                        LogUtil.logInfo("Downloading texture pack " + hash + " from " + textureUrl);
                        minecraft.downloadImage(new URL(textureUrl), file);
                    }
                    try {
                        image = ImageIO.read(file);                        
                        if (image.getWidth() % 16 == 0 && image.getHeight() % 16 == 0) {
                            minecraft.textureManager.setTerrainTexture(image);
                        } else {
                            LogUtil.logInfo("Unacceptable terrain texture dimensions: " + image.getWidth() + " x " + image.getHeight());
                        }
                    } catch (Exception ex) {
                        LogUtil.logWarning("Terrain file does not exist, reverting to default textures.", ex);
                        minecraft.textureManager.setTerrainTexture(null);
                    }
                }
            } else {
                // Reset texture to default
                LogUtil.logInfo("Reset terrain texture to default.");
                minecraft.textureManager.setTerrainTexture(null);
            }

        } else if (packetType == PacketType.CLICK_DISTANCE) {
            if (!networkManager.isExtEnabled(ProtocolExtension.CLICK_DISTANCE)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: ClickDistance");
            }
            short clickDistance = (short) packetParams[0];
            minecraft.gamemode.reachDistance = clickDistance / 32;

        } else if (packetType == PacketType.HOLD_THIS) {
            if (!networkManager.isExtEnabled(ProtocolExtension.HELD_BLOCK)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: HeldBlock");
            }
            byte blockToHold = (byte) packetParams[0];
            byte preventChange = (byte) packetParams[1];
            boolean canPreventChange = preventChange > 0;

            if (canPreventChange) {
                GameSettings.CanReplaceSlot = false;
            }

            minecraft.player.inventory.selected = 0;
            minecraft.player.inventory.replaceSlot(Block.blocks[blockToHold]);

            if (!canPreventChange) {
                GameSettings.CanReplaceSlot = true;
            }

        } else if (packetType == PacketType.SET_TEXT_HOTKEY) {
            LogUtil.logWarning("Server attempted to use unsupported extension: TextHotKey");
            String label = (String) packetParams[0];
            String action = (String) packetParams[1];
            int keyCode = (int) packetParams[2];
            byte keyMods = (byte) packetParams[3];
            HotKeyData data = new HotKeyData(label, action, keyCode, keyMods);
            //minecraft.hotKeys.add(data);

        } else if (packetType == PacketType.EXT_ADD_PLAYER_NAME) {
            if (!networkManager.isExtEnabled(ProtocolExtension.EXT_PLAYER_LIST_2)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: ExtPlayerList");
            }
            short nameId = (short) packetParams[0];
            String playerName = (String) packetParams[1];
            String listName = (String) packetParams[2];
            String groupName = (String) packetParams[3];
            byte unusedRank = (byte) packetParams[4];

            int playerIndex = -1;

            for (PlayerListNameData b : minecraft.playerListNameData) {
                if (b.nameID == nameId) {
                    // Already exists, update the entry.
                    playerIndex = minecraft.playerListNameData.indexOf(b);
                    break;
                }
            }

            if (playerIndex == -1) {
                minecraft.playerListNameData.add(new PlayerListNameData(nameId,
                        playerName, listName, groupName, unusedRank));
            } else {
                minecraft.playerListNameData.set(playerIndex,
                        new PlayerListNameData(nameId, playerName,
                                listName, groupName, unusedRank));
            }

            Collections.sort(minecraft.playerListNameData, new PlayerListComparator());

        } else if (packetType == PacketType.EXT_ADD_ENTITY) {
            LogUtil.logWarning("Server attempted to use unsupported extension: ExtPlayerList version 1");
            byte playerID = (byte) packetParams[0];
            String inGameName = (String) packetParams[1];
            String skinName = (String) packetParams[2];
            handleExtAddEntity(networkManager, playerID, inGameName, skinName);

        } else if (packetType == PacketType.EXT_REMOVE_PLAYER_NAME) {
            if (!networkManager.isExtEnabled(ProtocolExtension.EXT_PLAYER_LIST_2)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: ExtPlayerList");
            }
            short nameID = (short) packetParams[0];
            List<PlayerListNameData> cache = minecraft.playerListNameData;
            for (int q = 0; q < minecraft.playerListNameData.size(); q++) {
                if (minecraft.playerListNameData.get(q).nameID == nameID) {
                    cache.remove(q);
                }
            }
            minecraft.playerListNameData = cache;

        } else if (packetType == PacketType.CUSTOM_BLOCK_SUPPORT_LEVEL) {
            if (!networkManager.isExtEnabled(ProtocolExtension.CUSTOM_BLOCKS)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: CustomBlocks");
            }
            byte supportLevel = (byte) packetParams[0];
            LogUtil.logInfo("Using CustomBlocks level " + supportLevel);
            networkManager.send(
                    PacketType.CUSTOM_BLOCK_SUPPORT_LEVEL,
                    Constants.CUSTOM_BLOCK_SUPPORT_LEVEL);
            SessionData.setAllowedBlocks(supportLevel);

        } else if (packetType == PacketType.SET_BLOCK_PERMISSIONS) {
            if (!networkManager.isExtEnabled(ProtocolExtension.BLOCK_PERMISSIONS)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: BlockPermissions");
            }
            byte blockType = (byte) packetParams[0];
            byte allowPlacement = (byte) packetParams[1];
            byte allowDeletion = (byte) packetParams[2];
            Block block = Block.blocks[blockType];
            if (block == null) {
                LogUtil.logWarning("Unknown block ID given for SetBlockPermission packet: " + blockType);
            } else {
                if (allowPlacement == 0) {
                    if (minecraft.disallowedPlacementBlocks.add(block)) {
                        LogUtil.logInfo("Disallowing placement of block: " + BlockID.findName(blockType));
                    }
                } else if (minecraft.disallowedPlacementBlocks.remove(block)) {
                    LogUtil.logInfo("Allowing placement of block: " + BlockID.findName(blockType));
                }
                if (allowDeletion == 0) {
                    if (minecraft.disallowedBreakingBlocks.add(block)) {
                        LogUtil.logInfo("Disallowing deletion of block: " + BlockID.findName(blockType));
                    }
                } else if (minecraft.disallowedBreakingBlocks.remove(block)) {
                    LogUtil.logInfo("Allowing deletion of block: " + BlockID.findName(blockType));
                }
            }

        } else if (packetType == PacketType.CHANGE_MODEL) {
            if (!networkManager.isExtEnabled(ProtocolExtension.CHANGE_MODEL)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: ChangeModel");
            }
            byte playerId = (byte) packetParams[0];
            // Model names are case-insensitive
            String modelName = ((String) packetParams[1]).toLowerCase();
            HumanoidMob targetPlayer;
            //LogUtil.logInfo("CM: " + playerId + " " + modelName);

            if (playerId >= 0) {
                // Set another player's model
                targetPlayer = networkManager.getPlayer(playerId);
            } else {
                // Set own model
                targetPlayer = minecraft.player;
            }
            if (targetPlayer != null && !targetPlayer.getModelName().equals(modelName)) {
                ModelManager m = new ModelManager();
                if (m.getModel(modelName) != null) {
                    targetPlayer.setModel(modelName);
                } else {
                    // Unknown model name given -- reset to humanoid
                    targetPlayer.setModel(Model.HUMANOID);
                }

                if (targetPlayer.getModelName().equals(Model.HUMANOID)) {
                    targetPlayer.setSkin(targetPlayer.lastHumanoidSkinName);
                }
            }

        } else if (packetType == PacketType.ENV_SET_WEATHER_TYPE) {
            if (!networkManager.isExtEnabled(ProtocolExtension.ENV_WEATHER_TYPE)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: EnvWeatherType");
            }
            byte weatherType = (byte) packetParams[0];
            if (weatherType == 0) {
                minecraft.isRaining = false;
                minecraft.isSnowing = false;
            } else if (weatherType == 1) {
                minecraft.isRaining = !minecraft.isRaining;
                minecraft.isSnowing = false;
            } else if (weatherType == 2) {
                minecraft.isSnowing = !minecraft.isSnowing;
                minecraft.isRaining = false;
            }

        } else if (packetType == PacketType.EXT_ADD_ENTITY2) {
            if (!networkManager.isExtEnabled(ProtocolExtension.EXT_PLAYER_LIST_2)) {
                LogUtil.logWarning("Server attempted to use unsupported extension: ExtPlayerList version 2");
            }
            // "When an ExtAddEntity2 packet is received, it must be treated as the SpawnPlayer packet.
            // A player model must be spawned in-game at the given location, with InGameName text
            // drawn above it. Skin should be loaded using the given SkinName for a player name.
            // When client receives ExtAddEntity2 packet for an already-spawned player, a duplicate
            // entity must not be spawned and existing entity's position must not be changed.
            // Instead their InGameName and SkinName must be updated. If a negative EntityID is
            // given for ExtAddEntity2, client must update player's own spawn point, InGameName, and SkinName."
            byte playerID = (byte) packetParams[0];
            String inGameName = (String) packetParams[1];
            String skinName = (String) packetParams[2];
            short spawnX = (short) packetParams[3];
            short spawnY = (short) packetParams[4];
            short spawnZ = (short) packetParams[5];
            byte spawnYaw = (byte) packetParams[6];
            byte spawnPitch = (byte) packetParams[7];
            //LogUtil.logInfo("EAE2: " + playerID + " " + inGameName + " " + skinName);

            if (playerID < 0 || networkManager.getPlayer(playerID) == null) {
                handleSpawnPlayer(networkManager, inGameName, playerID, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
            }

            handleExtAddEntity(networkManager, playerID, inGameName, skinName);
        }
    }

    private void handleExtAddEntity(NetworkManager networkManager, byte playerID, String inGameName, String skinName) {
        if (skinName != null) {
            if (playerID >= 0) {
                NetworkPlayer targetPlayer = networkManager.getPlayer(playerID);
                if (targetPlayer != null) {
                    targetPlayer.setSkin(skinName);
                    targetPlayer.lastHumanoidSkinName = skinName;
                    targetPlayer.displayName = inGameName;
                }
            } else {
                minecraft.player.setSkin(skinName);
                minecraft.player.lastHumanoidSkinName = skinName;
                //No need to set the display name for yourself
            }
        }
    }

    private void handleSpawnPlayer(NetworkManager networkManager, String newPlayerName, byte newPlayerId,
            short newPlayerX, short newPlayerY, short newPlayerZ, byte newPlayerXRot, byte newPlayerYRot) {
        if (newPlayerId >= 0) {
            newPlayerXRot = (byte) (newPlayerXRot + 128);
            newPlayerY = (short) (newPlayerY - 22);
            NetworkPlayer newPlayer
                    = new NetworkPlayer(minecraft,
                            newPlayerName, newPlayerX, newPlayerY, newPlayerZ,
                            newPlayerYRot * 360 / 256F, newPlayerXRot * 360 / 256F);
            networkManager.addPlayer(newPlayerId, newPlayer);
            minecraft.level.addEntity(newPlayer);
        } else {
            // Set own spawnpoint
            minecraft.level.setSpawnPos(newPlayerX / 32, newPlayerY / 32, newPlayerZ / 32,
                    newPlayerXRot * 320 / 256);
            minecraft.player.moveTo(newPlayerX / 32F,
                    newPlayerY / 32F, newPlayerZ / 32F,
                    newPlayerXRot * 360 / 256F, newPlayerYRot * 360 / 256F);
        }
    }
}
