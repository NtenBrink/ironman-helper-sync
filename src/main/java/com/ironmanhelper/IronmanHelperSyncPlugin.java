package com.ironmanhelper;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Stuurt de bank naar Ironman Helper zodra hij verandert. De bank is alleen leesbaar als hij in-game
 * open is; na de laatste wijziging wachten we een paar seconden, zodat er niet bij elke klik verstuurd wordt.
 */
@Slf4j
@PluginDescriptor(
	name = "Ironman Helper Sync",
	description = "Syncs your bank to Ironman Helper whenever it changes",
	tags = {"bank", "sync", "ironman", "export"}
)
public class IronmanHelperSyncPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	/** Wachten na de laatste bankwijziging voordat we versturen */
	private static final long DEBOUNCE_SECONDS = 5;
	/** De server staat maximaal één sync per 30 s toe; wij houden wat marge aan */
	private static final long MIN_GAP_MS = 35_000;
	/** Werelden met een aparte bank (leagues, deadman, beta enz.) niet syncen */
	private static final Set<WorldType> SKIP_WORLDS = EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE,
		WorldType.TOURNAMENT_WORLD, WorldType.FRESH_START_WORLD);

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

	private ScheduledFuture<?> pendingSend;
	private volatile Snapshot pending;
	private volatile String lastSentSignature;
	private volatile long lastSentAt;

	@AllArgsConstructor
	private static class BankItem
	{
		final int id;
		final String name;
		final long qty;
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

	@Override
	protected void shutDown()
	{
		cancelPending();
		pending = null;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK || !config.autoSync() || config.syncToken().isBlank())
		{
			return;
		}
		if (!java.util.Collections.disjoint(client.getWorldType(), SKIP_WORLDS))
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
		pendingSend = executor.schedule(this::send, delayMs, TimeUnit.MILLISECONDS);
	}

	private synchronized void cancelPending()
	{
		if (pendingSend != null)
		{
			pendingSend.cancel(false);
			pendingSend = null;
		}
	}

	private void send()
	{
		Snapshot snapshot = pending;
		if (snapshot == null || snapshot.signature.equals(lastSentSignature))
		{
			return;
		}
		long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastSentAt);
		if (wait > 0)
		{
			schedule(wait);
			return;
		}

		HttpUrl base = HttpUrl.parse(config.serverUrl().trim());
		if (base == null)
		{
			chat("Ironman Helper: the server URL in the plugin settings is not valid.");
			return;
		}
		HttpUrl url = base.newBuilder()
			.addPathSegments("api/bank")
			.addPathSegment(snapshot.player)
			.addPathSegment("sync")
			.build();
		String body = gson.toJson(new SyncBody(snapshot.items, Instant.now().toString()));
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + config.syncToken().trim())
			.post(RequestBody.create(JSON, body))
			.build();

		lastSentAt = System.currentTimeMillis();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bank sync failed", e);
				chat("Ironman Helper: could not reach the server, your bank was not synced.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					handleResponse(response, snapshot);
				}
			}
		});
	}

	private void handleResponse(Response response, Snapshot snapshot)
	{
		switch (response.code())
		{
			case 200:
				lastSentSignature = snapshot.signature;
				chat("Ironman Helper: bank synced (" + snapshot.items.size() + " items).");
				break;
			case 401:
				chat("Ironman Helper: the sync token is not valid for " + snapshot.player + ". Create a new one on the Bank tab.");
				break;
			case 429:
				String retry = response.header("Retry-After");
				long seconds = retry != null && retry.matches("\\d+") ? Long.parseLong(retry) : 30;
				schedule(seconds * 1000 + 1000);
				break;
			default:
				chat("Ironman Helper: sync failed (status " + response.code() + ").");
		}
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
