package com.minecolonies.util;

import cpw.mods.fml.common.registry.LanguageRegistry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import java.util.List;

/**
 * Helper class for localization and sending player messages
 *
 * @author Colton
 */
public class LanguageHandler
{
    private static String getString(String key)
    {
        return LanguageRegistry.instance().getStringLocalization(key);
    }

    /**
     * Localize a string and use String.format().
     *
     * @param key unlocalized key
     * @param args Objects for String.format()
     * @return Localized string
     */
    public static String format(String key, Object... args)
    {
        return String.format(getString(key), args);
    }

    private static void sendPlayerMessage(EntityPlayer player, String message)
    {
        player.addChatComponentMessage(new ChatComponentText(message));
    }

    /**
     * Send a localized and formatted message to a player
     *
     * @param player player to send the message to
     * @param key unlocalized key
     * @param args Objects for String.format()
     */
    public static void sendPlayerLocalizedMessage(EntityPlayer player, String key, Object... args)
    {
        sendPlayerMessage(player, format(key, args));
    }

    private static void sendPlayersMessage(List<EntityPlayer> players, String message)
    {
        for (EntityPlayer player : players)
        {
            sendPlayerMessage(player, message);
        }
    }

    /**
     * Send a localized and formatted message to multiple players
     *
     * @param players EntityPlayers to send the message to
     * @param key unlocalized key
     * @param args Objects for String.format()
     */
    public static void sendPlayersLocalizedMessage(List<EntityPlayer> players, String key, Object... args)
    {
        sendPlayersMessage(players, format(key, args));
    }
}
