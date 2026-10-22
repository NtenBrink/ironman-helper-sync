package com.ironmanhelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Instellingen. Het serveradres staat vast in de plugin (IronmanHelperSyncPlugin.SERVER_URL) en is hier niet
 * te zien of te wijzigen. Per soort data een eigen schakelaar: de speler kiest zelf wat er gedeeld wordt.
 */
@ConfigGroup(IronmanHelperSyncConfig.GROUP)
public interface IronmanHelperSyncConfig extends Config
{
	String GROUP = "ironmanhelpersync";

	@ConfigSection(
		name = "What to share",
		description = "Choose which data is sent to Ironman Helper",
		position = 10
	)
	String shareSection = "share";

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
		description = "Show a chat message after each bank sync",
		position = 2
	)
	default boolean chatMessages()
	{
		return true;
	}

	// keyName blijft "autoSync" (versie 1), zodat bestaande instellingen behouden blijven
	@ConfigItem(
		keyName = "autoSync",
		name = "Bank",
		description = "Send your bank a few seconds after it changes",
		section = shareSection,
		position = 11
	)
	default boolean syncBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncStats",
		name = "Stats, quests and diaries",
		description = "Send your XP per skill, quest progress and achievement diaries (after logging in and at most every 10 minutes when something changed)",
		section = shareSection,
		position = 12
	)
	default boolean syncStats()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCarried",
		name = "Worn items and inventory",
		description = "Send what you are wearing and carrying, so gear and transport advice include it",
		section = shareSection,
		position = 13
	)
	default boolean syncCarried()
	{
		return true;
	}

	@ConfigItem(
		keyName = "liveXp",
		name = "Live XP",
		description = "Share your XP drops live (bundled, at most every 5 seconds), shown on your player page",
		section = shareSection,
		position = 14
	)
	default boolean liveXp()
	{
		return true;
	}
}
