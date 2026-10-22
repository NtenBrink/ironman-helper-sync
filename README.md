# Ironman Helper Sync

Keeps your account up to date on [Ironman Helper](https://ironman-helper.com), a comparison site for a small group of OSRS ironman friends: your bank, your stats, quests and achievement diaries, what you are wearing and carrying, your collection log, and your XP drops live.

## How it works

1. Log in on Ironman Helper, open your player page and go to the **Bank** tab.
2. Click **Create token** and copy the token.
3. In RuneLite, open the settings of **Ironman Helper Sync** and paste the token into **Sync token**.
4. Play as usual:
   - **Bank:** open your bank in-game. A few seconds after the last change, the plugin sends it and shows a chat message such as `Ironman Helper: bank synced (412 items).` The bank can only be read while it is open.
   - **Stats, quests, diaries, worn items and inventory:** sent about 10 seconds after you log in, then at most every 10 minutes when something changed, and together with each bank sync.
   - **Collection log:** open your collection log in-game and click **Search** once. The game then loads every item you have, and the plugin sends them. After that, new items are picked up from the chat message *New item added to your collection log* (turn on that game setting).
   - **Live XP:** your XP drops are bundled and sent at most every 5 seconds. Your player page shows that you are skilling, with an XP per hour estimate.

The plugin skips uploads when nothing has changed.

## Side panel

The plugin adds a small panel to the RuneLite sidebar with a **Sync now** button. It sends your stats and items right away, and your bank if you opened it this session. The panel shows when each type of data was last synced and whether that worked.

## Settings

| Setting | Default | Description |
|---|---|---|
| Sync token | *(empty)* | Token from the Bank tab; nothing is sent while this is empty |
| Chat messages | on | Show a chat message after each bank sync |

## Data sent to a third-party server

This plugin sends data to `ironman-helper.com`, which is not run by Jagex or RuneLite. The address is fixed and cannot be changed. The plugin only sends data when a sync token is set; with a token, all of the data below is synced. To stop syncing, clear the token or turn off the plugin.

Every request includes your display name (it is part of the request URL) and your sync token, as the `Authorization` header. The plugin sends:

- **Bank:** the items in your bank (item id, item name and quantity) and the time of the sync.
- **Stats, quests and diaries:** your XP per skill, the state of each quest (not started, in progress or finished) and which achievement diary tiers you have completed.
- **Worn items and inventory:** the items you are wearing (with their slot) and the items in your inventory: item id, item name and quantity.
- **Collection log:** the item ids of collection log items you have, read when you open the log in-game, and the item name from the *New item added to your collection log* chat message.
- **Live XP:** per skill, your new XP total and how much XP you gained since the previous update.

Nothing else is sent. That includes your location, account details, password, other chat messages and RuneLite settings. The plugin skips bank placeholders, and it does not sync on Leagues, Deadman, beta or other worlds with separate progress.

The token only allows updating your own data on Ironman Helper. You can revoke it on the Bank tab at any time, after which the plugin can no longer sync.

## Development

Requires Java 11.

```bash
./gradlew run     # starts RuneLite in developer mode with the plugin loaded
```
