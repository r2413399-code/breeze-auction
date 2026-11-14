# Breeze Auction

Server-side Fabric mod for Minecraft 1.21.11. Players do NOT need to install anything.

## Install on the server
1. Fabric server 1.21.11 + Fabric API.
2. Put `breeze-auction-1.0.0.jar` in the server's `mods` folder and start the server once.
3. In the server console run: `/auction admin add YourName` (admins may create and cancel auctions).
4. Edit `config/breeze-auction/config.json` if you want another payment item (default `minecraft:diamond`).

## Commands
- `/auction create <time> <minBid> [maxBid]` - hold any item (any stack, enchants/names kept) and auction it.
  Time: `30` = 30 minutes, or `90s`, `45m`, `2h`, `3d`.
- `/auction bid <amount> [id]` - bids are paid with the payment item taken from your inventory.
- `/auction list`, `/auction info <id>`, `/auction cancel <id>`, `/auction claim`

## Rules
- When the time is up, the highest bid wins the item; the seller gets the winning bid.
- Nobody bid: the item goes back to the seller.
- Minimum bid: the first bid must be at least this. Maximum bid: no bid may go above it, and a bid equal to it
  ends the auction immediately (buy-out).
- Outbid players get their bid back automatically. Items that do not fit in an inventory (or the player is
  offline) wait for `/auction claim` / next login. Auctions survive restarts.
