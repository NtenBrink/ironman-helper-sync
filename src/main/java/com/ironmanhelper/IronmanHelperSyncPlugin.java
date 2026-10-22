package com.ironmanhelper;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Stuurt gegevens naar Ironman Helper (ironman-helper.com):
 * - de bank, een paar seconden nadat hij verandert (alleen leesbaar als hij in-game open is);
 * - het profiel: XP per skill, quests, achievement diaries, wat je draagt en je inventory. Ongeveer 10 s na het
 *   inloggen, daarna hooguit elke 10 minuten als er iets veranderd is, en mee met elke bank-sync;
 * - het collection log: als je het in-game opent en zoekt (script 4100 per verkregen item, net als WikiSync), en
 *   nieuwe items uit de chatmelding "New item added to your collection log";
 * - live XP-drops, gebundeld en hooguit elke 5 seconden.
 * Met een sync-token wordt alles gesynct; het serveradres staat vast.
 */
@Slf4j
@PluginDescriptor(
	name = "Ironman Helper Sync",
	description = "Syncs your bank, stats, quests and gear to Ironman Helper",
	tags = {"bank", "sync", "ironman", "export", "xp", "quests"}
)
public class IronmanHelperSyncPlugin extends Plugin
{
	/** Vast serveradres; niet in te stellen door de speler */
	static final String SERVER_URL = "https://ironman-helper.com";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	/** Wachten na de laatste bankwijziging voordat we versturen */
	private static final long DEBOUNCE_SECONDS = 5;
	/** De server staat maximaal één bank-sync per 30 s toe; wij houden wat marge aan */
	private static final long BANK_GAP_MS = 35_000;
	/** Profiel: hooguit elke 10 minuten vanzelf; de server staat er één per 10 s toe */
	private static final long PROFILE_INTERVAL_MS = 10 * 60_000;
	private static final long PROFILE_MIN_GAP_MS = 11_000;
	private static final long PROFILE_AFTER_LOGIN_MS = 10_000;
	private static final long LIVE_FLUSH_SECONDS = 5;
	/** Script dat per verkregen collection log-item afgaat als het log zijn items laadt; argument 1 is het item-id */
	private static final int CLOG_ITEM_SCRIPT = 4100;
	private static final String CLOG_CHAT_PREFIX = "New item added to your collection log:";
	/** Werelden met een aparte bank en stats (leagues, deadman, beta enz.) niet syncen */
	private static final Set<WorldType> SKIP_WORLDS = EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE,
		WorldType.TOURNAMENT_WORLD, WorldType.FRESH_START_WORLD);

	/** Equipment-slot → naam zoals de app hem gebruikt */
	private static final Map<EquipmentInventorySlot, String> SLOT_NAMES = new LinkedHashMap<>();

	static
	{
		SLOT_NAMES.put(EquipmentInventorySlot.HEAD, "head");
		SLOT_NAMES.put(EquipmentInventorySlot.CAPE, "cape");
		SLOT_NAMES.put(EquipmentInventorySlot.AMULET, "neck");
		SLOT_NAMES.put(EquipmentInventorySlot.WEAPON, "weapon");
		SLOT_NAMES.put(EquipmentInventorySlot.BODY, "body");
		SLOT_NAMES.put(EquipmentInventorySlot.SHIELD, "shield");
		SLOT_NAMES.put(EquipmentInventorySlot.LEGS, "legs");
		SLOT_NAMES.put(EquipmentInventorySlot.GLOVES, "hands");
		SLOT_NAMES.put(EquipmentInventorySlot.BOOTS, "feet");
		SLOT_NAMES.put(EquipmentInventorySlot.RING, "ring");
		SLOT_NAMES.put(EquipmentInventorySlot.AMMO, "ammo");
	}

	/** Achievement diaries: regio (namen zoals WikiSync) → varbits voor Easy, Medium, Hard, Elite (> 0 = klaar) */
	private static final Map<String, int[]> DIARIES = new LinkedHashMap<>();
	private static final String[] TIERS = {"Easy", "Medium", "Hard", "Elite"};

	static
	{
		DIARIES.put("Ardougne", new int[]{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE});
		DIARIES.put("Desert", new int[]{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE});
		DIARIES.put("Falador", new int[]{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE});
		DIARIES.put("Fremennik", new int[]{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE});
		DIARIES.put("Kandarin", new int[]{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE});
		// Karamja Easy/Medium/Hard hebben een eigen varbit (ATJUN_*_DONE), Elite de gewone
		DIARIES.put("Karamja", new int[]{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE});
		DIARIES.put("Kourend & Kebos", new int[]{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE});
		DIARIES.put("Lumbridge & Draynor", new int[]{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE});
		DIARIES.put("Morytania", new int[]{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE});
		DIARIES.put("Varrock", new int[]{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE});
		DIARIES.put("Western Provinces", new int[]{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE});
		DIARIES.put("Wilderness", new int[]{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE});
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private IronmanHelperSyncConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	private IronmanHelperSyncPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> profileTicker;
	private ScheduledFuture<?> liveTicker;

	// --- Bank ---
	private ScheduledFuture<?> pendingSend;
	private volatile Snapshot pending;
	/** Laatst gelezen bank (ook als die al verstuurd is), voor "Sync now" */
	private volatile Snapshot lastBank;
	private volatile String lastSentSignature;
	private volatile long lastSentAt;

	// --- Profiel ---
	private volatile String lastProfileSignature;
	private volatile long lastProfileAt;
	private volatile long profileNotBefore;

	// --- Live XP ---
	private final Map<Skill, Integer> lastXp = new ConcurrentHashMap<>();
	private final Map<String, LiveDrop> pendingDrops = new ConcurrentHashMap<>();
	private volatile boolean xpBaselineNeeded = true;

	// --- Collection log ---
	/** Item-ids uit het geopende log, nog niet verstuurd */
	private final Set<Integer> clogIds = ConcurrentHashMap.newKeySet();
	/** Itemnamen uit de chatmelding, nog niet verstuurd */
	private final Set<String> clogNames = ConcurrentHashMap.newKeySet();
	private int clogLastTick = -1;

	// --- Speler en wereld (bijgewerkt op de client-thread) ---
	private volatile String playerName;
	private volatile boolean skipWorld;
	private GameState lastState;

	@AllArgsConstructor
	private static class BankItem
	{
		final int id;
		final String name;
		final long qty;
	}

	@AllArgsConstructor
	private static class CarriedItem
	{
		final int id;
		final String name;
		final long qty;
		final String slot;
	}

	@AllArgsConstructor
	private static class Snapshot
	{
		final String player;
		final List<BankItem> items;
		final String signature;
	}

	@AllArgsConstructor
	private static class SyncBody
	{
		final List<BankItem> items;
		final String capturedAt;
	}

	/** Profiel; velden die null zijn laat Gson weg, en de server laat die onderdelen dan zoals ze waren */
	private static class Profile
	{
		Map<String, Integer> skills;
		Map<String, Integer> quests;
		Map<String, Map<String, Boolean>> diaries;
		List<CarriedItem> equipment;
		List<CarriedItem> inventory;
		List<Integer> collectionLog;
		List<String> collectionLogNew;
		String capturedAt;
		transient String player;
		transient String signature;
	}

	@AllArgsConstructor
	private static class LiveDrop
	{
		final String skill;
		final int xp;
		final int delta;
	}

	@AllArgsConstructor
	private static class LiveBody
	{
		final List<LiveDrop> drops;
		final String at;
	}

	@Override
	protected void startUp()
	{
		panel = new IronmanHelperSyncPanel(this::syncNow);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Ironman Helper Sync")
			.icon(icon)
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		profileTicker = executor.scheduleAtFixedRate(() -> requestProfile(PROFILE_INTERVAL_MS, false), 1, 1, TimeUnit.MINUTES);
		liveTicker = executor.scheduleAtFixedRate(this::flushLive, LIVE_FLUSH_SECONDS, LIVE_FLUSH_SECONDS, TimeUnit.SECONDS);
		xpBaselineNeeded = true;
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		cancelPending();
		if (profileTicker != null)
		{
			profileTicker.cancel(false);
		}
		if (liveTicker != null)
		{
			liveTicker.cancel(false);
		}
		pending = null;
		lastBank = null;
		pendingDrops.clear();
		lastXp.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			skipWorld = !java.util.Collections.disjoint(client.getWorldType(), SKIP_WORLDS);
			// Echt ingelogd (of van wereld gewisseld), niet alleen een laadscherm: XP opnieuw inlezen en het profiel sturen
			if (lastState == GameState.LOGGING_IN || lastState == GameState.HOPPING || lastState == GameState.LOGIN_SCREEN)
			{
				xpBaselineNeeded = true;
				profileNotBefore = System.currentTimeMillis() + PROFILE_AFTER_LOGIN_MS;
				executor.schedule(() -> requestProfile(0, false), PROFILE_AFTER_LOGIN_MS + 500, TimeUnit.MILLISECONDS);
			}
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			pendingDrops.clear();
			playerName = null;
		}
		lastState = state;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			playerName = local.getName().replace(' ', ' ');
		}
		if (xpBaselineNeeded)
		{
			for (Skill skill : Skill.values())
			{
				if (!"Overall".equals(skill.getName()))
				{
					lastXp.put(skill, client.getSkillExperience(skill));
				}
			}
			xpBaselineNeeded = false;
		}
		flushCollectionLog();
	}

	// ---------------------------------------------------------------- Bank

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK || config.syncToken().isBlank() || skipWorld)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}

		// Itemnamen en placeholders opvragen kan alleen op de client-thread (daar komt dit event binnen)
		Snapshot snapshot = snapshot(local.getName().replace(' ', ' '), event.getItemContainer());
		if (snapshot.items.isEmpty())
		{
			return;
		}
		pending = snapshot;
		lastBank = snapshot;
		schedule(DEBOUNCE_SECONDS * 1000);
	}

	private Snapshot snapshot(String player, ItemContainer container)
	{
		List<BankItem> items = new ArrayList<>();
		StringBuilder signature = new StringBuilder();
		for (Item item : container.getItems())
		{
			int id = item.getId();
			int qty = item.getQuantity();
			if (id <= 0 || qty <= 0)
			{
				continue;
			}
			ItemComposition comp = itemManager.getItemComposition(id);
			if (comp.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			items.add(new BankItem(id, comp.getName(), qty));
			signature.append(id).append(':').append(qty).append(',');
		}
		return new Snapshot(player, items, signature.toString());
	}

	private synchronized void schedule(long delayMs)
	{
		cancelPending();
		pendingSend = executor.schedule(() -> sendBank(false), delayMs, TimeUnit.MILLISECONDS);
	}

	private synchronized void cancelPending()
	{
		if (pendingSend != null)
		{
			pendingSend.cancel(false);
			pendingSend = null;
		}
	}

	private void sendBank(boolean force)
	{
		Snapshot snapshot = force ? lastBank : pending;
		if (snapshot == null || (!force && snapshot.signature.equals(lastSentSignature)))
		{
			return;
		}
		long wait = BANK_GAP_MS - (System.currentTimeMillis() - lastSentAt);
		if (wait > 0)
		{
			pending = snapshot;
			schedule(wait);
			return;
		}

		String body = gson.toJson(new SyncBody(snapshot.items, Instant.now().toString()));
		lastSentAt = System.currentTimeMillis();
		post(new String[]{"api", "bank", snapshot.player, "sync"}, body, (code, retryAfter, reply) ->
		{
			switch (code)
			{
				case 200:
					lastSentSignature = snapshot.signature;
					// Het aantal zoals de site het telt: bank fillers tellen niet mee en items met dezelfde naam
					// (bv. graceful in andere kleuren) zijn één regel. Zo klopt de melding met de Bank-tab.
					int count = snapshot.items.size();
					boolean changed = true;
					try
					{
						BankReply r = gson.fromJson(reply, BankReply.class);
						if (r != null && r.items > 0)
						{
							count = r.items;
						}
						if (r != null && r.changed != null)
						{
							changed = r.changed;
						}
					}
					catch (RuntimeException e)
					{
						log.debug("Unexpected bank sync reply", e);
					}
					// Chatmelding alleen als de bank echt anders is dan op de site (het paneel toont het altijd)
					panel.setStatus(IronmanHelperSyncPanel.Kind.BANK, true, count + " items" + (changed ? "" : ", no changes"));
					if (changed)
					{
						chat("Ironman Helper: bank synced (" + count + " items).");
					}
					// Het profiel gaat mee met de bank-sync
					requestProfile(PROFILE_MIN_GAP_MS, false);
					break;
				case 401:
					panel.setStatus(IronmanHelperSyncPanel.Kind.BANK, false, "token not valid");
					chat("Ironman Helper: the sync token is not valid for " + snapshot.player + ". Create a new one on the Bank tab.");
					break;
				case 429:
					pending = snapshot;
					schedule(retryAfter * 1000 + 1000);
					break;
				case -1:
					panel.setStatus(IronmanHelperSyncPanel.Kind.BANK, false, "server not reachable");
					chat("Ironman Helper: could not reach the server, your bank was not synced.");
					break;
				default:
					panel.setStatus(IronmanHelperSyncPanel.Kind.BANK, false, "status " + code);
					chat("Ironman Helper: sync failed (status " + code + ").");
			}
		});
	}

	// ---------------------------------------------------------------- Profiel

	/** Knop "Sync now": het profiel meteen, en de bank als die deze sessie open is geweest. */
	private void syncNow()
	{
		if (config.syncToken().isBlank())
		{
			panel.setStatus(IronmanHelperSyncPanel.Kind.PROFILE, false, "no sync token in the settings");
			return;
		}
		panel.setSyncing(true);
		executor.schedule(() -> panel.setSyncing(false), 3, TimeUnit.SECONDS);
		requestProfile(0, true);
		if (lastBank != null)
		{
			executor.execute(() -> sendBank(true));
		}
	}

	/**
	 * Profiel versturen als het veranderd is en het vorige minstens `minGapMs` geleden was (`force`: ook als het niet
	 * veranderd is). De gegevens worden op de client-thread gelezen, het versturen gebeurt daarbuiten.
	 */
	private void requestProfile(long minGapMs, boolean force)
	{
		if (config.syncToken().isBlank())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN || skipWorld || playerName == null)
			{
				return;
			}
			Profile profile = buildProfile();
			executor.execute(() -> sendProfile(profile, minGapMs, force));
		});
	}

	private Profile buildProfile()
	{
		Profile p = new Profile();
		p.player = playerName;
		StringBuilder sig = new StringBuilder();

		// XP, quests en diaries
		p.skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (!"Overall".equals(skill.getName()))
			{
				int xp = client.getSkillExperience(skill);
				p.skills.put(skill.getName(), xp);
				sig.append(xp).append(',');
			}
		}
		p.quests = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			int v = state == QuestState.FINISHED ? 2 : state == QuestState.IN_PROGRESS ? 1 : 0;
			p.quests.put(quest.getName(), v);
			sig.append(v);
		}
		p.diaries = new LinkedHashMap<>();
		for (Map.Entry<String, int[]> e : DIARIES.entrySet())
		{
			Map<String, Boolean> tiers = new LinkedHashMap<>();
			for (int i = 0; i < TIERS.length; i++)
			{
				boolean done = client.getVarbitValue(e.getValue()[i]) > 0;
				tiers.put(TIERS[i], done);
				sig.append(done ? '1' : '0');
			}
			p.diaries.put(e.getKey(), tiers);
		}

		// Wat je draagt en bij je hebt
		p.equipment = new ArrayList<>();
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn != null)
		{
			for (Map.Entry<EquipmentInventorySlot, String> e : SLOT_NAMES.entrySet())
			{
				Item item = worn.getItem(e.getKey().getSlotIdx());
				if (item != null && item.getId() > 0 && item.getQuantity() > 0)
				{
					p.equipment.add(new CarriedItem(item.getId(), itemManager.getItemComposition(item.getId()).getName(), item.getQuantity(), e.getValue()));
					sig.append(e.getValue()).append(item.getId()).append(':').append(item.getQuantity()).append(',');
				}
			}
		}
		p.inventory = new ArrayList<>();
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv != null)
		{
			for (Item item : inv.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					p.inventory.add(new CarriedItem(item.getId(), itemManager.getItemComposition(item.getId()).getName(), item.getQuantity(), null));
					sig.append(item.getId()).append(':').append(item.getQuantity()).append(',');
				}
			}
		}

		// Collection log: alleen wat sinds de vorige verzending binnenkwam (de server telt het op)
		if (!clogIds.isEmpty())
		{
			p.collectionLog = new ArrayList<>(clogIds);
			sig.append("clog").append(clogIds.size());
		}
		if (!clogNames.isEmpty())
		{
			p.collectionLogNew = new ArrayList<>(clogNames);
			sig.append("clognew").append(clogNames.size());
		}
		p.signature = sig.toString();
		return p;
	}

	private void sendProfile(Profile profile, long minGapMs, boolean force)
	{
		long now = System.currentTimeMillis();
		if (!force && profile.signature.equals(lastProfileSignature))
		{
			return;
		}
		if (!force && (now - lastProfileAt < minGapMs || now < profileNotBefore))
		{
			return;
		}
		// De server staat één profiel per 10 s toe: anders iets later opnieuw
		long wait = PROFILE_MIN_GAP_MS - (now - lastProfileAt);
		if (wait > 0)
		{
			executor.schedule(() -> sendProfile(profile, 0, force), wait, TimeUnit.MILLISECONDS);
			return;
		}
		profile.capturedAt = Instant.now().toString();
		lastProfileAt = now;
		post(new String[]{"api", "player", profile.player, "sync"}, gson.toJson(profile), (code, retryAfter, reply) ->
		{
			if (code == 200)
			{
				lastProfileSignature = profile.signature;
				// Verstuurde collection log-items niet nog eens sturen
				if (profile.collectionLog != null)
				{
					clogIds.removeAll(profile.collectionLog);
				}
				if (profile.collectionLogNew != null)
				{
					clogNames.removeAll(profile.collectionLogNew);
				}
				panel.setStatus(IronmanHelperSyncPanel.Kind.PROFILE, true, profile.collectionLog != null ? "synced, incl. collection log" : "synced");
			}
			else if (code == 429)
			{
				executor.schedule(() -> sendProfile(profile, 0, force), retryAfter * 1000 + 1000, TimeUnit.MILLISECONDS);
			}
			else
			{
				panel.setStatus(IronmanHelperSyncPanel.Kind.PROFILE, false, code == 401 ? "token not valid" : code == -1 ? "server not reachable" : "status " + code);
			}
		});
	}

	// ---------------------------------------------------------------- Collection log

	/**
	 * Als het collection log zijn items laadt (vooral bij "Search" in het log), gaat dit script af voor elk item dat
	 * je hebt; argument 1 is het item-id. Zelfde methode als de WikiSync-plugin.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != CLOG_ITEM_SCRIPT || event.getScriptEvent() == null || skipWorld)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args != null && args.length > 1 && args[1] instanceof Integer && (Integer) args[1] > 0)
		{
			clogIds.add((Integer) args[1]);
			clogLastTick = client.getTickCount();
		}
	}

	/** "New item added to your collection log: Baby mole" (melding moet in-game aan staan). */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || skipWorld)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		if (!message.startsWith(CLOG_CHAT_PREFIX))
		{
			return;
		}
		String name = message.substring(CLOG_CHAT_PREFIX.length()).trim();
		if (!name.isEmpty())
		{
			clogNames.add(name);
			requestProfile(0, true);
		}
	}

	/** Twee ticks na het laatste item is het log klaar met laden: dan meteen versturen. */
	private void flushCollectionLog()
	{
		if (clogLastTick != -1 && client.getTickCount() >= clogLastTick + 2)
		{
			clogLastTick = -1;
			requestProfile(0, true);
		}
	}

	// ---------------------------------------------------------------- Live XP

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if ("Overall".equals(skill.getName()) || xpBaselineNeeded)
		{
			return;
		}
		int xp = event.getXp();
		Integer before = lastXp.put(skill, xp);
		if (before == null || xp <= before || skipWorld)
		{
			return;
		}
		// Per skill bundelen tot de volgende verzending: laatste XP, en de toename opgeteld
		pendingDrops.merge(skill.getName(), new LiveDrop(skill.getName(), xp, xp - before),
			(a, b) -> new LiveDrop(a.skill, Math.max(a.xp, b.xp), a.delta + b.delta));
	}

	private void flushLive()
	{
		String player = playerName;
		if (pendingDrops.isEmpty() || player == null || config.syncToken().isBlank())
		{
			return;
		}
		List<LiveDrop> drops = new ArrayList<>();
		for (String skill : new ArrayList<>(pendingDrops.keySet()))
		{
			LiveDrop d = pendingDrops.remove(skill);
			if (d != null)
			{
				drops.add(d);
			}
		}
		if (drops.isEmpty())
		{
			return;
		}
		post(new String[]{"api", "player", player, "live"}, gson.toJson(new LiveBody(drops, Instant.now().toString())), (code, retryAfter, reply) ->
		{
			if (code == 200)
			{
				panel.setStatus(IronmanHelperSyncPanel.Kind.LIVE, true, drops.get(drops.size() - 1).skill);
				return;
			}
			if (code == 429 || code == -1)
			{
				// Bij de volgende verzending opnieuw proberen
				for (LiveDrop d : drops)
				{
					pendingDrops.merge(d.skill, d, (a, b) -> new LiveDrop(a.skill, Math.max(a.xp, b.xp), a.delta + b.delta));
				}
			}
			if (code != 429)
			{
				panel.setStatus(IronmanHelperSyncPanel.Kind.LIVE, false, code == 401 ? "token not valid" : code == -1 ? "server not reachable" : "status " + code);
			}
		});
	}

	// ---------------------------------------------------------------- HTTP

	/** Antwoord van de server: statuscode (-1 = niet bereikbaar), Retry-After in seconden en de body. */
	private interface Reply
	{
		void accept(int code, long retryAfter, String body);
	}

	/** Antwoord op een bank-sync: hoeveel verschillende items de site telt (bank fillers eruit, gelijke namen samen). */
	private static class BankReply
	{
		int items;
		/** Of de bank anders is dan de vorige keer op de server (null bij een oudere server: dan als veranderd) */
		Boolean changed;
	}

	/** POST naar de server. */
	private void post(String[] segments, String json, Reply done)
	{
		HttpUrl base = HttpUrl.parse(SERVER_URL);
		if (base == null)
		{
			return;
		}
		HttpUrl.Builder url = base.newBuilder();
		for (String s : segments)
		{
			url.addPathSegment(s);
		}
		Request request = new Request.Builder()
			.url(url.build())
			.header("Authorization", "Bearer " + config.syncToken().trim())
			.post(RequestBody.create(JSON, json))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Ironman Helper sync failed", e);
				done.accept(-1, 0L, "");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					String retry = response.header("Retry-After");
					long seconds = retry != null && retry.matches("\\d+") ? Long.parseLong(retry) : 30;
					String text = response.body() != null ? response.body().string() : "";
					done.accept(response.code(), seconds, text);
				}
				catch (IOException e)
				{
					done.accept(response.code(), 30, "");
				}
			}
		});
	}

	private void chat(String message)
	{
		if (!config.chatMessages())
		{
			return;
		}
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}

	@Provides
	IronmanHelperSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronmanHelperSyncConfig.class);
	}
}
