package com.ironmanhelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Instellingen. Met een token wordt alles gesynct (bank, stats, quests, diaries, items, collection log, live XP);
 * het serveradres staat vast in de plugin (IronmanHelperSyncPlugin.SERVER_URL).
 */
@ConfigGroup(IronmanHelperSyncConfig.GROUP)
public interface IronmanHelperSyncConfig extends Config
{
	String GROUP = "ironmanhelpersync";

	@ConfigItem(
		keyName = "syncToken",
		name = "Sync token",
		description = "Create a token on the Bank tab of your player page (log in first) and paste it here",
		secret = true,
		position = 1
	)
	default String syncToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "chatMessages",
		name = "Chat messages",
		description = "Show a chat message when your bank was synced with changes",
		position = 2
	)
	default boolean chatMessages()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareLocation",
		name = "Share location",
		description = "Show where you are on the map on Ironman Helper (only your latest position, visible to logged-in players)",
		position = 3
	)
	default boolean shareLocation()
	{
		return true;
	}
}
