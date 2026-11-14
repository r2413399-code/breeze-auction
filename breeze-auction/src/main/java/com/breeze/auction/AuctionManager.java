package com.breeze.auction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All auction rules: creating, bidding, ending, refunds, saving. */
public final class AuctionManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = AuctionConfig.DIR.resolve("auctions.json");

	private static MinecraftServer server;
	private static RegistryWrapper.WrapperLookup registries;
	private static final List<Auction> AUCTIONS = new ArrayList<>();
	private static final Map<UUID, List<ItemStack>> PENDING = new HashMap<>();
	private static int nextId = 1;

	private AuctionManager() {
	}

	// ------------------------------------------------------------------ lifecycle
	public static void start(MinecraftServer s) {
		server = s;
		registries = s.getRegistryManager();
		load();
	}

	public static void stop() {
		save();
		server = null;
	}

	public static List<Auction> all() {
		return AUCTIONS;
	}

	public static Auction find(int id) {
		for (Auction a : AUCTIONS) {
			if (a.id == id) {
				return a;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ currency helpers
	public static Item currencyItem(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null || !Registries.ITEM.containsId(identifier)) {
			return null;
		}
		return Registries.ITEM.get(identifier);
	}

	public static Text money(long amount, String currencyId) {
		Item item = currencyItem(currencyId);
		Text name = item == null ? Text.literal(currencyId) : new ItemStack(item).getName();
		return Text.literal(amount + "x ").append(name);
	}

	public static Text itemText(ItemStack stack) {
		return Text.literal(stack.getCount() + "x ").append(stack.getName());
	}

	private static int count(ServerPlayerEntity player, Item item) {
		PlayerInventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) {
				total += s.getCount();
			}
		}
		return total;
	}

	private static void take(ServerPlayerEntity player, Item item, int amount) {
		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < inv.size() && amount > 0; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) {
				int n = Math.min(amount, s.getCount());
				s.decrement(n);
				amount -= n;
			}
		}
	}

	/** Gives the stack to the player if online and there is room; whatever is left waits for /auction claim. */
	private static void deliver(UUID uuid, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
		if (player != null) {
			player.getInventory().insertStack(stack);
		}
		if (!stack.isEmpty()) {
			PENDING.computeIfAbsent(uuid, k -> new ArrayList<>()).add(stack);
			if (player != null) {
				player.sendMessage(Text.literal("Your inventory is full - use /auction claim to collect the rest.").formatted(Formatting.YELLOW), false);
			}
		}
	}

	private static void deliverMoney(UUID uuid, long amount, String currencyId) {
		Item item = currencyItem(currencyId);
		if (item == null) {
			return;
		}
		long left = amount;
		while (left > 0) {
			int c = (int) Math.min(left, item.getMaxCount());
			deliver(uuid, new ItemStack(item, c));
			left -= c;
		}
	}

	private static void tell(UUID uuid, Text text) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
		if (p != null) {
			p.sendMessage(text, false);
		}
	}

	private static void announce(Text text) {
		server.getPlayerManager().broadcast(prefix().append(text), false);
	}

	public static Text prefix() {
		return Text.literal("[Auction] ").formatted(Formatting.GOLD, Formatting.BOLD);
	}

	// ------------------------------------------------------------------ rules
	/** Returns an error message, or null when the auction was created. */
	public static String create(ServerPlayerEntity seller, long durationMs, long minBid, long maxBid) {
		AuctionConfig cfg = AuctionConfig.get();
		ItemStack hand = seller.getMainHandStack();
		if (hand.isEmpty()) {
			return "Hold the item you want to auction in your main hand.";
		}
		if (currencyItem(cfg.currencyItem) == null) {
			return "The payment item in the config (" + cfg.currencyItem + ") does not exist.";
		}
		if (durationMs < cfg.minDurationSeconds * 1000L) {
			return "The auction must last at least " + cfg.minDurationSeconds + " seconds.";
		}
		if (durationMs > cfg.maxDurationMinutes * 60_000L) {
			return "The auction can last at most " + cfg.maxDurationMinutes + " minutes.";
		}
		if (minBid < 1 || minBid > 1_000_000) {
			return "The minimum bid must be between 1 and 1000000.";
		}
		if (maxBid != 0 && (maxBid < minBid || maxBid > 1_000_000)) {
			return "The maximum bid must be at least the minimum bid (and at most 1000000).";
		}
		int active = 0;
		for (Auction a : AUCTIONS) {
			if (a.seller.equals(seller.getUuid())) {
				active++;
			}
		}
		if (active >= cfg.maxActivePerSeller) {
			return "You already have " + active + " running auctions.";
		}

		Auction a = new Auction();
		a.id = nextId++;
		a.seller = seller.getUuid();
		a.sellerName = seller.getName().getString();
		a.item = hand.copy();
		a.minBid = minBid;
		a.maxBid = maxBid;
		a.currency = cfg.currencyItem;
		a.endTime = System.currentTimeMillis() + durationMs;
		seller.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
		AUCTIONS.add(a);
		save();

		Text maxText = maxBid == 0 ? Text.literal("none") : money(maxBid, a.currency);
		announce(Text.literal("#" + a.id + " ").formatted(Formatting.YELLOW)
				.append(Text.literal(a.sellerName + " is auctioning ").formatted(Formatting.WHITE))
				.append(itemText(a.item).copy().formatted(Formatting.AQUA))
				.append(Text.literal(" - min bid ").formatted(Formatting.WHITE)).append(money(minBid, a.currency))
				.append(Text.literal(", max bid ").formatted(Formatting.WHITE)).append(maxText)
				.append(Text.literal(", ends in " + Util.duration(durationMs) + ". Use /auction bid <amount> " + a.id).formatted(Formatting.WHITE)));
		return null;
	}

	/** Returns an error message, or null when the bid was placed. */
	public static String bid(ServerPlayerEntity bidder, Auction a, long amount) {
		AuctionConfig cfg = AuctionConfig.get();
		if (a.seller.equals(bidder.getUuid())) {
			return "You cannot bid on your own auction.";
		}
		if (a.topBidder != null && a.topBidder.equals(bidder.getUuid()) && a.topBid == amount) {
			return "You already have that bid.";
		}
		if (a.topBidder == null) {
			if (amount < a.minBid) {
				return "The minimum bid is " + a.minBid + ".";
			}
		} else if (amount < a.topBid + cfg.minIncrement) {
			return "You must bid at least " + (a.topBid + cfg.minIncrement) + " (the highest bid is " + a.topBid + ").";
		}
		if (a.maxBid != 0 && amount > a.maxBid) {
			return "The maximum bid is " + a.maxBid + ".";
		}
		Item currency = currencyItem(a.currency);
		if (currency == null) {
			return "This auction's payment item no longer exists.";
		}

		boolean raising = a.topBidder != null && a.topBidder.equals(bidder.getUuid());
		long needed = raising ? amount - a.topBid : amount;
		if (count(bidder, currency) < needed) {
			return "You need " + needed + " " + a.currency + " in your inventory to place this bid.";
		}
		take(bidder, currency, (int) needed);

		if (a.topBidder != null && !raising) {
			deliverMoney(a.topBidder, a.topBid, a.currency);
			tell(a.topBidder, prefix().append(Text.literal("You were outbid on #" + a.id + " - your " + a.topBid + " was returned.").formatted(Formatting.YELLOW)));
		}
		a.topBidder = bidder.getUuid();
		a.topBidderName = bidder.getName().getString();
		a.topBid = amount;
		save();

		announce(Text.literal(a.topBidderName + " bid ").formatted(Formatting.WHITE)
				.append(money(amount, a.currency).copy().formatted(Formatting.GREEN))
				.append(Text.literal(" on #" + a.id + " (").formatted(Formatting.WHITE))
				.append(itemText(a.item)).append(Text.literal(").").formatted(Formatting.WHITE)));

		if (a.maxBid != 0 && amount >= a.maxBid) {
			end(a);
		}
		return null;
	}

	/** Ends the auction: item to the winner, payment to the seller, or the item back to the seller if nobody bid. */
	public static void end(Auction a) {
		AUCTIONS.remove(a);
		if (a.topBidder == null) {
			deliver(a.seller, a.item.copy());
			tell(a.seller, prefix().append(Text.literal("Nobody bid on #" + a.id + " - your item was returned.").formatted(Formatting.YELLOW)));
			announce(Text.literal("#" + a.id + " (").formatted(Formatting.WHITE).append(itemText(a.item))
					.append(Text.literal(") ended with no bids.").formatted(Formatting.WHITE)));
		} else {
			deliver(a.topBidder, a.item.copy());
			deliverMoney(a.seller, a.topBid, a.currency);
			tell(a.topBidder, prefix().append(Text.literal("You won #" + a.id + "!").formatted(Formatting.GREEN)));
			tell(a.seller, prefix().append(Text.literal("Your auction #" + a.id + " sold for " + a.topBid + ".").formatted(Formatting.GREEN)));
			announce(Text.literal(a.topBidderName + " won #" + a.id + " (").formatted(Formatting.WHITE).append(itemText(a.item))
					.append(Text.literal(") for ").formatted(Formatting.WHITE)).append(money(a.topBid, a.currency).copy().formatted(Formatting.GREEN)));
		}
		save();
	}

	public static void cancel(Auction a) {
		AUCTIONS.remove(a);
		if (a.topBidder != null) {
			deliverMoney(a.topBidder, a.topBid, a.currency);
			tell(a.topBidder, prefix().append(Text.literal("Auction #" + a.id + " was cancelled - your " + a.topBid + " was returned.").formatted(Formatting.YELLOW)));
		}
		deliver(a.seller, a.item.copy());
		announce(Text.literal("#" + a.id + " was cancelled.").formatted(Formatting.WHITE));
		save();
	}

	/** Called once per second. */
	public static void tick() {
		long now = System.currentTimeMillis();
		for (Auction a : new ArrayList<>(AUCTIONS)) {
			long left = a.endTime - now;
			if (left <= 0) {
				end(a);
			} else if (left <= 60_000 && !a.warned) {
				a.warned = true;
				announce(Text.literal("#" + a.id + " (").formatted(Formatting.WHITE).append(itemText(a.item))
						.append(Text.literal(") ends in under a minute!").formatted(Formatting.YELLOW)));
			}
		}
	}

	/** Hands out anything that was waiting (full inventory / offline). Returns how many stacks were given. */
	public static int claim(ServerPlayerEntity player) {
		List<ItemStack> waiting = PENDING.get(player.getUuid());
		if (waiting == null || waiting.isEmpty()) {
			return 0;
		}
		int given = 0;
		List<ItemStack> left = new ArrayList<>();
		for (ItemStack s : waiting) {
			player.getInventory().insertStack(s);
			if (s.isEmpty()) {
				given++;
			} else {
				left.add(s);
			}
		}
		if (left.isEmpty()) {
			PENDING.remove(player.getUuid());
		} else {
			PENDING.put(player.getUuid(), left);
		}
		save();
		return given;
	}

	public static boolean hasPending(UUID uuid) {
		List<ItemStack> l = PENDING.get(uuid);
		return l != null && !l.isEmpty();
	}

	// ------------------------------------------------------------------ saving
	private static JsonElement encode(ItemStack stack) {
		RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, registries);
		return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
	}

	private static ItemStack decode(JsonElement json) {
		RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, registries);
		return ItemStack.CODEC.parse(ops, json).getOrThrow();
	}

	public static void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("nextId", nextId);

			JsonArray list = new JsonArray();
			for (Auction a : AUCTIONS) {
				JsonObject o = new JsonObject();
				o.addProperty("id", a.id);
				o.addProperty("seller", a.seller.toString());
				o.addProperty("sellerName", a.sellerName);
				o.add("item", encode(a.item));
				o.addProperty("minBid", a.minBid);
				o.addProperty("maxBid", a.maxBid);
				o.addProperty("currency", a.currency);
				o.addProperty("endTime", a.endTime);
				if (a.topBidder != null) {
					o.addProperty("topBidder", a.topBidder.toString());
					o.addProperty("topBidderName", a.topBidderName);
					o.addProperty("topBid", a.topBid);
				}
				o.addProperty("warned", a.warned);
				list.add(o);
			}
			root.add("auctions", list);

			JsonObject pending = new JsonObject();
			for (Map.Entry<UUID, List<ItemStack>> e : PENDING.entrySet()) {
				JsonArray arr = new JsonArray();
				for (ItemStack s : e.getValue()) {
					arr.add(encode(s));
				}
				pending.add(e.getKey().toString(), arr);
			}
			root.add("pending", pending);

			Files.createDirectories(AuctionConfig.DIR);
			Files.writeString(FILE, GSON.toJson(root));
		} catch (Exception e) {
			BreezeAuction.LOGGER.warn("Could not save auctions", e);
		}
	}

	private static void load() {
		AUCTIONS.clear();
		PENDING.clear();
		if (!Files.exists(FILE)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			nextId = root.has("nextId") ? root.get("nextId").getAsInt() : 1;

			for (JsonElement el : root.getAsJsonArray("auctions")) {
				try {
					JsonObject o = el.getAsJsonObject();
					Auction a = new Auction();
					a.id = o.get("id").getAsInt();
					a.seller = UUID.fromString(o.get("seller").getAsString());
					a.sellerName = o.get("sellerName").getAsString();
					a.item = decode(o.get("item"));
					a.minBid = o.get("minBid").getAsLong();
					a.maxBid = o.get("maxBid").getAsLong();
					a.currency = o.get("currency").getAsString();
					a.endTime = o.get("endTime").getAsLong();
					if (o.has("topBidder")) {
						a.topBidder = UUID.fromString(o.get("topBidder").getAsString());
						a.topBidderName = o.get("topBidderName").getAsString();
						a.topBid = o.get("topBid").getAsLong();
					}
					a.warned = o.has("warned") && o.get("warned").getAsBoolean();
					AUCTIONS.add(a);
				} catch (Exception e) {
					BreezeAuction.LOGGER.warn("Skipped an auction that could not be read", e);
				}
			}

			if (root.has("pending")) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("pending").entrySet()) {
					List<ItemStack> stacks = new ArrayList<>();
					for (JsonElement s : e.getValue().getAsJsonArray()) {
						try {
							stacks.add(decode(s));
						} catch (Exception ex) {
							BreezeAuction.LOGGER.warn("Skipped an unreadable waiting item", ex);
						}
					}
					PENDING.put(UUID.fromString(e.getKey()), stacks);
				}
			}
		} catch (Exception e) {
			BreezeAuction.LOGGER.warn("Could not load auctions", e);
		}
	}
}
