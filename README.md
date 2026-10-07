# Ironman Helper Sync

Keeps your bank up to date on [Ironman Helper](https://ironman-helper.com), a comparison site for a small group of OSRS ironman friends. You no longer need to export and paste your bank by hand.

## How it works

1. Log in on Ironman Helper, open your player page and go to the **Bank** tab.
2. Click **Create token** and copy the token.
3. In RuneLite, open the settings of **Ironman Helper Sync** and paste the token into **Sync token**.
4. Open your bank in-game. A few seconds after the last change, the plugin sends your bank and shows a chat message such as `Ironman Helper: bank synced (412 items).`

The bank can only be read while it is open, so the plugin syncs when you visit a bank. It skips the upload when nothing has changed.

## Settings

| Setting | Default | Description |
|---|---|---|
| Server URL | `https://ironman-helper.com` | Address of the Ironman Helper app |
| Sync token | *(empty)* | Token from the Bank tab; nothing is sent while this is empty |
| Sync automatically | on | Send the bank a few seconds after it changes |
| Chat messages | on | Show a chat message after each sync |

## Data sent to a third-party server

This plugin sends data to the server in the **Server URL** setting (by default `ironman-helper.com`, which is not run by Jagex or RuneLite). It only sends data when a sync token is set, and only after your bank changes.

Each sync sends:

- your display name (it is part of the request URL);
- the items in your bank: item id, item name and quantity;
- the time of the sync;
- your sync token, as the `Authorization` header.

Nothing else is sent. That includes your inventory, equipment, location, account details, password and RuneLite settings. The plugin skips placeholders, and it does not sync on Leagues, Deadman, beta or other worlds with a separate bank.

The token only allows updating your own bank on Ironman Helper. You can revoke it on the Bank tab at any time, after which the plugin can no longer sync.

## Development

Requires Java 11.

```bash
./gradlew run     # starts RuneLite in developer mode with the plugin loaded
```
