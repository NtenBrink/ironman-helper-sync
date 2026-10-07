package com.ironmanhelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(IronmanHelperSyncConfig.GROUP)
public interface IronmanHelperSyncConfig extends Config
{
	String GROUP = "ironmanhelpersync";

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "Address of the Ironman Helper app",
		position = 1
	)
	default String serverUrl()
	{
		return "https://ironman-helper.com";
	}

	@ConfigItem(
		keyName = "syncToken",
		name = "Sync token",
		description = "Create a token on the Bank tab of your player page (log in first) and paste it here",
		secret = true,
		position = 2
	)
	default String syncToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoSync",
		name = "Sync automatically",
		description = "Send your bank a few seconds after it changes",
		position = 3
	)
	default boolean autoSync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatMessages",
		name = "Chat messages",
		description = "Show a chat message after each sync",
		position = 4
	)
	default boolean chatMessages()
	{
		return true;
	}
}
