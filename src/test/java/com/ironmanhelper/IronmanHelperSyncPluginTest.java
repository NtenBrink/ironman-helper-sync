package com.ironmanhelper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Start RuneLite in developer-modus met deze plugin geladen (gradlew run). */
public class IronmanHelperSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(IronmanHelperSyncPlugin.class);
		RuneLite.main(args);
	}
}
